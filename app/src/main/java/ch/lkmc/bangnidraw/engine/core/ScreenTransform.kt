package ch.lkmc.bangnidraw.engine.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The composition `view ∘ fit` — what the screen actually shows of canvas
 * space (`docs/plan/03-canvas-engine.md` §3.1).
 *
 * [FitTransform] letterboxes the canvas into the viewport; [ViewTransform] is
 * the user's pan/zoom/rotate on top of it. Both are **similarities**, and
 * similarities compose, so the pair collapses to one similarity computed once
 * per frame and handed to the vertex shader as four floats:
 *
 * ```
 * fit(p)    = fs·p + o
 * view(q)   = [a −b; b a]·q + t
 * screen(p) = view(fit(p)) = [a' −b'; b' a']·p + t'
 *   a' = a·fs,  b' = b·fs,  t' = view.apply(o)
 * ```
 *
 * Collapsing them is not a micro-optimization: the shader gets one `vec4`
 * instead of two transforms to apply per vertex, and — more importantly —
 * there is exactly one implementation of "where does this canvas point
 * appear", so `CanvasTouchHandler` mapping a `MotionEvent` back to canvas
 * space and the compositor placing a tile quad cannot disagree.
 *
 * The four floats are `u_screen`'s components in that order.
 */
data class ScreenTransform(
    /** `s·cosθ·fs` — the [0,0] and [1,1] entry of the rotation-scale matrix. */
    val a: Float,
    /** `s·sinθ·fs` — the [1,0] entry; `−b` is [0,1]. */
    val b: Float,
    val tx: Float,
    val ty: Float,
) {
    /**
     * Screen px per canvas px — `fit.scale × view.scale`, and the number
     * §3.4's filter table is read with.
     *
     * Derived from [a] and [b] rather than carried separately, so it cannot
     * drift from the matrix it describes: `√(a² + b²) = fs·s` for any θ.
     */
    val effectiveScale: Float get() = kotlin.math.sqrt(a * a + b * b)

    /** Canvas px per screen px — `u_canvasPerScreen`, and the supersample step. */
    val canvasPerScreen: Float get() = if (effectiveScale > 0f) 1f / effectiveScale else 0f

    /**
     * The x of where canvas point ([x], [y]) appears, in view px.
     *
     * Scalar, and the single definition of this half of the transform: [apply]
     * delegates to it, [screenBoundsOf]'s corner walk calls it directly to stay
     * allocation-free, and the shader is handed the same four floats as
     * `u_screen`. Three inlined copies of `a·x − b·y + tx` would hold the
     * scissor rect and the geometry it clips together by coincidence — and a
     * scissor that disagrees with the draw clips content invisibly.
     */
    fun screenX(x: Float, y: Float): Float = a * x - b * y + tx

    /** The y of [screenX]'s point. Same reasoning; see there. */
    fun screenY(x: Float, y: Float): Float = b * x + a * y + ty

    /** Where canvas point ([x], [y]) appears, in view px. */
    fun apply(x: Float, y: Float): Pair<Float, Float> = Pair(screenX(x, y), screenY(x, y))

    /**
     * The inverse of [apply]: a view point back to canvas px — what
     * `CanvasTouchHandler` maps a `MotionEvent` with.
     *
     * The matrix is `[a −b; b a]`, whose determinant is `a² + b² =
     * effectiveScale²`, so the inverse is `[a b; −b a] / (a² + b²)` — a
     * similarity again, and closed-form rather than a general 2×2 solve.
     * A degenerate transform (scale 0, which [ViewTransform.MIN_SCALE] and
     * [FitTransform] both make impossible for real inputs) answers the origin
     * rather than dividing by zero and handing back NaN coordinates, which
     * would travel into a stroke.
     */
    fun invert(x: Float, y: Float): Pair<Float, Float> {
        val det = a * a + b * b
        if (det <= 0f || !det.isFinite()) return Pair(0f, 0f)
        val dx = x - tx
        val dy = y - ty
        return Pair((a * dx + b * dy) / det, (-b * dx + a * dy) / det)
    }

    /**
     * The screen-space bounding box of a canvas-space rect, inflated by one
     * pixel and clipped to a [viewportWidth] × [viewportHeight] viewport —
     * the scissor rect of §8.1 step 3.
     *
     * **The bounding box of the four transformed corners, not the transformed
     * corners.** Under rotation the image of an axis-aligned rect is not
     * axis-aligned, and a scissor is; taking two opposite corners would clip
     * the two that stick out, leaving wedges of a stroke undrawn at any angle
     * that is not a multiple of 90°.
     *
     * The one pixel of inflation absorbs the filter footprint at the edge and
     * the float-to-int rounding, both of which can otherwise leave a hairline
     * of stale pixels along the dirty rect's border.
     */
    fun screenBoundsOf(rect: IntRect, viewportWidth: Int, viewportHeight: Int): IntRect {
        if (rect.isEmpty || viewportWidth <= 0 || viewportHeight <= 0) return IntRect.EMPTY
        // NOT named l/t/r/b: `b` would shadow this class's own `b`, the matrix
        // coefficient. That is no longer load-bearing now that the corner walk
        // calls [screenX]/[screenY] instead of inlining the math, but it cost a
        // real bug once — Kotlin resolves the innermost binding and says
        // nothing — and the names stay unambiguous.
        val x0 = rect.left.toFloat()
        val y0 = rect.top.toFloat()
        val x1 = rect.right.toFloat()
        val y1 = rect.bottom.toFloat()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        // Written out rather than looped over a `listOf(l to t, …)`: that form
        // allocated four boxed Pairs plus one per `apply` call, nine per frame,
        // on the path that produces the present pass's scissor rect. The rest
        // of this engine keeps the frame path allocation-free — `visibleKeys`
        // takes an `IntArray` out-param and `allKeys` is marked cold — and this
        // was quietly undercutting that.
        //
        // All FOUR corners, still: under rotation the image of an axis-aligned
        // rect is not axis-aligned, and two opposite corners clip the others.
        var ok = true
        fun corner(cx: Float, cy: Float) {
            // The scalar helpers, not `apply`: same formula, no Pair.
            val sx = screenX(cx, cy)
            val sy = screenY(cx, cy)
            if (!sx.isFinite() || !sy.isFinite()) {
                ok = false
                return
            }
            minX = min(minX, sx); maxX = max(maxX, sx)
            minY = min(minY, sy); maxY = max(maxY, sy)
        }
        corner(x0, y0)
        corner(x1, y0)
        corner(x1, y1)
        corner(x0, y1)
        if (!ok) return IntRect.EMPTY
        val left = floor(minX - 1f).toInt().coerceIn(0, viewportWidth)
        val top = floor(minY - 1f).toInt().coerceIn(0, viewportHeight)
        val right = ceil(maxX + 1f).toInt().coerceIn(0, viewportWidth)
        val bottom = ceil(maxY + 1f).toInt().coerceIn(0, viewportHeight)
        return if (left >= right || top >= bottom) IntRect.EMPTY
        else IntRect(left, top, right, bottom)
    }

    companion object {
        /**
         * `view ∘ fit`, per §3.1.
         *
         * `t'` is `view.apply(fit.offset)` — the *fit's* offset carried through
         * the view, not the view's own translation. Using `view.tx/ty`
         * directly is the natural-looking mistake and puts the canvas in the
         * wrong place for every non-identity view on a letterboxed canvas,
         * which is every canvas whose aspect differs from the viewport's.
         */
        fun of(fit: FitTransform, view: ViewTransform): ScreenTransform {
            val (tx, ty) = view.apply(fit.offsetX, fit.offsetY)
            return ScreenTransform(
                a = view.a * fit.scale,
                b = view.b * fit.scale,
                tx = tx,
                ty = ty,
            )
        }
    }
}
