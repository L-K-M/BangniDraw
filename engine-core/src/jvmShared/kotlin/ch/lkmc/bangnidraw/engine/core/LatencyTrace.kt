package ch.lkmc.bangnidraw.engine.core

/**
 * The last N predictions and where the pen actually was
 * (`docs/plan/07-input-and-stylus.md` §8's overlay, `10-performance.md` §5.3).
 *
 * §8 ends on a recommendation rather than a measurement — "keep prediction on
 * for all refresh rates, including 120 Hz, **and measure**" — and names this as
 * the instrument: an overlay that "draws the last N real vs predicted points so
 * the error and the benefit are visible". A running average alone cannot settle
 * that argument; what a person needs to see is *where* the guesses land, since
 * a tail that leads the pen along the stroke reads completely differently from
 * one that sprays around it, and both produce the same mean error.
 *
 * **Screen px**, like everything the prediction path measures: the question is
 * what the eye sees, so a stroke at 8x zoom must not look eight times better.
 *
 * A fixed ring of parallel `FloatArray`s: it is fed from the input path, where
 * `10-performance.md` §2.4 allows no allocation, and it is read by a Compose
 * overlay at 4 Hz. Oldest entries are overwritten without ceremony — this is a
 * window on the recent past, not a log.
 *
 * Not thread-safe, and does not need to be: written on the main thread by
 * `CanvasTouchHandler` and read on the main thread by the overlay's recomposition.
 */
class LatencyTrace(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "a trace needs at least one slot, was $capacity" }
    }

    private val predX = FloatArray(capacity)
    private val predY = FloatArray(capacity)
    private val realX = FloatArray(capacity)
    private val realY = FloatArray(capacity)

    /** Where the next entry goes; wraps at [capacity]. */
    private var head = 0

    /** How many entries are live, capped at [capacity]. */
    var size = 0
        private set

    /**
     * Appends one scored prediction: where the tail said the pen would be, and
     * where it turned out to be at that same instant.
     *
     * Both points come from `PredictionGate`, which already interpolates the
     * real path to the predicted timestamp — recomputing that here would be a
     * second implementation of the one piece of this that is easy to get wrong.
     */
    fun record(predictedX: Float, predictedY: Float, actualX: Float, actualY: Float) {
        predX[head] = predictedX
        predY[head] = predictedY
        realX[head] = actualX
        realY[head] = actualY
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /**
     * Entry [i] counting **backwards from the newest**: 0 is the most recent
     * pair, `size - 1` the oldest still held.
     *
     * Newest-first because that is the order the overlay draws in — the recent
     * points get full opacity and the old ones fade — and because "the last N"
     * is how §8 asks for it. An index into the raw ring would make every caller
     * repeat the modular arithmetic that the wrap makes easy to get wrong.
     */
    fun predictedXAt(i: Int): Float = predX[indexOf(i)]
    fun predictedYAt(i: Int): Float = predY[indexOf(i)]
    fun actualXAt(i: Int): Float = realX[indexOf(i)]
    fun actualYAt(i: Int): Float = realY[indexOf(i)]

    /** The miss distance of entry [i], in screen px. */
    fun errorAt(i: Int): Float {
        val j = indexOf(i)
        val dx = predX[j] - realX[j]
        val dy = predY[j] - realY[j]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Empties the window — a new stroke's tail has nothing to do with the last
     * one's.
     *
     * `size = 0` is the load-bearing half: every read is bounded by it, so the
     * previous stroke's entries become unreachable whatever is still in the
     * arrays. Rewinding [head] is canonicalization, not correctness — reads are
     * relative to `head`, so a ring resumed mid-array behaves identically, and
     * removing the line kills no test. Said plainly because the alternative is
     * a reader assuming it matters and preserving it through a refactor that
     * did not need to.
     */
    fun clear() {
        head = 0
        size = 0
    }

    private fun indexOf(i: Int): Int {
        require(i in 0 until size) { "index $i is outside 0..${size - 1}" }
        // `head` points one PAST the newest, so the newest is at head - 1.
        //
        // The `+ capacity` keeps the result non-negative before the modulo, and
        // one is enough: `i < size <= capacity`, so `head - 1 - i` is at worst
        // -capacity. It is not decoration — Kotlin's `%` takes the dividend's
        // sign, so `(0 - 1) % 8` is -1 rather than 7, and the version without
        // it indexes off the front of the array on the first wrap.
        return (head - 1 - i + capacity) % capacity
    }

    companion object {
        /**
         * §8's "last N". Two seconds of scored predictions at 120 Hz would be
         * far more than a person can read off a screen; this is about a
         * second's worth of the *scored* subset (one per tail that the pen
         * caught up with, not one per frame), which is enough to see a pattern
         * and few enough to draw without hiding the stroke underneath.
         */
        const val DEFAULT_CAPACITY = 64
    }
}
