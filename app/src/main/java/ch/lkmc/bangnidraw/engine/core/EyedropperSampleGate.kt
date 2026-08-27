package ch.lkmc.bangnidraw.engine.core

/**
 * Caps how often an eyedropper drag may read pixels
 * (`docs/plan/10-performance.md` §2.4's no-stall spirit, applied to the one
 * read path a stroke can drive).
 *
 * Every read is a synchronous `glReadPixels` — a full pipeline sync on the GL
 * thread. Unbuffered dispatch delivers samples at the digitizer's rate (with
 * historical samples, hundreds per second on a 240 Hz pen), so an unthrottled
 * eyedropper drag issues hundreds of pipeline stalls per second while the
 * pointer moves.
 *
 * Dropping an intermediate read is invisible in the right way: the preview
 * shows the color of the last read that did happen — at most [intervalMs] of
 * pointer travel behind — and pen-up commits exactly what the user was shown.
 *
 * Pure JVM so the policy is testable; the caller owns the clock.
 */
class EyedropperSampleGate(
    /** Minimum spacing between reads, in the caller's milliseconds. */
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {

    init {
        require(intervalMs >= 0L) { "interval must not be negative, was $intervalMs" }
    }

    private var lastReadMs = 0L
    private var read = false

    /** True when this sample may read now; updates the gate either way. */
    fun shouldRead(nowMs: Long): Boolean {
        if (read && nowMs - lastReadMs < intervalMs) return false

        read = true
        lastReadMs = nowMs
        return true
    }

    /** A new stroke starts fresh: its first sample always reads. */
    fun reset() {
        read = false
    }

    companion object {
        /** One read per frame at 60 Hz — the preview rate the eye tracks. */
        const val DEFAULT_INTERVAL_MS = 16L
    }
}
