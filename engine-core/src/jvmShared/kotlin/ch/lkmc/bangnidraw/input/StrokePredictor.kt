package ch.lkmc.bangnidraw.input

/**
 * The predicted-tail source behind `CanvasTouchHandler`
 * (`07-input-and-stylus.md` §8, DESKTOP.md seam 3).
 *
 * The Android implementation wraps `MotionEventPredictor` and stays in the
 * app module — the platform event type cannot cross into shared code. A
 * null predictor is a supported configuration everywhere it is consulted:
 * no predicted tail, everything else identical. The desktop host passes
 * null (DESKTOP.md: prediction is platform-tailored, and desktop input
 * rates are modest).
 *
 * Recording is the host's job — this interface only predicts. The glue
 * feeding real events must give the predictor every stylus/eraser event,
 * hover included (§8: hover history improves the first predicted samples
 * after contact), and must classify by scanning **every** pointer's tool
 * type, not pointer index 0 — a palm that landed first is pointer 0, and
 * the pen's history would silently never be recorded.
 */
interface StrokePredictor {

    /** Whether the surface this predicts for is still on screen. */
    val isUsable: Boolean

    /**
     * Fills this predictor's internal samples with the current frame's
     * guess for [pointerId] and returns how many — nearest prediction
     * first, furthest-ahead last — or 0 when there is nothing to offer.
     *
     * Zero is routine rather than exceptional (not enough history yet, or
     * the implementation declines); the caller draws no tail that frame
     * and nothing else changes. The samples stay valid until the next
     * [predict].
     */
    fun predict(pointerId: Int): Int

    /** Sample [index] of the last [predict] result, nearest-first. */
    fun predictedAt(index: Int): PointerSample
}
