package ch.lkmc.bangnidraw.engine.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round

/**
 * Whether the canvas rotation is snapped to zero, and when it lets go
 * (`docs/plan/07-input-and-stylus.md` §7).
 *
 * The gesture math accumulates into a **raw** angle that keeps running while
 * the displayed rotation is pinned at exactly `0f`. Keeping the two apart is
 * what lets a small counter-rotation leave the snap instead of fighting it: if
 * the displayed value were the only state, snapping would erase the very
 * evidence needed to un-snap.
 *
 * Enter and exit use different thresholds — [SNAP_IN] in, [SNAP_OUT] out — and
 * the wider exit band is the hysteresis. With one threshold the rotation
 * flickers in and out at the boundary while a finger holds still on it, which
 * on a canvas reads as the paper twitching.
 *
 * Exactly `0f` matters beyond looks: [ViewTransform.isIdentity] decides whether
 * the reset-view pill is shown, and a rotation of 1e-8 would leave it on
 * screen for a canvas that is visibly straight.
 */
class RotationSnap(
    /** Snap to 90° multiples as well as to zero (`Prefs.snapRightAngles`, off by default). */
    var snapRightAngles: Boolean = false,
) {

    var isSnapped: Boolean = false
        private set

    /**
     * The angle the gesture has accumulated, ignoring the snap. Keeps moving
     * while [isSnapped].
     */
    var raw: Float = 0f
        private set

    /**
     * Feeds an accumulated raw angle and returns the angle to display.
     *
     * Returns exactly `0f` (or exactly a right angle) while snapped, and [raw]
     * otherwise.
     */
    fun update(rawAngle: Float): Float {
        val before = isSnapped
        raw = ViewTransform.normalizeAngle(rawAngle)
        val target = nearestTarget(raw)
        val distance = abs(ViewTransform.normalizeAngle(raw - target))
        isSnapped = if (isSnapped) distance <= SNAP_OUT else distance <= SNAP_IN
        justEntered = !before && isSnapped
        return if (isSnapped) target else raw
    }

    /**
     * Whether the last [update] crossed **into** the snap.
     *
     * A caller that needs both the angle to display and the entry edge reads
     * this instead of re-deriving the edge around [update] — two hand-rolled
     * copies of "did we just enter" can drift from each other and from
     * [updateAndDetectEntry].
     */
    var justEntered: Boolean = false
        private set

    /**
     * True when this update crossed **into** the snap — the one transition that
     * fires a haptic tick (`HapticFeedbackConstants.CLOCK_TICK`, gated by
     * `Prefs.haptics`). Leaving the snap is silent: a tick on the way out would
     * fire while the user is already rotating and reads as noise.
     */
    fun updateAndDetectEntry(rawAngle: Float): Boolean {
        update(rawAngle)
        return justEntered
    }

    /** A new gesture, or a view reset: nothing is snapped and nothing accumulated. */
    fun reset() {
        isSnapped = false
        justEntered = false
        raw = 0f
    }

    /**
     * Zero, or the nearest right angle when [snapRightAngles] is on.
     *
     * Off by default, and the reason is a product one: a painter turning the
     * canvas to suit a stroke does not want it snapping at 90°, while someone
     * working on a rotated portrait does. Only the user knows which they are.
     */
    private fun nearestTarget(angle: Float): Float {
        if (!snapRightAngles) return 0f
        val quarter = (PI / 2.0).toFloat()
        return ViewTransform.normalizeAngle(round(angle / quarter) * quarter)
    }

    companion object {
        /** 3° — unsnapped becomes snapped inside this. */
        const val SNAP_IN = 0.0524f

        /** 5° — snapped stays snapped out to here. The gap is the hysteresis. */
        const val SNAP_OUT = 0.0873f
    }
}
