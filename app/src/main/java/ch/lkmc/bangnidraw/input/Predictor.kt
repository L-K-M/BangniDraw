package ch.lkmc.bangnidraw.input

import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import ch.lkmc.bangnidraw.engine.core.PointerTool

/**
 * `MotionEventPredictor`, kept behind the shared [StrokePredictor] seam
 * (`docs/plan/07-input-and-stylus.md` §8, `02-architecture.md` §2.6).
 *
 * §2.6's rule is that androidx and platform types stop at the input
 * boundary; nothing in shared code may name one. Without this the predictor
 * would have to be threaded from the surface into whatever builds the tail,
 * and `MotionEvent` would travel with it.
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
) : StrokePredictor {

    /**
     * Whether the surface this predicts for is still on screen.
     *
     * The per-frame callback reposts itself for as long as a stroke is live,
     * and a surface torn down mid-stroke — back navigation, a system teardown
     * — does not reliably deliver an `ACTION_CANCEL` to end that stroke.
     * Without a check of its own the callback would repost forever, holding
     * the handler and its host alive with it. §8 already says a predictor is
     * recreated with its surface; this is the same fact read the other way
     * round.
     */
    override val isUsable: Boolean get() = view.isAttachedToWindow

    /** Feeds one real event. §8: every `DOWN`/`MOVE`/`UP`, never a predicted one. */
    fun record(event: MotionEvent) {
        delegate.record(event)
    }

    /**
     * Flattens this frame's guess for [pointerId] into the reused sample
     * buffer and returns how many: nearest prediction first, furthest-ahead
     * last. The samples are valid until the *next* [predict] of any
     * pointer, not merely within the frame.
     *
     * Zero is routine rather than exceptional: the library returns null
     * before it has enough history, and the platform implementation can
     * decline outright. The caller draws no tail that frame and nothing else
     * changes.
     *
     * **The predicted event is not recycled**, and that is a decision rather
     * than an oversight. The library switches implementations underneath — on
     * Android 14+ it forwards the platform `MotionEventPredictor`'s own event,
     * below it builds one with `MotionEvent.obtain` (both read from the 1.0.0
     * bytecode) — and neither the class's javadoc nor Android's own stylus
     * guide says who owns the result; the guide's sample simply drops it.
     * Recycling an event the platform still owns is a crash somewhere else
     * entirely; not recycling one we own is a small object per frame for the
     * collector. The asymmetry decides it.
     *
     * Read within the frame and never kept, either way.
     *
     * Every flattened sample inherits the current sample's tool type —
     * `MotionEvent` has no per-sample tool history — which is safe today
     * because a tool change cannot be reported within one event, and worth
     * knowing before anyone assumes otherwise.
     */
    override fun predict(pointerId: Int): Int {
        count = 0
        val e = delegate.predict() ?: return 0
        val pointer = e.findPointerIndex(pointerId)
        if (pointer < 0) return 0

        val sampleCount = e.historySize + 1
        if (samples.size < sampleCount) samples = Array(sampleCount) { PointerSample() }
        for (h in 0 until e.historySize) {
            samples[h].set(
                pointerId = pointerId,
                tool = toolOf(e.getToolType(pointer)),
                x = e.getHistoricalX(pointer, h),
                y = e.getHistoricalY(pointer, h),
                pressure = e.getHistoricalPressure(pointer, h),
                tilt = e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointer, h),
                orientation = e.getHistoricalOrientation(pointer, h),
                timeNs = e.getHistoricalEventTime(h) * NANOS_PER_MILLISECOND,
            )
        }
        samples[e.historySize].set(
            pointerId = pointerId,
            tool = toolOf(e.getToolType(pointer)),
            x = e.getX(pointer),
            y = e.getY(pointer),
            pressure = e.getPressure(pointer),
            tilt = e.getAxisValue(MotionEvent.AXIS_TILT, pointer),
            orientation = e.getOrientation(pointer),
            timeNs = e.eventTime * NANOS_PER_MILLISECOND,
        )
        count = sampleCount
        return sampleCount
    }

    override fun predictedAt(index: Int): PointerSample {
        // A tripwire, not a validation policy: reading past the last count
        // serves stale samples from a previous frame silently.
        require(index in 0 until count) {
            "predictedAt($index) outside the last predict() range (0 until $count)"
        }
        return samples[index]
    }

    private var samples = Array(1) { PointerSample() }

    /** Valid entries in [samples] after the last [predict]; beyond it is stale. */
    private var count = 0

    private fun toolOf(toolType: Int): PointerTool = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerTool.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerTool.ERASER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerTool.MOUSE
        else -> PointerTool.FINGER
    }

    companion object {
        private const val TAG = "Predictor"
        private const val NANOS_PER_MILLISECOND = 1_000_000L

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
         * `AndroidCanvasInput.attachPredictor` attempts each view exactly once.
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
