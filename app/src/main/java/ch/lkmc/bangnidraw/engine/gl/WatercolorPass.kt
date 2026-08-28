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
import ch.lkmc.bangnidraw.engine.core.RmwTouchTracker
import ch.lkmc.bangnidraw.engine.core.ScreenTransform
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.WatercolorBehavior
import ch.lkmc.bangnidraw.engine.core.WatercolorDabBounds
import ch.lkmc.bangnidraw.engine.core.WatercolorKernel
import ch.lkmc.bangnidraw.engine.core.WatercolorOverlayKernel

/** Ordered color and coarse wet-state updates for watercolor gestures. */
internal class WatercolorPass(
    canvas: CanvasSize,
    private val pool: TilePool,
    private val state: GlState,
    private val wetProgram: GlProgram,
    private val colorProgram: GlProgram,
    private val overlayPass: WatercolorOverlayPass,
    private val colorMixProgram: GlProgram? = null,
    private val mixboxLut: Int = 0,
) {

    private val wetGrid = TileGrid(
        WatercolorKernel.wetPixels(canvas.width),
        WatercolorKernel.wetPixels(canvas.height),
    )
    private val wetBounds = IntRect(0, 0, wetGrid.width, wetGrid.height)
    private val wetLayers = LinkedHashMap<LayerId, WetLayer>()
    private val dabBounds = WatercolorDabBounds(TileGrid(canvas.width, canvas.height))

    private val colorBefore = OffscreenTarget("Watercolor color before")
    private val wetBefore = OffscreenTarget("Watercolor wet before")
    private val wetAfter = OffscreenTarget("Watercolor wet after")
    private val backupScratch = OffscreenTarget("Watercolor wet copy scratch")
    private val readFbo = GlFbo()
    private val drawFbo = GlFbo()
    private val quad = FullRectQuad()

    /** Retained high-water capacity for renderer memory diagnostics. */
    internal val scratchBytes: Long
        get() = colorBefore.bytes + wetBefore.bytes + wetAfter.bytes + backupScratch.bytes

    private var colorKeys = IntArray(0)
    private var outputKeys = IntArray(0)
    private var wetKeys = IntArray(0)
    private var rebaseKeys = IntArray(0)
    private var freshColorKeys = IntArray(0)
    private var freshWetKeys = IntArray(0)
    private var reservedColorCount = 0
    private var reservedWetCount = 0

    private var activeLayer: LayerId? = null
    private var lastTickEpoch: Long? = null
    private var wetBackup: LayerTextures? = null
    private val backedWetTimes = LongArray(wetGrid.tileCount)
    // Dense state plus packed keys keep per-dab backup checks allocation-free.
    private val backedWetStates = ByteArray(wetGrid.tileCount)
    private val backedWetKeys = IntArray(wetGrid.tileCount)
    private var backedWetKeyCount = 0

    fun begin(layer: LayerId, spec: RmwSpec, nowNanos: Long): Boolean {
        if (!spec.isWatercolor) return false

        finish()
        resetDryEpoch(nowNanos)
        activeLayer = layer
        wetLayers.getOrPut(layer) {
            WetLayer(LayerTextures(wetGrid, pool), LongArray(wetGrid.tileCount))
        }
        wetBackup = LayerTextures(wetGrid, pool)
        ensureWetKeyCapacity()
        return true
    }

    fun stamp(
        batch: DabBatch,
        layer: LayerTextures,
        stroke: StrokeSpec,
        nowNanos: Long,
        tracker: RmwTouchTracker,
        onFirstTouch: (IntArray, Int) -> Unit,
    ): IntRect {
        val spec = stroke.rmw ?: return IntRect.EMPTY
        val behavior = spec.watercolorBehavior ?: return IntRect.EMPTY
        val layerId = activeLayer ?: return IntRect.EMPTY
        resetActiveEpoch(layerId, nowNanos)
        val wetLayer = wetLayers.getOrPut(layerId) {
            WetLayer(LayerTextures(wetGrid, pool), LongArray(wetGrid.tileCount))
        }
        val nowTick = WatercolorKernel.tickAt(nowNanos)
        val committed = batch.committedCount
        if (committed == 0) return IntRect.EMPTY
        ensureColorKeyCapacity(layer.grid)

        var hasDirty = false
        var dirtyLeft = layer.grid.width
        var dirtyTop = layer.grid.height
        var dirtyRight = 0
        var dirtyBottom = 0
        for (index in 0 until committed) {
            if (batch.flow[index] <= 0f) continue
            if (spec is RmwSpec.Water && behavior.waterLoad * batch.flow[index] <= 0f) continue
            if (!dabBounds.set(
                    batch.x[index],
                    batch.y[index],
                    batch.radius[index],
                    behavior.spread,
                )
            ) {
                continue
            }

            val affectsColor = spec is RmwSpec.Watercolor || layer.hasColorContent(
                dabBounds.sourceLeft,
                dabBounds.sourceTop,
                dabBounds.sourceRight,
                dabBounds.sourceBottom,
                colorKeys,
            )
            if (affectsColor && !copyColorSource(layer)) continue
            if (!copyWetSource(wetLayer.textures)) continue
            if (!renderWet(batch, index, behavior, nowTick)) continue
            if (!backupWet(wetLayer)) continue
            val colorOutput = if (affectsColor) ColorOutput.WRITE else ColorOutput.SKIP
            if (!reserveDabOutput(layer, wetLayer, colorOutput)) continue
            val colorReady = !affectsColor || canBindColorOutput(layer)
            val wetReady = canBindWetOutput(wetLayer.textures)
            if (!colorReady || !wetReady) {
                rollbackDabReservation(layer, wetLayer.textures)
                continue
            }

            if (affectsColor) {
                val added = tracker.add(
                    dabBounds.outputLeft,
                    dabBounds.outputTop,
                    dabBounds.outputRight,
                    dabBounds.outputBottom,
                    outputKeys,
                )
                if (added > 0) onFirstTouch(outputKeys, added)
                drawColor(layer, batch, index, stroke, behavior)
                dirtyLeft = minOf(dirtyLeft, dabBounds.outputLeft)
                dirtyTop = minOf(dirtyTop, dabBounds.outputTop)
                dirtyRight = maxOf(dirtyRight, dabBounds.outputRight)
                dirtyBottom = maxOf(dirtyBottom, dabBounds.outputBottom)
                hasDirty = true
            }
            // Wet-only dabs damage presentation without entering color history.
            val wroteWet = writeWet(wetLayer, nowNanos)
            if (wroteWet) {
                val wetDirty = IntRect(
                    left = dabBounds.wetOutputLeft * WatercolorKernel.CELL_SIZE,
                    top = dabBounds.wetOutputTop * WatercolorKernel.CELL_SIZE,
                    right = minOf(
                        layer.grid.width,
                        dabBounds.wetOutputRight * WatercolorKernel.CELL_SIZE,
                    ),
                    bottom = minOf(
                        layer.grid.height,
                        dabBounds.wetOutputBottom * WatercolorKernel.CELL_SIZE,
                    ),
                )
                wetLayer.overlayDirty = wetLayer.overlayDirty.union(wetDirty)
                dirtyLeft = minOf(dirtyLeft, wetDirty.left)
                dirtyTop = minOf(dirtyTop, wetDirty.top)
                dirtyRight = maxOf(dirtyRight, wetDirty.right)
                dirtyBottom = maxOf(dirtyBottom, wetDirty.bottom)
                hasDirty = true
            }
            commitDabReservation()
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        state.scissorOff()
        if (!hasDirty) return IntRect.EMPTY

        return IntRect(dirtyLeft, dirtyTop, dirtyRight, dirtyBottom)
    }

    /** Keeps pre-gesture wetness while dropping newly added water. */
    fun cancel() {
        val layerId = activeLayer ?: return
        val wetLayer = wetLayers[layerId] ?: return resetStroke()
        val backup = wetBackup ?: return resetStroke()

        for (index in 0 until backedWetKeyCount) {
            val key = TileKey(backedWetKeys[index])
            val keyIndex = wetGrid.index(key)
            if (backedWetStates[keyIndex] == WetBackupState.ABSENT.code) {
                if (!wetLayer.textures.slice(key).isNone) wetLayer.textures.remove(key)
                wetLayer.updatedAtNanos[keyIndex] = 0L
                continue
            }

            if (restoreWetKey(backup, wetLayer.textures, key)) {
                wetLayer.updatedAtNanos[keyIndex] = backedWetTimes[keyIndex]
            } else {
                // A restore that cannot run must not leave the gesture's
                // water behind: drop the page, exactly like an absent one.
                if (!wetLayer.textures.slice(key).isNone) wetLayer.textures.remove(key)
                wetLayer.updatedAtNanos[keyIndex] = 0L
            }
        }
        if (wetLayer.textures.tileCount == 0) wetLayer.overlayDirty = IntRect.EMPTY
        resetStroke()
    }

    fun finish() = resetStroke()

    internal fun drawOverlay(
        layer: LayerId,
        screen: ScreenTransform,
        projection: FloatArray,
        bufferTransform: FloatArray,
        canvasDirty: IntRect,
        nowNanos: Long,
        opacity: Float,
    ): Int {
        val wet = wetLayers[layer] ?: return 0

        return overlayPass.draw(
            textures = wet.textures,
            screen = screen,
            projection = projection,
            bufferTransform = bufferTransform,
            canvasDirty = canvasDirty,
            nowTick = WatercolorKernel.tickAt(nowNanos),
            opacity = opacity,
        )
    }

    internal fun refreshOverlay(
        visibleLayer: LayerId?,
        nowNanos: Long,
    ): WatercolorOverlayKernel.RefreshResult {
        val visibleWetLayer = visibleLayer?.let { wetLayers[it] }
        val visibleDirty = visibleWetLayer?.overlayDirty ?: IntRect.EMPTY
        val before = wetTileCount()
        pruneAndRebase(nowNanos)
        if (visibleWetLayer?.textures?.tileCount == 0) {
            visibleWetLayer.overlayDirty = IntRect.EMPTY
        }

        return WatercolorOverlayKernel.RefreshResult(
            action = WatercolorOverlayKernel.refresh(before, wetTileCount()),
            dirty = visibleDirty,
        )
    }

    internal fun hasWetOverlay(): Boolean = wetTileCount() > 0

    private fun wetTileCount(): Int {
        var count = 0
        for (layer in wetLayers.values) count += layer.textures.tileCount

        return count
    }

    private fun pruneAndRebase(nowNanos: Long) {
        resetDryEpoch(nowNanos)
        pruneExpired(nowNanos)
    }

    fun removeLayer(layer: LayerId) {
        if (activeLayer == layer) resetStroke()
        wetLayers.remove(layer)?.textures?.release()
    }

    /** History and reopen operate on dry pigment; wetness is transient. */
    fun dryAll() {
        resetStroke()
        wetLayers.values.forEach { it.textures.release() }
        wetLayers.clear()
        lastTickEpoch = null
    }

    fun forgetAll() {
        wetBackup?.forgetAll()
        wetLayers.values.forEach { it.textures.forgetAll() }
        clearWetBackupRecords()
        wetLayers.clear()
        wetBackup = null
        activeLayer = null
        lastTickEpoch = null
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
        overlayPass.release()
    }

    private fun renderWet(
        batch: DabBatch,
        index: Int,
        behavior: WatercolorBehavior,
        nowTick: Int,
    ): Boolean {
        val width = dabBounds.wetSourceRight - dabBounds.wetSourceLeft
        val height = dabBounds.wetSourceBottom - dabBounds.wetSourceTop
        if (!wetAfter.ensureCapacity(width, height, state)) return false
        if (!drawFbo.bindTexture2d(wetAfter.texture)) return false

        state.useProgram(wetProgram)
        state.viewport(0, 0, wetAfter.width, wetAfter.height)
        state.scissorOff()
        state.blendOff()
        state.ditherOff()
        wetProgram.uniform1i("u_before", BEFORE_UNIT)
        bindWetUpdateMode(WetUpdateMode.UPDATE)
        wetProgram.uniform2f(
            "u_wetOrigin",
            dabBounds.wetSourceLeft.toFloat(),
            dabBounds.wetSourceTop.toFloat(),
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
        quad.draw(UNIT_QUAD_EDGE, UNIT_QUAD_EDGE)
        return true
    }

    private fun renderEpochRebase(rect: IntRect, nowTick: Int): Boolean {
        if (!wetAfter.ensureCapacity(rect.width, rect.height, state)) return false
        if (!drawFbo.bindTexture2d(wetAfter.texture)) return false

        state.useProgram(wetProgram)
        state.viewport(0, 0, wetAfter.width, wetAfter.height)
        state.scissorOff()
        state.blendOff()
        state.ditherOff()
        wetProgram.uniform1i("u_before", BEFORE_UNIT)
        bindWetUpdateMode(WetUpdateMode.EPOCH_REBASE)
        wetProgram.uniform2f("u_wetOrigin", rect.left.toFloat(), rect.top.toFloat())
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
        wetProgram.uniform1f("u_nowTick", nowTick.toFloat())
        bindTexture(BEFORE_UNIT, wetBefore.texture)
        quad.draw(UNIT_QUAD_EDGE, UNIT_QUAD_EDGE)
        return true
    }

    private fun bindWetUpdateMode(mode: WetUpdateMode) {
        wetProgram.uniform1i("u_ageOnly", mode.ageOnlyUniform)
        wetProgram.uniform1i("u_epochRollover", mode.epochRolloverUniform)
    }

    private fun drawColor(
        layer: LayerTextures,
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
        bindColorSampling(active)
        bindDab(active, batch, index)
        active.uniform3f("u_color", strokeR, strokeG, strokeB)
        active.uniform1f("u_strength", batch.flow[index])
        active.uniform1f("u_spread", behavior.spread)
        active.uniform1f("u_granulation", behavior.granulation)
        active.uniform1f("u_edgeDarkening", behavior.edgeDarkening)
        active.uniform1f("u_dilution", stroke.dilution)
        active.uniform1i(
            "u_depositMode",
            if (rmw is RmwSpec.Watercolor) PIGMENT_DEPOSIT else CLEAR_WATER,
        )
        active.uniform1i("u_alphaLock", if (stroke.alphaLock) 1 else 0)
        bindTexture(BEFORE_UNIT, colorBefore.texture)
        bindTexture(WET_UNIT, wetAfter.texture)
        if (active === colorMixProgram) {
            active.uniform1i("mixbox_lut", MIXBOX_LUT_UNIT)
            bindTexture(MIXBOX_LUT_UNIT, mixboxLut)
        }

        drawOutputTiles(layer, active)
    }

    private var strokeR = 0f
    private var strokeG = 0f
    private var strokeB = 0f

    fun setColor(red: Float, green: Float, blue: Float) {
        strokeR = red
        strokeG = green
        strokeB = blue
    }

    private fun bindColorSampling(program: GlProgram) {
        program.uniform2f(
            "u_scratchOrigin",
            dabBounds.sourceLeft.toFloat(),
            dabBounds.sourceTop.toFloat(),
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
            dabBounds.wetSourceLeft.toFloat(),
            dabBounds.wetSourceTop.toFloat(),
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

    /** Pool exhaustion is resolved before either persistent target changes. */
    private fun reserveDabOutput(
        color: LayerTextures,
        wet: WetLayer,
        colorOutput: ColorOutput,
    ): Boolean {
        reservedColorCount = 0
        reservedWetCount = reserveTiles(
            wet.textures,
            dabBounds.wetOutputLeft,
            dabBounds.wetOutputTop,
            dabBounds.wetOutputRight,
            dabBounds.wetOutputBottom,
            wetKeys,
            freshWetKeys,
        )
        if (reservedWetCount == RESERVATION_FAILED) {
            reservedWetCount = 0
            return false
        }
        if (colorOutput == ColorOutput.SKIP) return true

        reservedColorCount = reserveTiles(
            color,
            dabBounds.outputLeft,
            dabBounds.outputTop,
            dabBounds.outputRight,
            dabBounds.outputBottom,
            outputKeys,
            freshColorKeys,
        )
        if (reservedColorCount != RESERVATION_FAILED) return true

        reservedColorCount = 0
        rollbackTiles(wet.textures, freshWetKeys, reservedWetCount)
        reservedWetCount = 0
        return false
    }

    private fun reserveTiles(
        layer: LayerTextures,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        keys: IntArray,
        freshKeys: IntArray,
    ): Int {
        val count = layer.grid.keysForBounds(left, top, right, bottom, keys)
        var freshCount = 0
        for (index in 0 until count) {
            val key = TileKey(keys[index])
            if (!layer.slice(key).isNone) continue

            try {
                layer.sliceForWrite(key)
            } catch (_: PoolExhausted) {
                rollbackTiles(layer, freshKeys, freshCount)
                return RESERVATION_FAILED
            }
            freshKeys[freshCount++] = key.packed
        }

        return freshCount
    }

    private fun canBindColorOutput(layer: LayerTextures): Boolean = canBindOutput(
        layer,
        dabBounds.outputLeft,
        dabBounds.outputTop,
        dabBounds.outputRight,
        dabBounds.outputBottom,
        outputKeys,
    )

    private fun canBindWetOutput(layer: LayerTextures): Boolean = canBindOutput(
        layer,
        dabBounds.wetOutputLeft,
        dabBounds.wetOutputTop,
        dabBounds.wetOutputRight,
        dabBounds.wetOutputBottom,
        wetKeys,
    )

    private fun canBindOutput(
        layer: LayerTextures,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        keys: IntArray,
    ): Boolean {
        val count = layer.grid.keysForBounds(left, top, right, bottom, keys)
        for (index in 0 until count) {
            val slice = layer.slice(TileKey(keys[index]))
            if (slice.isNone) return false
            if (!drawFbo.bindArrayLayer(
                    layer.pageTexture(slice.page),
                    slice.slice,
                    GLES30.GL_DRAW_FRAMEBUFFER,
                )
            ) {
                return false
            }
        }

        return true
    }

    private fun rollbackDabReservation(color: LayerTextures, wet: LayerTextures) {
        rollbackTiles(color, freshColorKeys, reservedColorCount)
        rollbackTiles(wet, freshWetKeys, reservedWetCount)
        commitDabReservation()
    }

    private fun rollbackTiles(layer: LayerTextures, keys: IntArray, count: Int) {
        for (index in 0 until count) layer.remove(TileKey(keys[index]))
    }

    private fun commitDabReservation() {
        reservedColorCount = 0
        reservedWetCount = 0
    }

    private fun drawOutputTiles(layer: LayerTextures, program: GlProgram) {
        val count = layer.grid.keysForBounds(
            dabBounds.outputLeft,
            dabBounds.outputTop,
            dabBounds.outputRight,
            dabBounds.outputBottom,
            colorKeys,
        )
        for (index in 0 until count) {
            val key = TileKey(colorKeys[index])
            val slice = try {
                layer.sliceForWrite(key)
            } catch (_: PoolExhausted) {
                continue
            }
            if (!drawFbo.bindArrayLayer(layer.pageTexture(slice.page), slice.slice)) continue

            val tileLeft = key.tx * TILE_SIZE
            val tileTop = key.ty * TILE_SIZE
            val left = maxOf(dabBounds.outputLeft, tileLeft)
            val top = maxOf(dabBounds.outputTop, tileTop)
            val right = minOf(dabBounds.outputRight, tileLeft + TILE_SIZE)
            val bottom = minOf(dabBounds.outputBottom, tileTop + TILE_SIZE)
            state.viewport(0, 0, TILE_SIZE, TILE_SIZE)
            state.scissor(left - tileLeft, top - tileTop, right - left, bottom - top)
            state.blendOff()
            program.uniform2f("u_tileOrigin", tileLeft.toFloat(), tileTop.toFloat())
            quad.draw(UNIT_QUAD_EDGE, UNIT_QUAD_EDGE)
            layer.markColorWritten(key)
        }
    }

    private fun writeWet(layer: WetLayer, nowNanos: Long): Boolean {
        state.scissorOff()
        var wroteAny = false
        val count = wetGrid.keysForBounds(
            dabBounds.wetOutputLeft,
            dabBounds.wetOutputTop,
            dabBounds.wetOutputRight,
            dabBounds.wetOutputBottom,
            wetKeys,
        )
        try {
            for (index in 0 until count) {
                val key = TileKey(wetKeys[index])
                val slice = try {
                    layer.textures.sliceForWrite(key)
                } catch (_: PoolExhausted) {
                    continue
                }
                if (!readFbo.bindTexture2d(wetAfter.texture, GLES30.GL_READ_FRAMEBUFFER)) {
                    continue
                }
                if (!drawFbo.bindArrayLayer(
                        layer.textures.pageTexture(slice.page),
                        slice.slice,
                        GLES30.GL_DRAW_FRAMEBUFFER,
                    )
                ) {
                    continue
                }

                blitWetIntersection(
                    dabBounds.wetSourceLeft,
                    dabBounds.wetSourceTop,
                    dabBounds.wetOutputLeft,
                    dabBounds.wetOutputTop,
                    dabBounds.wetOutputRight,
                    dabBounds.wetOutputBottom,
                    key,
                )
                layer.updatedAtNanos[wetGrid.index(key)] = nowNanos
                wroteAny = true
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        }

        return wroteAny
    }

    private fun blitWetIntersection(
        sourceLeft: Int,
        sourceTop: Int,
        outputLeft: Int,
        outputTop: Int,
        outputRight: Int,
        outputBottom: Int,
        key: TileKey,
    ) {
        val tileLeft = key.tx * TILE_SIZE
        val tileTop = key.ty * TILE_SIZE
        val tileRight = minOf(tileLeft + TILE_SIZE, wetGrid.width)
        val tileBottom = minOf(tileTop + TILE_SIZE, wetGrid.height)
        val left = maxOf(outputLeft, tileLeft)
        val top = maxOf(outputTop, tileTop)
        val right = minOf(outputRight, tileRight)
        val bottom = minOf(outputBottom, tileBottom)
        GLES30.glBlitFramebuffer(
            left - sourceLeft,
            top - sourceTop,
            right - sourceLeft,
            bottom - sourceTop,
            left - tileLeft,
            top - tileTop,
            right - tileLeft,
            bottom - tileTop,
            GLES30.GL_COLOR_BUFFER_BIT,
            GLES30.GL_NEAREST,
        )
    }

    private fun copyColorSource(layer: LayerTextures): Boolean = copyTilesBounds(
        layer,
        dabBounds.sourceLeft,
        dabBounds.sourceTop,
        dabBounds.sourceRight,
        dabBounds.sourceBottom,
        colorBefore,
        colorKeys,
    )

    private fun copyWetSource(layer: LayerTextures): Boolean = copyTilesBounds(
        layer,
        dabBounds.wetSourceLeft,
        dabBounds.wetSourceTop,
        dabBounds.wetSourceRight,
        dabBounds.wetSourceBottom,
        wetBefore,
        wetKeys,
    )

    private fun copyTiles(
        layer: LayerTextures,
        rect: IntRect,
        target: OffscreenTarget,
        keys: IntArray,
    ): Boolean = copyTilesBounds(
        layer,
        rect.left,
        rect.top,
        rect.right,
        rect.bottom,
        target,
        keys,
    )

    private fun copyTilesBounds(
        layer: LayerTextures,
        rectLeft: Int,
        rectTop: Int,
        rectRight: Int,
        rectBottom: Int,
        target: OffscreenTarget,
        keys: IntArray,
    ): Boolean {
        if (rectLeft >= rectRight || rectTop >= rectBottom) return false
        if (!target.ensureCapacity(rectRight - rectLeft, rectBottom - rectTop, state)) return false
        if (!clear(target)) return false
        if (!drawFbo.bindTexture2d(target.texture, GLES30.GL_DRAW_FRAMEBUFFER)) return false

        val count = layer.grid.keysForBounds(
            rectLeft,
            rectTop,
            rectRight,
            rectBottom,
            keys,
        )
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

                val tileLeft = key.tx * TILE_SIZE
                val tileTop = key.ty * TILE_SIZE
                val tileRight = minOf(tileLeft + TILE_SIZE, layer.grid.width)
                val tileBottom = minOf(tileTop + TILE_SIZE, layer.grid.height)
                val left = maxOf(rectLeft, tileLeft)
                val top = maxOf(rectTop, tileTop)
                val right = minOf(rectRight, tileRight)
                val bottom = minOf(rectBottom, tileBottom)
                GLES30.glBlitFramebuffer(
                    left - tileLeft,
                    top - tileTop,
                    right - tileLeft,
                    bottom - tileTop,
                    left - rectLeft,
                    top - rectTop,
                    right - rectLeft,
                    bottom - rectTop,
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

    private fun backupWet(layer: WetLayer): Boolean {
        val backup = wetBackup ?: return false
        val count = wetGrid.keysForBounds(
            dabBounds.wetOutputLeft,
            dabBounds.wetOutputTop,
            dabBounds.wetOutputRight,
            dabBounds.wetOutputBottom,
            wetKeys,
        )
        for (index in 0 until count) {
            val key = TileKey(wetKeys[index])
            val keyIndex = wetGrid.index(key)
            if (backedWetStates[keyIndex] != WetBackupState.UNSEEN.code) continue

            val source = layer.textures.slice(key)
            if (source.isNone) {
                recordWetBackup(key, keyIndex, WetBackupState.ABSENT)
                continue
            }
            if (!copyWetKey(layer.textures, source.page, source.slice, backup, key)) return false
            backedWetTimes[keyIndex] = layer.updatedAtNanos[keyIndex]
            recordWetBackup(key, keyIndex, WetBackupState.PRESENT)
        }
        return true
    }

    private fun restoreWetKey(
        source: LayerTextures,
        target: LayerTextures,
        key: TileKey,
    ): Boolean {
        val slice = source.slice(key)
        if (slice.isNone) return false

        return copyWetKey(source, slice.page, slice.slice, target, key)
    }

    private fun copyWetKey(
        source: LayerTextures,
        sourcePage: Int,
        sourceSlice: Int,
        target: LayerTextures,
        key: TileKey,
    ): Boolean {
        if (!backupScratch.ensure(TILE_SIZE, TILE_SIZE, state)) return false
        state.scissorOff()
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

    /** Re-encodes live cells once per 16-bit tick epoch without losing water. */
    private fun rebaseWetPages(nowNanos: Long) {
        ensureWetKeyCapacity()
        val nowTick = WatercolorKernel.tickAt(nowNanos)
        val count = wetGrid.keysFor(wetBounds, rebaseKeys)
        val layers = wetLayers.entries.iterator()
        while (layers.hasNext()) {
            val layer = layers.next().value
            for (index in 0 until count) {
                val key = TileKey(rebaseKeys[index])
                if (layer.textures.slice(key).isNone) continue
                if (rebaseWetKey(layer.textures, key, nowTick)) continue

                val keyIndex = wetGrid.index(key)
                layer.textures.remove(key)
                layer.updatedAtNanos[keyIndex] = 0L
            }
            if (layer.textures.tileCount == 0) layers.remove()
        }

        val backup = wetBackup ?: return
        for (index in 0 until backedWetKeyCount) {
            val key = TileKey(backedWetKeys[index])
            val keyIndex = wetGrid.index(key)
            if (backedWetStates[keyIndex] == WetBackupState.ABSENT.code) continue

            val expired = WatercolorKernel.isExpired(nowNanos, backedWetTimes[keyIndex])
            if (!expired && rebaseWetKey(backup, key, nowTick)) continue

            if (!backup.slice(key).isNone) backup.remove(key)
            backedWetStates[keyIndex] = WetBackupState.ABSENT.code
            backedWetTimes[keyIndex] = 0L
        }
    }

    private fun rebaseWetKey(layer: LayerTextures, key: TileKey, nowTick: Int): Boolean {
        val rect = wetGrid.tileRect(key)
        if (!copyTiles(layer, rect, wetBefore, wetKeys)) return false
        if (!renderEpochRebase(rect, nowTick)) return false

        val slice = layer.slice(key)
        if (slice.isNone) return false
        state.scissorOff()
        try {
            if (!readFbo.bindTexture2d(wetAfter.texture, GLES30.GL_READ_FRAMEBUFFER)) {
                return false
            }
            if (!drawFbo.bindArrayLayer(
                    layer.pageTexture(slice.page),
                    slice.slice,
                    GLES30.GL_DRAW_FRAMEBUFFER,
                )
            ) {
                return false
            }

            blitWetIntersection(
                rect.left,
                rect.top,
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                key,
            )
            return true
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        }
    }

    /** The presentation clock reclaims expired pages after retaining their damage. */
    private fun pruneExpired(nowNanos: Long) {
        ensureWetKeyCapacity()
        val count = wetGrid.keysFor(wetBounds, wetKeys)
        val layers = wetLayers.entries.iterator()
        while (layers.hasNext()) {
            val layer = layers.next().value
            for (index in 0 until count) {
                val key = TileKey(wetKeys[index])
                if (layer.textures.slice(key).isNone) continue

                val keyIndex = wetGrid.index(key)
                if (!WatercolorKernel.isExpired(nowNanos, layer.updatedAtNanos[keyIndex])) continue

                layer.textures.remove(key)
                layer.updatedAtNanos[keyIndex] = 0L
            }
            if (layer.textures.tileCount == 0) layers.remove()
        }
    }

    private fun clear(target: OffscreenTarget): Boolean {
        if (!drawFbo.bindTexture2d(target.texture)) return false
        state.scissor(0, 0, target.width, target.height)
        drawFbo.clear(0f, 0f, 0f, 0f)
        state.scissorOff()
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
        program.uniform2f("u_tip", batch.angle[index], batch.aspect[index])
    }

    private fun bindTexture(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    private fun ensureWetKeyCapacity() {
        if (wetKeys.size < wetGrid.tileCount) wetKeys = IntArray(wetGrid.tileCount)
        if (rebaseKeys.size < wetGrid.tileCount) rebaseKeys = IntArray(wetGrid.tileCount)
        if (freshWetKeys.size < wetGrid.tileCount) freshWetKeys = IntArray(wetGrid.tileCount)
    }

    private fun ensureColorKeyCapacity(grid: TileGrid) {
        if (colorKeys.size < grid.tileCount) colorKeys = IntArray(grid.tileCount)
        if (outputKeys.size < grid.tileCount) outputKeys = IntArray(grid.tileCount)
        if (freshColorKeys.size < grid.tileCount) freshColorKeys = IntArray(grid.tileCount)
    }

    private fun resetDryEpoch(nowNanos: Long) {
        val nextEpoch = WatercolorKernel.tickEpoch(nowNanos)
        val previousEpoch = lastTickEpoch
        if (previousEpoch == null) {
            lastTickEpoch = nextEpoch
            return
        }
        if (previousEpoch == nextEpoch) return

        rebaseWetPages(nowNanos)
        lastTickEpoch = nextEpoch
    }

    private fun resetActiveEpoch(layerId: LayerId, nowNanos: Long) {
        if (activeLayer != layerId) return

        resetDryEpoch(nowNanos)
    }

    private fun resetStroke() {
        wetBackup?.release()
        wetBackup = null
        clearWetBackupRecords()
        activeLayer = null
        commitDabReservation()
    }

    private fun recordWetBackup(key: TileKey, keyIndex: Int, status: WetBackupState) {
        backedWetStates[keyIndex] = status.code
        backedWetKeys[backedWetKeyCount++] = key.packed
    }

    private fun clearWetBackupRecords() {
        for (index in 0 until backedWetKeyCount) {
            val keyIndex = wetGrid.index(TileKey(backedWetKeys[index]))
            backedWetStates[keyIndex] = WetBackupState.UNSEEN.code
            backedWetTimes[keyIndex] = 0L
        }
        backedWetKeyCount = 0
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

    private class WetLayer(
        val textures: LayerTextures,
        val updatedAtNanos: LongArray,
        var overlayDirty: IntRect = IntRect.EMPTY,
    )

    private enum class WetUpdateMode(
        val ageOnlyUniform: Int,
        val epochRolloverUniform: Int,
    ) {
        UPDATE(GLES30.GL_FALSE, GLES30.GL_FALSE),
        EPOCH_REBASE(GLES30.GL_TRUE, GLES30.GL_TRUE),
    }

    private enum class ColorOutput {
        SKIP,
        WRITE,
    }

    private enum class WetBackupState(val code: Byte) {
        UNSEEN(0),
        ABSENT(1),
        PRESENT(2),
    }

    private companion object {
        const val BEFORE_UNIT = 0
        const val WET_UNIT = 1
        const val MIXBOX_LUT_UNIT = 2
        const val CLEAR_WATER = 0
        const val PIGMENT_DEPOSIT = 1
        const val RESERVATION_FAILED = -1
        const val UNIT_QUAD_EDGE = 1f
    }
}
