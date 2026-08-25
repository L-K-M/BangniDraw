package ch.lkmc.bangnidraw.engine.core

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * One two-finger navigation step (`docs/plan/07-input-and-stylus.md` §7).
 *
 * Computed per `ACTION_MOVE` from the two navigating pointers' previous and
 * current positions, in view px:
 *
 * ```
 * centroid = (q0 + q1) / 2
 * pan      = centroid − (p0 + p1) / 2
 * zoom     = |q1 − q0| / |p1 − p0|
 * rotation = atan2(q1 − q0) − atan2(p1 − p0), wrapped to (−π, π]
 * ```
 *
 * Pure, and separate from the handler, because it is the arithmetic that makes
 * "the point under the fingers stays under the fingers" true — a property worth
 * pinning without a device. [ViewTransform.gesture] does the rest by
 * construction, and similarities compose exactly, so a long session of pinches
 * does not drift.
 *
 * Results are written into the caller's instance rather than returned, because
 * this runs per `ACTION_MOVE` and `10-performance.md` §2.4 allows no allocation
 * on the touch path.
 */
class NavigationStep {

    /** The centroid the fingers are at **now** — informational. */
    var centroidX: Float = 0f
        private set
    var centroidY: Float = 0f
        private set

    /**
     * The centroid the fingers were at **before** this step: the point
     * [ViewTransform.gesture] must pivot about.
     *
     * `docs/plan/07-input-and-stylus.md` §7 writes `centroid = (q0 + q1) / 2`
     * and hands *that* to `gesture`. **That is wrong**, and measurably so:
     * `gesture` rotates and scales the view about the anchor it is given and
     * *then* adds `pan`, so anchoring at the current centroid translates by pan
     * twice over. The canvas point under the fingers drifts about 2 px per step
     * on a rotating pinch — invisible in one frame, a slide across the screen
     * over a gesture. Anchoring at the previous centroid makes it exact: the
     * point under the fingers before the step lands at `prev + pan`, which is
     * the current centroid by definition of pan.
     *
     * Pure pan hides it — with `zoom = 1` and `rotation = 0` the two anchors
     * agree — which is presumably how the snippet came to be written that way.
     */
    var anchorX: Float = 0f
        private set
    var anchorY: Float = 0f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set
    var zoom: Float = 1f
        private set
    var rotation: Float = 0f
        private set

    /**
     * Two pointers moving from `p` to `q`.
     *
     * A separation under [MIN_SEPARATION_PX] gives `zoom = 1` and
     * `rotation = 0`: dividing by it would send the zoom to infinity, and the
     * angle between two points a fraction of a pixel apart is noise. Fingers
     * genuinely that close are one contact the digitizer split in two.
     */
    fun fromPointers(
        p0x: Float, p0y: Float, p1x: Float, p1y: Float,
        q0x: Float, q0y: Float, q1x: Float, q1y: Float,
    ) {
        centroidX = (q0x + q1x) * 0.5f
        centroidY = (q0y + q1y) * 0.5f
        anchorX = (p0x + p1x) * 0.5f
        anchorY = (p0y + p1y) * 0.5f
        panX = centroidX - anchorX
        panY = centroidY - anchorY

        val prevSpan = hypot(p1x - p0x, p1y - p0y)
        val curSpan = hypot(q1x - q0x, q1y - q0y)
        if (prevSpan < MIN_SEPARATION_PX || curSpan < MIN_SEPARATION_PX) {
            zoom = 1f
            rotation = 0f
            return
        }
        zoom = curSpan / prevSpan
        rotation = ViewTransform.normalizeAngle(
            atan2(q1y - q0y, q1x - q0x) - atan2(p1y - p0y, p1x - p0x),
        )
    }

    /**
     * One pointer: pan only.
     *
     * Stylus-only mode navigates with a single finger, and §7 is explicit that
     * it neither zooms nor rotates — there is no second point to measure either
     * against. The same path serves a two-finger gesture whose second finger
     * lifted, which §3 says continues as pan-only rather than ending.
     */
    fun fromSinglePointer(px: Float, py: Float, qx: Float, qy: Float) {
        centroidX = qx
        centroidY = qy
        anchorX = px
        anchorY = py
        panX = qx - px
        panY = qy - py
        zoom = 1f
        rotation = 0f
    }

    /** Applies this step to [view], pivoting about [anchorX] — see its KDoc. */
    fun applyTo(view: ViewTransform): ViewTransform = view.gesture(
        // The PREVIOUS centroid, deliberately — see anchorX's KDoc. Now that
        // the parameters are named pivot, this reads as what it is.
        pivotX = anchorX,
        pivotY = anchorY,
        panX = panX,
        panY = panY,
        zoom = zoom,
        rotationDelta = rotation,
    )

    companion object {
        /** Below this the pointers are one contact the digitizer split in two. */
        const val MIN_SEPARATION_PX = 1f
    }
}
