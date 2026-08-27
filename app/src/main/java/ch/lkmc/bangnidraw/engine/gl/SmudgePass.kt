package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.PoolExhausted
import ch.lkmc.bangnidraw.engine.core.RmwDabPlan
import ch.lkmc.bangnidraw.engine.core.RmwMixing
import ch.lkmc.bangnidraw.engine.core.RmwSpec
import ch.lkmc.bangnidraw.engine.core.RmwTileScissor
import ch.lkmc.bangnidraw.engine.core.RmwTouchTracker
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey

/** Ordered direct-to-layer smudge and blur passes. */
class SmudgePass(
    private val state: GlState,
    private val deposit: GlProgram,
    private val absorb: GlProgram,
    private val blurHorizontal: GlProgram,
    private val blurVertical: GlProgram,
    private val depositMix: GlProgram? = null,
    private val absorbMix: GlProgram? = null,
    private val mixboxLut: Int = 0,
) {

    private val before = OffscreenTarget("RMW before")
    private val work = OffscreenTarget("RMW work")
    private var pickupRead = OffscreenTarget("Smudge pickup A")
    private var pickupWrite = OffscreenTarget("Smudge pickup B")
    private val readFbo = GlFbo()
    private val drawFbo = GlFbo()
    private val quad = FullRectQuad()
    private var firstSmudgeDab = true

    private var sourceKeys = IntArray(0)
    private var outputKeys = IntArray(0)

    fun begin(spec: RmwSpec): Boolean {
        firstSmudgeDab = true
        if (spec !is RmwSpec.Smudge) return true
        if (!pickupRead.ensure(spec.pickupEdge, spec.pickupEdge, state)) return false
        if (!pickupWrite.ensure(spec.pickupEdge, spec.pickupEdge, state)) return false

        return clear(pickupRead) && clear(pickupWrite)
    }

    /** Stamps committed dabs only; predictions never modify the layer. */
    fun stamp(
        batch: DabBatch,
        layer: LayerTextures,
        spec: RmwSpec,
        tracker: RmwTouchTracker,
        onFirstTouch: (IntArray, Int) -> Unit,
    ): IntRect {
        val committed = batch.committedCount
        if (committed == 0) return IntRect.EMPTY

        ensureKeyCapacity(layer.grid)
        var dirty = IntRect.EMPTY
        for (index in 0 until committed) {
            val blurRadius = (spec as? RmwSpec.Blur)?.radius ?: 0
            val plan = RmwDabPlan.forDab(
                layer.grid,
                batch.x[index],
                batch.y[index],
                batch.radius[index],
                blurRadius,
            )
            if (plan.output.isEmpty || !copyBefore(layer, plan.source)) continue

            when (spec) {
                is RmwSpec.Smudge -> {
                    if (firstSmudgeDab) {
                        if (absorb(plan, batch, index, spec, FIRST_PICKUP_RATE)) {
                            firstSmudgeDab = false
                        }
                        continue
                    }

                    val added = tracker.add(plan.output, outputKeys)
                    if (added > 0) onFirstTouch(outputKeys, added)
                    deposit(plan, batch, index, layer, spec)
                    absorb(plan, batch, index, spec, spec.pickupRate)
                }
                is RmwSpec.Blur -> {
                    val added = tracker.add(plan.output, outputKeys)
                    if (added > 0) onFirstTouch(outputKeys, added)
                    blur(plan, batch, index, layer, spec)
                }
            }
            dirty = dirty.union(plan.output)
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        state.scissorOff()
        return dirty
    }

    private fun copyBefore(layer: LayerTextures, rect: IntRect): Boolean {
        if (!before.ensure(rect.width, rect.height, state)) return false
        if (!clear(before)) return false
        if (!drawFbo.bindTexture2d(before.texture, GLES30.GL_DRAW_FRAMEBUFFER)) return false

        val count = layer.grid.keysFor(rect, sourceKeys)
        try {
            for (index in 0 until count) {
                val key = TileKey(sourceKeys[index])
                val slice = layer.slice(key)
                if (slice.isNone) continue
                if (!readFbo.bindArrayLayer(
                        layer.pageTexture(slice.page),
                        slice.slice,
                        GLES30.GL_READ_FRAMEBUFFER,
                    )
                ) {
                    return false
                }

                val tile = layer.grid.tileRect(key)
                val left = maxOf(rect.left, tile.left)
                val top = maxOf(rect.top, tile.top)
                val right = minOf(rect.right, tile.right)
                val bottom = minOf(rect.bottom, tile.bottom)
                GLES30.glBlitFramebuffer(
                    left - tile.left,
                    top - tile.top,
                    right - tile.left,
                    bottom - tile.top,
                    left - rect.left,
                    top - rect.top,
                    right - rect.left,
                    bottom - rect.top,
                    GLES30.GL_COLOR_BUFFER_BIT,
                    GLES30.GL_NEAREST,
                )
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        }
        return true
    }

    private fun deposit(
        plan: RmwDabPlan,
        batch: DabBatch,
        index: Int,
        layer: LayerTextures,
        spec: RmwSpec.Smudge,
    ) {
        val active = if (
            spec.mixing == RmwMixing.Pigment && depositMix != null && mixboxLut != 0
        ) {
            depositMix
        } else {
            deposit
        }
        bindSmudgeProgram(active)
        active.uniform1i("u_before", BEFORE_UNIT)
        active.uniform1i("u_pickup", WORK_UNIT)
        active.uniform2f("u_scratchOrigin", plan.source.left.toFloat(), plan.source.top.toFloat())
        active.uniform2f("u_scratchSize", plan.source.width.toFloat(), plan.source.height.toFloat())
        active.uniform1f("u_pickupEdge", spec.pickupEdge.toFloat())
        bindDab(active, batch, index)
        active.uniform1f("u_strength", batch.flow[index])
        bindTexture(BEFORE_UNIT, before.texture)
        bindTexture(WORK_UNIT, pickupRead.texture)

        drawOutputTiles(layer, plan.output, active) { program, key ->
            val origin = layer.grid.origin(key)
            program.uniform2f("u_tileOrigin", origin.x.toFloat(), origin.y.toFloat())
        }
    }

    private fun absorb(
        plan: RmwDabPlan,
        batch: DabBatch,
        index: Int,
        spec: RmwSpec.Smudge,
        pickupRate: Float,
    ): Boolean {
        val active = if (
            spec.mixing == RmwMixing.Pigment && absorbMix != null && mixboxLut != 0
        ) {
            absorbMix
        } else {
            absorb
        }
        bindSmudgeProgram(active)
        active.uniform1i("u_before", BEFORE_UNIT)
        active.uniform1i("u_pickup", WORK_UNIT)
        active.uniform2f("u_scratchOrigin", plan.source.left.toFloat(), plan.source.top.toFloat())
        active.uniform2f("u_scratchSize", plan.source.width.toFloat(), plan.source.height.toFloat())
        active.uniform1f("u_pickupEdge", spec.pickupEdge.toFloat())
        bindDab(active, batch, index)
        active.uniform1f("u_pickupRate", pickupRate)
        bindTexture(BEFORE_UNIT, before.texture)
        bindTexture(WORK_UNIT, pickupRead.texture)

        if (!drawFbo.bindTexture2d(pickupWrite.texture)) return false
        state.viewport(0, 0, spec.pickupEdge, spec.pickupEdge)
        state.scissorOff()
        state.blendOff()
        quad.draw(spec.pickupEdge.toFloat(), spec.pickupEdge.toFloat())

        val previous = pickupRead
        pickupRead = pickupWrite
        pickupWrite = previous
        return true
    }

    private fun blur(
        plan: RmwDabPlan,
        batch: DabBatch,
        index: Int,
        layer: LayerTextures,
        spec: RmwSpec.Blur,
    ) {
        if (!work.ensure(plan.source.width, plan.source.height, state)) return
        if (!drawFbo.bindTexture2d(work.texture)) return
        state.useProgram(blurHorizontal)
        state.viewport(0, 0, work.width, work.height)
        state.scissorOff()
        state.blendOff()
        blurHorizontal.uniform1i("u_source", BEFORE_UNIT)
        blurHorizontal.uniform2f("u_texel", 1f / before.width, 1f / before.height)
        blurHorizontal.uniform1i("u_radius", spec.radius)
        bindTexture(BEFORE_UNIT, before.texture)
        quad.draw(work.width.toFloat(), work.height.toFloat())

        state.useProgram(blurVertical)
        blurVertical.uniform1i("u_before", BEFORE_UNIT)
        blurVertical.uniform1i("u_horizontal", WORK_UNIT)
        blurVertical.uniform2f("u_scratchOrigin", plan.source.left.toFloat(), plan.source.top.toFloat())
        blurVertical.uniform2f("u_scratchSize", plan.source.width.toFloat(), plan.source.height.toFloat())
        blurVertical.uniform2f("u_texel", 1f / before.width, 1f / before.height)
        bindDab(blurVertical, batch, index)
        blurVertical.uniform1f("u_strength", batch.flow[index])
        blurVertical.uniform1i("u_radius", spec.radius)
        bindTexture(BEFORE_UNIT, before.texture)
        bindTexture(WORK_UNIT, work.texture)

        drawOutputTiles(layer, plan.output, blurVertical) { program, key ->
            val origin = layer.grid.origin(key)
            program.uniform2f("u_tileOrigin", origin.x.toFloat(), origin.y.toFloat())
        }
    }

    private inline fun drawOutputTiles(
        layer: LayerTextures,
        rect: IntRect,
        program: GlProgram,
        bindTile: (GlProgram, TileKey) -> Unit,
    ) {
        val count = layer.grid.keysFor(rect, sourceKeys)
        for (index in 0 until count) {
            val key = TileKey(sourceKeys[index])
            val slice = try {
                layer.sliceForWrite(key)
            } catch (_: PoolExhausted) {
                continue
            }
            if (!drawFbo.bindArrayLayer(layer.pageTexture(slice.page), slice.slice)) continue

            val scissor = RmwTileScissor.forRect(layer.grid, key, rect)
            state.viewport(0, 0, TILE_SIZE, TILE_SIZE)
            state.scissor(
                scissor.x,
                scissor.y,
                scissor.width,
                scissor.height,
            )
            state.blendOff()
            bindTile(program, key)
            quad.draw(TILE_SIZE.toFloat(), TILE_SIZE.toFloat())
        }
    }

    private fun bindSmudgeProgram(program: GlProgram) {
        state.useProgram(program)
        if (program !== depositMix && program !== absorbMix) return

        program.uniform1i("mixbox_lut", MIXBOX_LUT_UNIT)
        bindTexture(MIXBOX_LUT_UNIT, mixboxLut)
    }

    private fun bindDab(program: GlProgram, batch: DabBatch, index: Int) {
        program.uniform4f(
            "u_dab",
            batch.x[index],
            batch.y[index],
            batch.radius[index],
            batch.hardness[index],
        )
    }

    private fun bindTexture(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    private fun clear(target: OffscreenTarget): Boolean {
        if (!drawFbo.bindTexture2d(target.texture)) return false
        state.scissorOff()
        drawFbo.clear(0f, 0f, 0f, 0f)
        return true
    }

    private fun ensureKeyCapacity(grid: TileGrid) {
        if (sourceKeys.size < grid.tileCount) sourceKeys = IntArray(grid.tileCount)
        if (outputKeys.size < grid.tileCount) outputKeys = IntArray(grid.tileCount)
    }

    fun release() {
        before.release(state)
        work.release(state)
        pickupRead.release(state)
        pickupWrite.release(state)
        readFbo.release()
        drawFbo.release()
        quad.release()
    }

    private companion object {
        const val BEFORE_UNIT = 0
        const val WORK_UNIT = 1
        const val FIRST_PICKUP_RATE = 1f
    }
}
