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
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var ok = true
        // Written out rather than looped over pairs, for the reason
        // ScreenTransform.screenBoundsOf records: this runs on the front-buffer
        // path, once per input batch, where §2.4 allows no allocation.
        fun corner(cx: Float, cy: Float) {
            val bx = transform[0] * cx + transform[4] * cy + transform[12]
            val by = transform[1] * cx + transform[5] * cy + transform[13]
            if (!bx.isFinite() || !by.isFinite()) {
                ok = false
                return
            }
            minX = min(minX, bx); maxX = max(maxX, bx)
            minY = min(minY, by); maxY = max(maxY, by)
        }
        corner(x0, y0)
        corner(x1, y0)
        corner(x1, y1)
        corner(x0, y1)
        if (!ok) return IntRect.EMPTY
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
        out[0] = rect.left
        out[1] = bufferHeight - rect.bottom
        out[2] = rect.right - rect.left
        out[3] = rect.bottom - rect.top
    }

    private const val MATRIX_SIZE = 16
}
