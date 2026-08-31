package ch.lkmc.bangnidraw.engine.core

/**
 * Whether the predicted tail is worth drawing right now
 * (`docs/plan/07-input-and-stylus.md` §8, `03-canvas-engine.md` §9).
 *
 * §8: the handler keeps an exponentially-weighted error
 * `err = 0.9·err + 0.1·|predicted − actual|` in screen px, and when it passes
 * [DISABLE_PX] it stops sending tails for the rest of the stroke, re-enabling
 * at the next pen-down. A wildly wrong tail is worse than a few ms of latency:
 * the user sees ink that is not where their pen is, every frame, and the eye
 * catches that far more readily than lag.
 *
 * §15's rule puts it here rather than in the handler. "Is the prediction good
 * enough" is a decision, it is the one piece of §9 that has no device-visible
 * output of its own, and it is the piece most likely to be subtly wrong — an
 * EMA that never decays, a threshold compared before the first sample, a
 * disable that does not survive to the end of the stroke.
 *
 * Screen px throughout, not canvas px: the threshold is about what the eye
 * sees, so a stroke at 8× zoom must not become eight times more tolerant of a
 * bad guess.
 */
class PredictionGate {

    /** The running error estimate, in screen px. Public for the debug overlay of 2.5d. */
    var error: Float = 0f
        private set

    /**
     * False once this stroke's prediction has been judged too poor to draw.
     *
     * Latches for the rest of the stroke rather than recovering when the error
     * drops back: §8 says "for the rest of the stroke", and a tail that
     * flickered on and off as the estimate crossed the threshold would be more
     * distracting than either state.
     */
    var enabled: Boolean = true
        private set

    /** Whether a sample has been compared yet — before that the error means nothing. */
    private var seeded = false

    /**
     * A new stroke: prediction is on again and the estimate starts empty.
     *
     * §8's "re-enabled at the next `ACTION_DOWN`". Carrying the previous
     * stroke's error would let one bad flick disable prediction for a session.
     */
    fun reset() {
        error = 0f
        enabled = true
        seeded = false
        pending = false
        hasActual = false
        // The scored pair is deliberately NOT cleared: it describes a
        // comparison that really happened, and it is only ever read straight
        // after `actual` returns true. Zeroing it would put a point at the
        // canvas origin in front of anyone who read it out of turn, which is
        // worse than a stale one — a stale pair looks stale, an origin looks
        // like a measurement.
    }

    /**
     * Folds one screen-px miss into the estimate and re-checks the threshold.
     *
     * [distancePx] is `|predicted − actual|` for a predicted point whose real
     * sample has now arrived. Non-finite input is ignored rather than allowed
     * to poison the EMA into a permanent disable — a NaN would compare false
     * against the threshold *and* make every later value NaN.
     */
    fun observe(distancePx: Float) {
        if (!distancePx.isFinite() || distancePx < 0f) return
        error = if (seeded) {
            DECAY * error + (1f - DECAY) * distancePx
        } else {
            // The first observation IS the estimate. Blending it against a zero
            // that no sample produced would halve every early error and delay
            // the disable by several frames, which is exactly when a bad
            // predictor is most visible — at the start of a fast stroke.
            seeded = true
            distancePx
        }
        if (error > DISABLE_PX) enabled = false
    }

    /** [observe] for a predicted point and the real one, in screen px. */
    fun observe(predictedX: Float, predictedY: Float, actualX: Float, actualY: Float) {
        val dx = predictedX - actualX
        val dy = predictedY - actualY
        observe(kotlin.math.sqrt(dx * dx + dy * dy))
    }

    // ------------------------------------------- scoring a live prediction

    private var pending = false
    private var pendingX = 0f
    private var pendingY = 0f
    private var pendingNs = 0L

    private var hasActual = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastNs = 0L

    /**
     * The pair the most recent scoring compared, for §8's overlay to draw.
     *
     * Published from here rather than recomputed by the caller because the
     * *actual* point is interpolated to the predicted instant, and that
     * interpolation is the one part of this class that is easy to get subtly
     * wrong. A second implementation in the overlay's feeder would be a second
     * chance to get it wrong, drawing points that disagree with the error the
     * same overlay reports beside them.
     *
     * Only meaningful right after [actual] returned true.
     */
    var scoredPredictedX: Float = 0f
        private set
    var scoredPredictedY: Float = 0f
        private set
    var scoredActualX: Float = 0f
        private set
    var scoredActualY: Float = 0f
        private set

