package ch.lkmc.bangnidraw.input

import ch.lkmc.bangnidraw.engine.core.PointerTool

/**
 * Whether a pointer is a palm (`docs/plan/07-input-and-stylus.md` §5).
 *
 * Three layers, cheapest first, and only the middle one is ours:
 *
 * 1. **The platform.** Samsung's firmware suppresses most palm touches while
 *    the S Pen is in range, and API 33+ can retroactively cancel a delivered
 *    pointer with `FLAG_CANCELED`. We take what we get.
 * 2. **The stylus-near rule** — this class. While a stylus is down or hovering,
 *    or within the hover grace, every finger is rejected.
 * 3. **A size heuristic — deliberately not used.** `AXIS_TOUCH_MAJOR` varies
 *    too much across devices to threshold reliably, and the stylus-near rule
 *    already covers every case where a palm can reach the glass while drawing
 *    with a pen. Finger drawing on a phone has no palm to reject.
 *
 * Pure, so the policy can be tested without a device; `CanvasTouchHandler`
 * obeys it rather than reimplementing it.
 */
object PalmRejection {

    /**
     * True when this pointer should be ignored entirely.
     *
     * A stylus is never rejected — it is the thing being protected. A finger is
     * rejected whenever [StylusState.isNear], which covers the pen resting, the
     * pen hovering, and the grace after it lifts.
     */
    fun rejects(tool: PointerTool, stylus: StylusState, nowNs: Long): Boolean = when (tool) {
        PointerTool.STYLUS, PointerTool.ERASER -> false
        // A mouse cannot be a palm and does not coexist with a pen on the same
        // surface; rejecting it would break the desktop path of §9 for no gain.
        PointerTool.MOUSE -> false
        PointerTool.FINGER -> stylus.isNear(nowNs)
    }

    /**
     * Whether a finger stroke **already in progress** should be cancelled
     * because the stylus arrived.
     *
     * Hover alone does not: the user may be drawing with a finger with the pen
     * in the other hand, and yanking the stroke away would be worse than the
     * palm risk. Contact does — at that point the pen's stroke is the intent.
     * That asymmetry is §5's last paragraph, and it is the reason this is a
     * separate question from [rejects] rather than the same flag read twice.
     */
    fun cancelsLiveFingerStroke(stylus: StylusState): Boolean = stylus.isDown
}
