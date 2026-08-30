package ch.lkmc.bangnidraw.engine.core

import kotlin.math.pow

/**
 * Wheel and trackpad scroll → a zoom factor about the cursor
 * (`docs/plan/07-input-and-stylus.md` §2's mouse support, the wheel half).
 *
 * Pure so the mapping is testable without a `MotionEvent`: the handler reads
 * `AXIS_VSCROLL` and hands the raw tick count here. Scrolling up — wheel
 * rotated away from the user — zooms in, the convention every canvas app and
 * map shares; the factor composes through [ViewTransform.gesture], whose
 * scale clamp and exact-pivot arithmetic this deliberately does not repeat.
 */
object ScrollZoom {

    /** One wheel notch multiplies the scale by this. */
    const val STEP_PER_NOTCH = 1.15f

    /**
     * One event's ticks are bounded so a burst — a coasting trackpad fling
     * delivering many accumulated notches in one event — cannot teleport the
     * zoom across its whole range in a single frame.
     */
    const val MAX_TICKS_PER_EVENT = 4f

    /**
     * [ticks] is `AXIS_VSCROLL`'s value: whole notches for a wheel,
     * fractional for a high-resolution trackpad. Non-finite input is a
     * malformed event and zooms nothing.
     */
    fun factor(ticks: Float): Float {
        if (!ticks.isFinite() || ticks == 0f) return 1f

        return STEP_PER_NOTCH.pow(ticks.coerceIn(-MAX_TICKS_PER_EVENT, MAX_TICKS_PER_EVENT))
    }

    /**
     * View-space pivot for one scroll event, or null when the event carries
     * no honest one. Pointer-class sources pivot at the cursor. A
     * directly-reporting touchpad's coordinates are pad-relative, so it
     * pivots at the viewport centre — and before layout has provided a view
     * size, the event is dropped rather than zoomed about a fabricated
     * point.
     */
    fun pivot(
        pointerClass: Boolean,
        eventX: Float,
        eventY: Float,
        viewWidth: Float?,
        viewHeight: Float?,
    ): Pair<Float, Float>? {
        if (pointerClass) return eventX to eventY
        if (viewWidth == null || viewHeight == null) return null

        return viewWidth / 2f to viewHeight / 2f
    }
}
