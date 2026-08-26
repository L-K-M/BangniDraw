package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.PoolExhausted
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.TileKey

/**
 * Folds a finished stroke buffer into its layer, once, on pen-up
 * (`docs/plan/03-canvas-engine.md` §7.4).
 *
 * **The pass ping-pongs, and it has to.** The shader reads the layer tile `L`
 * and writes the layer tile, which ES 3.0 leaves undefined: rendering is
 * undefined when a fragment shader samples the texture *object* bound as the
 * draw attachment. So each key renders into a **scratch slice** taken with
 * `allocateNotOn(pageOf(L), pageOf(S))` — §2.1's rule that a pass never
 * renders into a slice of a page it samples — and the layer then adopts the
 * scratch by swapping handles. No copy, and the slice the layer gave up
 * becomes the next key's scratch candidate, re-checked against the same rule.
 *
 * The arithmetic is `StrokeMerge`'s, in `engine/core`, and `merge.glsl` is the
 * GLSL transcription of it; `StrokeShaderContractTest` holds the two together
 * and `StrokeMergeTest` cross-checks the CPU side against PR 2.1's
 * `Composite`. Nothing about *what* a merge computes is decided here.
 *
 * GL-thread-only.
 */
class MergePass(
    private val program: GlProgram,
    private val state: GlState,
    private val pool: TilePool,
    private val quad: FullRectQuad,
) {

    private val fbo = GlFbo()

    /** The two pages the shader samples, which the scratch slice must avoid. */
    private val excluded = IntArray(2)

    /**
     * Merges every key [buffer] touched into [layer] and returns how many
     * tiles changed — what `Readback` then reads out.
     *
     * [keys] is the caller's scratch list, reused across strokes.
     *
     * Leaves [buffer] untouched: freeing it is `StrokeBuffer.reset`'s job, and
     * the caller does it after the readback has been enqueued (§10.1), not
     * here, because the buffer's slices must outlive this call only until the
     * GPU has consumed them.
     */
    fun merge(
        layer: LayerTextures,
        buffer: StrokeBuffer,
        spec: StrokeSpec,
        keys: MutableList<TileKey>,
    ): Int {
        if (buffer.isEmpty) return 0
        keys.clear()
        buffer.keys(keys)
        if (keys.isEmpty()) return 0

        state.useProgram(program)
        program.uniform1i("u_strokeMode", spec.mode.ordinal)
        program.uniform1f("u_strokeOpacity", spec.opacity)
        program.uniform1f("u_dilution", spec.dilution)
        program.uniform1i("u_alphaLock", if (spec.alphaLock) 1 else 0)
        program.uniform1i("u_layerPage", LAYER_UNIT)
        program.uniform1i("u_strokePage", STROKE_UNIT)
        // The merge writes a finished pixel; the shader has already done the
        // compositing. Hardware blending on top of it would composite twice.
        state.blendOff()
        state.viewport(0, 0, TILE_SIZE, TILE_SIZE)
        state.scissorOff()

        var merged = 0
        for (key in keys) {
            val strokeSlice = buffer.slice(key)
            if (strokeSlice.isNone) continue
            // The layer tile may not exist yet — a stroke on empty canvas —
            // and §7.4 says it is created. `sliceForWrite` clears it, so the
            // shader reads transparent rather than a recycled slice's paint.
            val layerSlice = try {
                layer.sliceForWrite(key)
            } catch (_: PoolExhausted) {
                continue
            }

            excluded[0] = layerSlice.page
            excluded[1] = strokeSlice.page
            val scratch = try {
                pool.allocateNotOn(excluded)
            } catch (_: PoolExhausted) {
                // Nothing has been written yet for this key, so skipping it
                // leaves the layer exactly as it was. The stroke loses a tile
                // rather than the painting losing a layer.
                continue
            }

            if (!fbo.bindArrayLayer(pool.textureOf(scratch.page), scratch.slice)) {
                // The scratch was never drawn into, so it holds nothing and
                // must go back — otherwise every failed bind leaks a slice.
                pool.free(scratch)
                continue
            }

            bindPage(LAYER_UNIT, layer.pageTexture(layerSlice.page))
            bindPage(STROKE_UNIT, buffer.pageTexture(strokeSlice.page))
            program.uniform1f("u_layerSlice", layerSlice.slice.toFloat())
            program.uniform1f("u_strokeSlice", strokeSlice.slice.toFloat())
            quad.draw(TILE_SIZE.toFloat(), TILE_SIZE.toFloat())

            // The layer adopts the scratch and frees what it held. `swap` does
            // the freeing, which is why the old handle is not touched here.
            layer.swap(key, scratch)
            merged++
        }
        return merged
    }

    private fun bindPage(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texture)
    }

    fun release() = fbo.release()

    private companion object {
        const val LAYER_UNIT = 0
        const val STROKE_UNIT = 1
    }
}
