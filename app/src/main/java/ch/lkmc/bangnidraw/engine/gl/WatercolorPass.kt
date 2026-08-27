package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.DabBatch
import ch.lkmc.bangnidraw.engine.core.IntRect
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.PoolExhausted
import ch.lkmc.bangnidraw.engine.core.RmwMixing
import ch.lkmc.bangnidraw.engine.core.RmwSpec
import ch.lkmc.bangnidraw.engine.core.RmwTileScissor
import ch.lkmc.bangnidraw.engine.core.RmwTouchTracker
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.WatercolorBehavior
import ch.lkmc.bangnidraw.engine.core.WatercolorDabPlan
import ch.lkmc.bangnidraw.engine.core.WatercolorKernel

/** Ordered color and coarse wet-state updates for watercolor gestures. */
internal class WatercolorPass(
    canvas: CanvasSize,
    private val pool: TilePool,
    private val state: GlState,
    private val wetProgram: GlProgram,
    private val colorProgram: GlProgram,
    private val colorMixProgram: GlProgram? = null,
    private val mixboxLut: Int = 0,
) {

    private val wetGrid = TileGrid(
        WatercolorKernel.wetPixels(canvas.width),
        WatercolorKernel.wetPixels(canvas.height),
    )
    private val wetLayers = LinkedHashMap<LayerId, LayerTextures>()

    private val colorBefore = OffscreenTarget("Watercolor color before")
    private val wetBefore = OffscreenTarget("Watercolor wet before")
    private val wetAfter = OffscreenTarget("Watercolor wet after")
    private val backupScratch = OffscreenTarget("Watercolor wet backup")
    private val readFbo = GlFbo()
    private val drawFbo = GlFbo()
    private val quad = FullRectQuad()

    private var colorKeys = IntArray(0)
    private var outputKeys = IntArray(0)
    private var wetKeys = IntArray(0)

    private var activeLayer: LayerId? = null
    private var wetBackup: LayerTextures? = null
    private val backedWetKeys = LinkedHashSet<TileKey>()
    private val absentWetKeys = HashSet<TileKey>()

    fun begin(layer: LayerId, spec: RmwSpec): Boolean {
        if (!spec.isWatercolor) return false

        finish()
        activeLayer = layer
        wetLayers.getOrPut(layer) { LayerTextures(wetGrid, pool) }
        wetBackup = LayerTextures(wetGrid, pool)
        ensureWetKeyCapacity()
        return true
    }

    fun stamp(
        batch: DabBatch,
        layer: LayerTextures,
        stroke: StrokeSpec,
        nowTick: Int,
        tracker: RmwTouchTracker,
        onFirstTouch: (IntArray, Int) -> Unit,
    ): IntRect {
        val spec = stroke.rmw ?: return IntRect.EMPTY
        val behavior = spec.watercolorBehavior ?: return IntRect.EMPTY
        val layerId = activeLayer ?: return IntRect.EMPTY
        val wetLayer = wetLayers[layerId] ?: return IntRect.EMPTY
        val committed = batch.committedCount
        if (committed == 0) return IntRect.EMPTY
        ensureColorKeyCapacity(layer.grid)

        var dirty = IntRect.EMPTY
        for (index in 0 until committed) {
            val plan = WatercolorDabPlan.forDab(
                layer.grid,
                batch.x[index],
                batch.y[index],
                batch.radius[index],
                behavior.spread,
            )
            if (plan.output.isEmpty) continue
            if (!copyTiles(layer, plan.source, colorBefore, colorKeys)) continue
            if (!copyTiles(wetLayer, plan.wetSource, wetBefore, wetKeys)) continue
            if (!renderWet(plan, batch, index, behavior, nowTick)) continue
            if (!backupWet(wetLayer, plan.wetOutput)) continue

            val added = tracker.add(plan.output, outputKeys)
            if (added > 0) onFirstTouch(outputKeys, added)
            drawColor(layer, plan, batch, index, stroke, behavior)
            writeWet(wetLayer, plan)
            dirty = dirty.union(plan.output)
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        state.scissorOff()
        return dirty
    }

    /** Keeps pre-gesture wetness while dropping the gesture's water. */
    fun cancel() {
        val layerId = activeLayer ?: return
        val wetLayer = wetLayers[layerId] ?: return resetStroke()
        val backup = wetBackup ?: return resetStroke()

        for (key in backedWetKeys) {
            if (key in absentWetKeys) {
                if (!wetLayer.slice(key).isNone) wetLayer.remove(key)
                continue
            }

            restoreWetKey(backup, wetLayer, key)
        }
        resetStroke()
    }

    fun finish() = resetStroke()

    fun removeLayer(layer: LayerId) {
        if (activeLayer == layer) resetStroke()
        wetLayers.remove(layer)?.release()
    }

    /** History and reopen operate on dry pigment; wetness is transient. */
    fun dryAll() {
        resetStroke()
        wetLayers.values.forEach(LayerTextures::release)
        wetLayers.clear()
    }

    fun forgetAll() {
        wetBackup?.forgetAll()
        wetLayers.values.forEach(LayerTextures::forgetAll)
        backedWetKeys.clear()
        absentWetKeys.clear()
        wetLayers.clear()
        wetBackup = null
        activeLayer = null
    }

    fun release() {
        dryAll()
        colorBefore.release(state)
        wetBefore.release(state)
        wetAfter.release(state)
        backupScratch.release(state)
        readFbo.release()
        drawFbo.release()
        quad.release()
    }

    private fun renderWet(
        plan: WatercolorDabPlan,
        batch: DabBatch,
        index: Int,
        behavior: WatercolorBehavior,
        nowTick: Int,
    ): Boolean {
        if (!wetAfter.ensureCapacity(plan.wetSource.width, plan.wetSource.height, state)) return false
        if (!drawFbo.bindTexture2d(wetAfter.texture)) return false

        state.useProgram(wetProgram)
        state.viewport(0, 0, wetAfter.width, wetAfter.height)
        state.scissorOff()
        state.blendOff()
        wetProgram.uniform1i("u_before", BEFORE_UNIT)
        wetProgram.uniform2f(
            "u_wetOrigin",
            plan.wetSource.left.toFloat(),
            plan.wetSource.top.toFloat(),
        )
        wetProgram.uniform2f(
            "u_wetTexel",
            1f / wetBefore.capacityWidth,
            1f / wetBefore.capacityHeight,
        )
        wetProgram.uniform2f(
            "u_wetScale",
            wetBefore.width.toFloat() / wetBefore.capacityWidth,
            wetBefore.height.toFloat() / wetBefore.capacityHeight,
        )
        bindDab(wetProgram, batch, index)
        wetProgram.uniform1f("u_waterLoad", behavior.waterLoad * batch.flow[index])
        wetProgram.uniform1f("u_spread", behavior.spread)
        wetProgram.uniform1f("u_nowTick", nowTick.toFloat())
        bindTexture(BEFORE_UNIT, wetBefore.texture)
        quad.draw(wetAfter.width.toFloat(), wetAfter.height.toFloat())
        return true
    }

    private fun drawColor(
        layer: LayerTextures,
        plan: WatercolorDabPlan,
        batch: DabBatch,
        index: Int,
        stroke: StrokeSpec,
        behavior: WatercolorBehavior,
    ) {
        val rmw = requireNotNull(stroke.rmw)
        val active = if (
            rmw.watercolorMixing == RmwMixing.Pigment && colorMixProgram != null && mixboxLut != 0
        ) {
            colorMixProgram
        } else {
            colorProgram
        }
        state.useProgram(active)
        active.uniform1i("u_before", BEFORE_UNIT)
        active.uniform1i("u_wet", WET_UNIT)
        bindColorSampling(active, plan)
        bindDab(active, batch, index)
        active.uniform3f("u_color", strokeR, strokeG, strokeB)
        active.uniform1f("u_strength", batch.flow[index])
        active.uniform1f("u_spread", behavior.spread)
        active.uniform1f("u_granulation", behavior.granulation)
        active.uniform1f("u_edgeDarkening", behavior.edgeDarkening)
        active.uniform1f("u_dilution", stroke.dilution)
        active.uniform1i("u_depositMode", if (rmw is RmwSpec.Watercolor) PIGMENT_DEPOSIT else CLEAR_WATER)
        active.uniform1i("u_alphaLock", if (stroke.alphaLock) 1 else 0)
        bindTexture(BEFORE_UNIT, colorBefore.texture)
        bindTexture(WET_UNIT, wetAfter.texture)
        if (active === colorMixProgram) {
            active.uniform1i("mixbox_lut", MIXBOX_LUT_UNIT)
            bindTexture(MIXBOX_LUT_UNIT, mixboxLut)
        }

        drawOutputTiles(layer, plan.output, active)
    }

    private var strokeR = 0f
    private var strokeG = 0f
    private var strokeB = 0f

    fun setColor(red: Float, green: Float, blue: Float) {
        strokeR = red
        strokeG = green
        strokeB = blue
    }

    private fun bindColorSampling(program: GlProgram, plan: WatercolorDabPlan) {
        program.uniform2f(
            "u_scratchOrigin",
            plan.source.left.toFloat(),
            plan.source.top.toFloat(),
        )
        program.uniform2f(
            "u_beforeTexel",
            1f / colorBefore.capacityWidth,
            1f / colorBefore.capacityHeight,
        )
        program.uniform2f(
            "u_beforeScale",
            colorBefore.width.toFloat() / colorBefore.capacityWidth,
            colorBefore.height.toFloat() / colorBefore.capacityHeight,
        )
        program.uniform2f(
            "u_wetOrigin",
            plan.wetSource.left.toFloat(),
            plan.wetSource.top.toFloat(),
        )
        program.uniform2f(
            "u_wetTexel",
            1f / wetAfter.capacityWidth,
            1f / wetAfter.capacityHeight,
        )
        program.uniform2f(
            "u_wetScale",
            wetAfter.width.toFloat() / wetAfter.capacityWidth,
            wetAfter.height.toFloat() / wetAfter.capacityHeight,
        )
    }

    private fun drawOutputTiles(layer: LayerTextures, rect: IntRect, program: GlProgram) {
        val count = layer.grid.keysFor(rect, colorKeys)
        for (index in 0 until count) {
            val key = TileKey(colorKeys[index])
            val slice = try {
                layer.sliceForWrite(key)
            } catch (_: PoolExhausted) {
                continue
            }
            if (!drawFbo.bindArrayLayer(layer.pageTexture(slice.page), slice.slice)) continue

            val scissor = RmwTileScissor.forRect(layer.grid, key, rect)
            state.viewport(0, 0, TILE_SIZE, TILE_SIZE)
            state.scissor(scissor.x, scissor.y, scissor.width, scissor.height)
            state.blendOff()
            val origin = layer.grid.origin(key)
            program.uniform2f("u_tileOrigin", origin.x.toFloat(), origin.y.toFloat())
            quad.draw(TILE_SIZE.toFloat(), TILE_SIZE.toFloat())
        }
    }

    private fun writeWet(layer: LayerTextures, plan: WatercolorDabPlan) {
        if (!readFbo.bindTexture2d(wetAfter.texture, GLES30.GL_READ_FRAMEBUFFER)) return
        state.scissorOff()
        val count = wetGrid.keysFor(plan.wetOutput, wetKeys)
        try {
            for (index in 0 until count) {
                val key = TileKey(wetKeys[index])
                val slice = try {
                    layer.sliceForWrite(key)
                } catch (_: PoolExhausted) {
                    continue
                }
                if (!drawFbo.bindArrayLayer(
                        layer.pageTexture(slice.page),
                        slice.slice,
                        GLES30.GL_DRAW_FRAMEBUFFER,
                    )
                ) {
                    continue
                }

                blitWetIntersection(plan.wetSource, plan.wetOutput, key)
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        }
    }

    private fun blitWetIntersection(source: IntRect, output: IntRect, key: TileKey) {
        val tile = wetGrid.tileRect(key)
        val left = maxOf(output.left, tile.left)
        val top = maxOf(output.top, tile.top)
        val right = minOf(output.right, tile.right)
        val bottom = minOf(output.bottom, tile.bottom)
        GLES30.glBlitFramebuffer(
            left - source.left,
            top - source.top,
            right - source.left,
            bottom - source.top,
            left - tile.left,
            top - tile.top,
            right - tile.left,
            bottom - tile.top,
            GLES30.GL_COLOR_BUFFER_BIT,
            GLES30.GL_NEAREST,
        )
    }

    private fun copyTiles(
        layer: LayerTextures,
        rect: IntRect,
        target: OffscreenTarget,
        keys: IntArray,
    ): Boolean {
        if (rect.isEmpty) return false
        if (!target.ensureCapacity(rect.width, rect.height, state)) return false
        if (!clear(target)) return false
        if (!drawFbo.bindTexture2d(target.texture, GLES30.GL_DRAW_FRAMEBUFFER)) return false

        val count = layer.grid.keysFor(rect, keys)
        try {
            for (index in 0 until count) {
                val key = TileKey(keys[index])
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
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        }
        return true
    }

    private fun backupWet(layer: LayerTextures, rect: IntRect): Boolean {
        val backup = wetBackup ?: return false
        val count = wetGrid.keysFor(rect, wetKeys)
        for (index in 0 until count) {
            val key = TileKey(wetKeys[index])
            if (key in backedWetKeys) continue

            val source = layer.slice(key)
            if (source.isNone) {
                absentWetKeys += key
                backedWetKeys += key
                continue
            }
            if (!copyWetKey(layer, source.page, source.slice, backup, key)) return false
            backedWetKeys += key
        }
        return true
    }

    private fun restoreWetKey(source: LayerTextures, target: LayerTextures, key: TileKey) {
        val slice = source.slice(key)
        if (slice.isNone) return
        copyWetKey(source, slice.page, slice.slice, target, key)
    }

    private fun copyWetKey(
        source: LayerTextures,
        sourcePage: Int,
        sourceSlice: Int,
        target: LayerTextures,
        key: TileKey,
    ): Boolean {
        if (!backupScratch.ensure(TILE_SIZE, TILE_SIZE, state)) return false
        try {
            if (!readFbo.bindArrayLayer(
                    source.pageTexture(sourcePage),
                    sourceSlice,
                    GLES30.GL_READ_FRAMEBUFFER,
                )
            ) {
                return false
            }
            if (!drawFbo.bindTexture2d(backupScratch.texture, GLES30.GL_DRAW_FRAMEBUFFER)) {
                return false
            }
            GLES30.glBlitFramebuffer(
                0, 0, TILE_SIZE, TILE_SIZE,
                0, 0, TILE_SIZE, TILE_SIZE,
                GLES30.GL_COLOR_BUFFER_BIT,
                GLES30.GL_NEAREST,
            )

            val targetSlice = try {
                target.sliceForWrite(key)
            } catch (_: PoolExhausted) {
                return false
            }
            if (!readFbo.bindTexture2d(backupScratch.texture, GLES30.GL_READ_FRAMEBUFFER)) {
                return false
            }
            if (!drawFbo.bindArrayLayer(
                    target.pageTexture(targetSlice.page),
                    targetSlice.slice,
                    GLES30.GL_DRAW_FRAMEBUFFER,
                )
            ) {
                return false
            }
            GLES30.glBlitFramebuffer(
                0, 0, TILE_SIZE, TILE_SIZE,
                0, 0, TILE_SIZE, TILE_SIZE,
                GLES30.GL_COLOR_BUFFER_BIT,
                GLES30.GL_NEAREST,
            )
            return true
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        }
    }

    private fun clear(target: OffscreenTarget): Boolean {
        if (!drawFbo.bindTexture2d(target.texture)) return false
        state.scissorOff()
        drawFbo.clear(0f, 0f, 0f, 0f)
        return true
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

    private fun ensureWetKeyCapacity() {
        if (wetKeys.size < wetGrid.tileCount) wetKeys = IntArray(wetGrid.tileCount)
    }

    private fun ensureColorKeyCapacity(grid: TileGrid) {
        if (colorKeys.size < grid.tileCount) colorKeys = IntArray(grid.tileCount)
        if (outputKeys.size < grid.tileCount) outputKeys = IntArray(grid.tileCount)
    }

    private fun resetStroke() {
        wetBackup?.release()
        wetBackup = null
        backedWetKeys.clear()
        absentWetKeys.clear()
        activeLayer = null
    }

    private val RmwSpec.isWatercolor: Boolean
        get() = this is RmwSpec.Watercolor || this is RmwSpec.Water

    private val RmwSpec.watercolorBehavior: WatercolorBehavior?
        get() = when (this) {
            is RmwSpec.Watercolor -> behavior
            is RmwSpec.Water -> behavior
            else -> null
        }

    private val RmwSpec.watercolorMixing: RmwMixing?
        get() = when (this) {
            is RmwSpec.Watercolor -> mixing
            is RmwSpec.Water -> mixing
            else -> null
        }

    private companion object {
        const val BEFORE_UNIT = 0
        const val WET_UNIT = 1
        const val MIXBOX_LUT_UNIT = 2
        const val CLEAR_WATER = 0
        const val PIGMENT_DEPOSIT = 1
    }
}
