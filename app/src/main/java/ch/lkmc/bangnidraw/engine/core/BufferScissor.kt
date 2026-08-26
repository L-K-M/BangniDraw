package ch.lkmc.bangnidraw.engine.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A window-px rect carried into buffer px through graphics-core's buffer
 * transform (`docs/plan/03-canvas-engine.md` §8.1 step 3, §8.5).
 *
 * §8.1 builds the front frame's scissor in three moves: the dirty rect in
 * canvas px goes through `ScreenTransform` to window px — which
 * [ScreenTransform.screenBoundsOf] already does, inflation and viewport clip
 * included — and then through `transform` to buffer px, which is this. The
 * split is real and not bureaucratic: the compositor may hand back a
 * **pre-rotated** buffer whose width and height are swapped relative to the
 * viewport, and a scissor computed in window px and applied to that buffer
 * clips the wrong band of the screen. On a portrait phone held in landscape
 * that is the difference between a stroke appearing and a stroke appearing
 * somewhere else.
 *
 * §15's rule puts it here rather than in the renderer: which pixels a frame is
 * allowed to touch is decision-shaped, and it is the kind of decision that
 * fails silently — a wrong scissor draws a *plausible* frame, just not in the
 * right place, and only on the devices that pre-rotate.
 */
object BufferScissor {

    /**
     * The buffer-px bounding box of a window-px [rect] under [transform],
     * clipped to `[0, bufferWidth) × [0, bufferHeight)`.
     *
     * [transform] is graphics-core's 16-float **column-major** 4×4, applied in
     * pixel space before the projection (§3.1's `projection × transform ×
     * pixelPos`), matching [ch.lkmc.bangnidraw.engine.gl.Mat4]'s layout.
     *
     * **All four corners, like [ScreenTransform.screenBoundsOf].** The
     * transform is a rotation by a multiple of 90° in practice, where two
     * opposite corners would happen to suffice — but "in practice" is a
     * property of today's compositors, not of the contract, and the bounding
     * box of four corners is exact for any affine transform at the cost of two
     * more multiplies per frame.
     *
     * No extra inflation: `screenBoundsOf` already added its pixel, and a
     * rotation by a right angle maps that inflated rect exactly.
     */
    fun bounds(
        rect: IntRect,
        transform: FloatArray,
        bufferWidth: Int,
        bufferHeight: Int,
    ): IntRect {
        if (rect.isEmpty || bufferWidth <= 0 || bufferHeight <= 0) return IntRect.EMPTY
        require(transform.size >= MATRIX_SIZE) {
            "a buffer transform needs $MATRIX_SIZE floats, was ${transform.size}"
        }
        val x0 = rect.left.toFloat()
        val y0 = rect.top.toFloat()
        val x1 = rect.right.toFloat()
        val y1 = rect.bottom.toFloat()
        // Plain locals, and no helper function. A Kotlin local `fun` that
        // captures mutable vars compiles to Ref wrappers — `FloatRef` per
        // accumulator, `BooleanRef` for the flag — so the tidy-looking version
        // of this allocated about seven objects per call, once per input batch,
        // on the front-buffer path whose no-allocation rule its own comment
        // cited. Verified from the compiled class, not assumed.
        val bx00 = transform[0] * x0 + transform[4] * y0 + transform[12]
        val by00 = transform[1] * x0 + transform[5] * y0 + transform[13]
        val bx10 = transform[0] * x1 + transform[4] * y0 + transform[12]
        val by10 = transform[1] * x1 + transform[5] * y0 + transform[13]
        val bx11 = transform[0] * x1 + transform[4] * y1 + transform[12]
        val by11 = transform[1] * x1 + transform[5] * y1 + transform[13]
        val bx01 = transform[0] * x0 + transform[4] * y1 + transform[12]
        val by01 = transform[1] * x0 + transform[5] * y1 + transform[13]
        if (!bx00.isFinite() || !by00.isFinite() || !bx10.isFinite() || !by10.isFinite() ||
            !bx11.isFinite() || !by11.isFinite() || !bx01.isFinite() || !by01.isFinite()
        ) {
            return IntRect.EMPTY
        }
        val minX = min(min(bx00, bx10), min(bx11, bx01))
        val minY = min(min(by00, by10), min(by11, by01))
        val maxX = max(max(bx00, bx10), max(bx11, bx01))
        val maxY = max(max(by00, by10), max(by11, by01))
        val left = floor(minX).toInt().coerceIn(0, bufferWidth)
        val top = floor(minY).toInt().coerceIn(0, bufferHeight)
        val right = ceil(maxX).toInt().coerceIn(0, bufferWidth)
        val bottom = ceil(maxY).toInt().coerceIn(0, bufferHeight)
        return if (left >= right || top >= bottom) IntRect.EMPTY else IntRect(left, top, right, bottom)
    }

    /**
     * The scissor box GL wants: `(x, y, width, height)` with **y measured from
     * the bottom**, written into [out].
     *
     * `glScissor`'s origin is the lower-left of the framebuffer, while every
     * rect in this engine is y-down from the top (§3.1). Converting here, next
     * to the transform that produced the rect, keeps the flip in one place —
     * the alternative is a `bufferHeight - bottom` at each call site, which is
     * the shape of an off-by-one nobody notices until a stroke's top row goes
     * missing.
     */
    fun toGlScissor(rect: IntRect, bufferHeight: Int, out: IntArray) {
        require(out.size >= 4) { "a scissor needs 4 ints, was ${out.size}" }
        // Unclipped input would make `bufferHeight - rect.bottom` negative, and
        // GL answers a negative scissor with GL_INVALID_VALUE and *keeps the
        // previous box* — so the draw would silently touch pixels outside the
        // dirty region instead of failing. The pairing with [bounds], which
        // clips, is what makes that unreachable; this says so out loud.
        require(rect.top >= 0 && rect.bottom <= bufferHeight) {
            "toGlScissor needs a rect already clipped to the buffer, was " +
                "top=${rect.top} bottom=${rect.bottom} height=$bufferHeight"
        }
        out[0] = rect.left
        out[1] = bufferHeight - rect.bottom
        out[2] = rect.right - rect.left
        out[3] = rect.bottom - rect.top
    }

    private const val MATRIX_SIZE = 16
}
