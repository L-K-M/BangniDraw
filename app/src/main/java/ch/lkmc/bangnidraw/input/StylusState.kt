package ch.lkmc.bangnidraw.input

import ch.lkmc.bangnidraw.engine.core.ButtonState
import ch.lkmc.bangnidraw.engine.core.PointerTool

/**
 * Where the stylus is and what it is doing — the latest hover position,
 * pressure, tilt, button state and tool type
 * (`docs/plan/02-architecture.md` §2.6, `07-input-and-stylus.md` §5, §6).
 *
 * A **single-writer mutable struct**: `CanvasTouchHandler` writes it on the
 * main thread from every event, and the hover cursor and the ViewModel read it
 * on a frame tick — not per event. That is deliberate. A hover moves at the
 * digitizer's rate, and turning each sample into a Compose recomposition would
 * spend a frame's budget redrawing a cursor while a stroke needs it.
 *
 * No Android types: `MotionEvent` stops at `CanvasTouchHandler`, which is what
 * lets this be tested on the JVM.
 */
class StylusState {

    /** Hover position in view px; meaningful only while [isHovering]. */
    var hoverX: Float = 0f
        private set
    var hoverY: Float = 0f
        private set

    /** Distance from the glass if the device reports it, else 0. */
    var hoverDistance: Float = 0f
        private set

    var tool: PointerTool = PointerTool.FINGER
        private set

    var isHovering: Boolean = false
        private set

    /** True between the pen touching down and lifting. */
    var isDown: Boolean = false
        private set

    /** The barrel button, from `ACTION_BUTTON_PRESS`/`RELEASE` (§6). */
    var buttonPressed: Boolean = false
        private set

    /** When the pen last left hover range, or [NEVER]. */
    var exitedAtNs: Long = NEVER
        private set

    fun onHoverEnter(x: Float, y: Float, distance: Float, tool: PointerTool) {
        hoverX = x
        hoverY = y
        hoverDistance = distance
        this.tool = tool
        isHovering = true
        exitedAtNs = NEVER
    }

    fun onHoverMove(x: Float, y: Float, distance: Float) {
        hoverX = x
        hoverY = y
        hoverDistance = distance
        isHovering = true
    }

    /**
     * The pen left hover range at [timeNs].
     *
     * The timestamp is the point of this method: [isNear] keeps answering true
     * for [HOVER_GRACE_MS] afterwards, which is what covers "lift the pen a
     * little too high between two strokes, palm still resting".
     */
    fun onHoverExit(timeNs: Long) {
        isHovering = false
        exitedAtNs = timeNs
    }

    fun onDown(x: Float, y: Float, tool: PointerTool) {
        hoverX = x
        hoverY = y
        this.tool = tool
        isDown = true
        isHovering = false
        exitedAtNs = NEVER
    }

    fun onUp(timeNs: Long) {
        isDown = false
        // A pen that lifts off the glass is usually still in hover range, but
        // the event for that may not arrive. Starting the grace here means the
        // palm stays rejected for the gap between strokes either way.
        exitedAtNs = timeNs
    }

    fun onButton(state: ButtonState) {
        buttonPressed = state == ButtonState.Pressed
    }

    /**
     * Whether a stylus is close enough that finger touches are palms (§5).
     *
     * True while the pen is down or hovering, and for [HOVER_GRACE_MS] after it
     * leaves. The grace is a guess to tune on a device, and it is the one
     * number here with a real cost either way: too long and the first finger
     * gesture after putting the pen down is swallowed, too short and a hover
     * flicker lets the palm mark.
     */
    fun isNear(nowNs: Long): Boolean {
        if (isDown || isHovering) return true
        if (exitedAtNs == NEVER) return false
        val sinceMs = (nowNs - exitedAtNs) / 1_000_000L
        // A clock that went backwards (a caller mixing time bases) reads as
        // "just left" rather than as an expired grace: rejecting a palm for too
        // long is recoverable, letting one through is a mark on the painting.
        return sinceMs < HOVER_GRACE_MS
    }

    /** A new surface or session: nothing is known about the pen. */
    fun reset() {
        isHovering = false
        isDown = false
        buttonPressed = false
        exitedAtNs = NEVER
        hoverDistance = 0f
    }

    companion object {
        const val NEVER = Long.MIN_VALUE

        /** §5: covers the pen being lifted a little too high between strokes. */
        const val HOVER_GRACE_MS = 500L
    }
}
