package ch.lkmc.bangnidraw.input

import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor

/**
 * `MotionEventPredictor`, kept behind one class
 * (`docs/plan/07-input-and-stylus.md` §8, `02-architecture.md` §2.6).
 *
 * §2.6's rule is that androidx and platform types stop at the `input/`
 * boundary; nothing in `engine/core` may name one. Without this the predictor
 * would have to be threaded from the surface into whatever builds the tail, and
 * `MotionEvent` would travel with it.
 *
 * It is deliberately almost nothing — the roadmap says so in as many words
 * ("`Predictor` is a thin wrapper whose whole purpose is that core never sees
 * the androidx type"), which is why there is no `PredictorTest`: everything it
 * could be asked about is the library's answer, and the library needs a real
 * `View`.
 *
 * **One per surface.** §8: `newInstance` is given the `SurfaceView`, and a
 * predictor outliving its surface would be predicting into a window that no
 * longer exists. [forView] is what enforces that — it rebuilds when the view it
 * was made for is not the one asking.
 *
 * Main-thread-only, like the input path it sits on.
 */
class Predictor private constructor(
    private val view: View,
    private val delegate: MotionEventPredictor,
) {

    /**
     * Whether the surface this predicts for is still on screen.
     *
     * The per-frame callback reposts itself for as long as a stroke is live,
     * and a surface torn down mid-stroke — back navigation, a system teardown
     * — does not reliably deliver an `ACTION_CANCEL` to end it. Without a check
     * of its own the callback would repost forever, holding the handler and its
     * host alive with it. §8 already says a predictor is recreated with its
     * surface; this is the same fact read the other way round.
     */
    val isUsable: Boolean get() = view.isAttachedToWindow

    /** Feeds one real event. §8: every `DOWN`/`MOVE`/`UP`, never a predicted one. */
    fun record(event: MotionEvent) {
        delegate.record(event)
    }

    /**
     * This frame's guess, or null when the predictor has nothing to offer.
     *
     * Null is routine rather than exceptional: the library returns it before it
     * has enough history, and the platform implementation can decline outright.
     * The caller draws no tail that frame and nothing else changes.
     *
     * **Not recycled**, and that is a decision rather than an oversight. The
     * library switches implementations underneath — on Android 14+ it forwards
     * the platform `MotionPredictor`'s own event, below it builds one with
     * `MotionEvent.obtain` (both read from the 1.0.0 bytecode) — and neither
     * the class's javadoc nor Android's own stylus guide says who owns the
     * result; the guide's sample simply drops it. Recycling an event the
     * platform still owns is a crash somewhere else entirely; not recycling one
     * we own is a small object per frame for the collector. The asymmetry
     * decides it.
     *
     * Read within the frame and never kept, either way.
     */
    fun predict(): MotionEvent? = delegate.predict()

    companion object {
        private const val TAG = "Predictor"

        /**
         * [existing] if it already belongs to [view], otherwise a new one — or
         * null if this device has no predictor to give.
         *
         * `newInstance` reaches the platform's own prediction service on
         * Android 14+, and a device whose implementation is missing or refuses
         * to initialize throws rather than returning null. Catching it here
         * turns "this device cannot predict" into a session that simply draws
         * no tail, which is the same outcome as a predictor that always
         * declines — and one the app is already built to handle — rather than a
         * crash on the first pen-down.
         *
         * **A null return must not be retried per event.** Building and
         * throwing a `RuntimeException` is cheap once and ruinous at 240 Hz,
         * and the `Log.w` carries a full stack trace each time. This function
         * cannot enforce that itself — a null answer carries no view to
         * remember — so the caller owns it:
         * `CanvasTouchHandler.attachPredictor` attempts each view exactly once.
         */
        fun forView(view: View, existing: Predictor?): Predictor? {
            if (existing != null && existing.view === view) return existing
            return try {
                Predictor(view, MotionEventPredictor.newInstance(view))
            } catch (e: RuntimeException) {
                Log.w(TAG, "no motion predictor on this device; drawing without a tail", e)
                null
            }
        }
    }
}