    /** Whether a prediction is waiting for the pen to reach its instant. */
    val hasPending: Boolean get() = pending

    /**
     * Remembers one predicted point to be scored when the pen reaches
     * [timeNs] — §8's "compared when the real sample for the predicted time
     * arrives".
     *
     * **The oldest pending prediction wins**: a later call while one is still
     * waiting is dropped. A new tail arrives every frame while the pen is
     * typically 2–3 frames behind the lookahead, so overwriting would keep
     * replacing the prediction just before the pen got to it and the error
     * would never be measured at all — the estimate would sit at zero and the
     * disable would never fire, which is indistinguishable from a perfect
     * predictor.
     *
     * Screen px, like everything else here.
     */
    fun predicted(x: Float, y: Float, timeNs: Long) {
        if (pending) return
        if (!x.isFinite() || !y.isFinite()) return
        // A prediction at or before the last real sample can never be scored —
        // interpolation needs the pen to still be short of it — so it is
        // refused rather than left to block the slot forever.
        if (hasActual && timeNs <= lastNs) return
        pending = true
        pendingX = x
        pendingY = y
        pendingNs = timeNs
    }

    /**
     * Feeds one real sample, scoring the pending prediction once the pen has
     * passed its instant.
     *
     * The real path rarely lands a sample at exactly the predicted time, so
     * the comparison is against the pen's position *interpolated* to that
     * instant between this sample and the one before it — §8's "interpolated".
     * Comparing against the nearest real sample instead would fold the pen's
     * own motion between samples into the predictor's error: at 240 Hz and a
     * brisk 3000 px/s that is over 12 px of pure sampling offset, enough to
     * trip [DISABLE_PX] on a predictor that was exactly right.
     *
     * **Returns true when this call scored a pending prediction**, which is
     * when [scoredPredictedX] and friends are worth reading. A return value
     * rather than a counter the caller polls: scoring happens on at most one
     * sample in many, and the alternative is every caller remembering the
     * previous count to notice the edge.
     */
    fun actual(x: Float, y: Float, timeNs: Long): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        var scored = false
        if (pending && hasActual && timeNs >= pendingNs) {
            val span = timeNs - lastNs
            // A zero (or inverted) span means both samples carry one instant —
            // routine, since a device may stamp a whole historical run with the
            // batch's event time. There is nothing to interpolate along, so the
            // newer sample is the pen's position at that instant.
            val t = if (span > 0L) {
                ((pendingNs - lastNs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                1f
            }
            val atX = lastX + (x - lastX) * t
            val atY = lastY + (y - lastY) * t
            observe(pendingX, pendingY, atX, atY)
            scoredPredictedX = pendingX
            scoredPredictedY = pendingY
            scoredActualX = atX
            scoredActualY = atY
            pending = false
            scored = true
        }
        hasActual = true
        lastX = x
        lastY = y
        lastNs = timeNs
        return scored
    }

    /**
     * How much of a predicted run to keep, given each sample's age past the
     * last real one.
     *
     * §8 truncates the tail to [MAX_LOOKAHEAD_NS] by dropping predicted
     * samples beyond it — "longer tails visibly overshoot at stroke ends".
     * Returns the number of leading samples to keep, so a caller walks
     * `0 until keepCount(...)`.
     *
     * [ageNs] must be non-decreasing; the first sample that is too old ends the
     * tail, because a later one cannot be younger.
     *
     * `inline` because the only caller's [ageNs] closes over the batch it is
     * truncating, and a capturing lambda allocated once a frame is exactly what
     * `10-performance.md` §2.4 forbids on the way to
     * `renderFrontBufferedLayer`. Nothing here reads private state, so the
     * inlining costs no visibility.
     */
    inline fun keepCount(count: Int, ageNs: (Int) -> Long): Int {
        var kept = 0
        while (kept < count) {
            val age = ageNs(kept)
            if (age > MAX_LOOKAHEAD_NS) break
            kept++
        }
        return kept
    }

    companion object {
        /** §8's `PREDICT_ERR_DISABLE_PX`. */
        const val DISABLE_PX = 12f

        /** §8's `PREDICT_MAX_NS` — one frame at 60 Hz, two at 120. */
        const val MAX_LOOKAHEAD_NS = 16_000_000L

        /** §8's `err = 0.9·err + 0.1·|predicted − actual|`. */
        const val DECAY = 0.9f
    }
}
