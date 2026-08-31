package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `docs/plan/07-input-and-stylus.md` §8's adaptive disable and tail
 * truncation.
 *
 * This class has no device-visible output of its own — it only ever says
 * "draw the tail" or "don't" — so a wrong answer here is invisible until it is
 * a tail that never appears or a wrong one that never goes away. Every
 * assertion below was mutation-tested: each named field or constant, changed,
 * fails at least one of these.
 */
class PredictionGateTest {

    private val ms = 1_000_000L

    @Test
    fun `the first observation is the estimate, not a blend against zero`() {
        val gate = PredictionGate()
        gate.observe(10f)
        // 10, not 1. Blending the first miss against an initial zero would
        // report a tenth of the real error and delay the disable by several
        // frames — at the start of a fast stroke, which is exactly when a bad
        // predictor is most visible.
        assertEquals(10f, gate.error, 1e-4f)
    }

    @Test
    fun `later observations decay toward the new value`() {
        val gate = PredictionGate()
        gate.observe(10f)
        gate.observe(0f)
        assertEquals(9f, gate.error, 1e-4f, "err = 0.9*10 + 0.1*0")
        gate.observe(0f)
        assertEquals(8.1f, gate.error, 1e-4f, "and it keeps decaying rather than sticking")
    }

    @Test
    fun `the threshold is strict, and passing it disables the tail`() {
        val atThreshold = PredictionGate()
        atThreshold.observe(PredictionGate.DISABLE_PX)
        assertTrue(
            atThreshold.enabled,
            "12 px exactly is the limit, not past it — err > DISABLE_PX",
        )

        val past = PredictionGate()
        past.observe(PredictionGate.DISABLE_PX + 0.5f)
        assertFalse(past.enabled, "past the limit the tail stops")
    }

    @Test
    fun `the disable latches for the rest of the stroke`() {
        val gate = PredictionGate()
        gate.observe(100f)
        assertFalse(gate.enabled)
        // A hundred perfect predictions in a row do not bring it back. §8 says
        // "for the rest of the stroke": a tail that flickered on and off as the
        // estimate crossed the threshold would be more distracting than either
        // state, and the estimate crosses back within a few frames — 100 px
        // decays under 12 in twenty-one observations of zero (100 x 0.9^20 is
        // 12.16, still above it; 0.9^21 is the first below).
        repeat(100) { gate.observe(0f) }
        assertTrue(gate.error < PredictionGate.DISABLE_PX, "the estimate did come back down")
        assertFalse(gate.enabled, "but the tail did not")
    }

    @Test
    fun `reset is what re-enables it, and it clears the estimate too`() {
        val gate = PredictionGate()
        gate.observe(100f)
        assertFalse(gate.enabled)
        gate.reset()
        assertTrue(gate.enabled, "§8: re-enabled at the next pen-down")
        assertEquals(0f, gate.error, 1e-4f)
        // And the *seeding* is reset with it, not just the number: carrying the
        // previous stroke's seeded state would blend the new stroke's first
        // miss against a zero, which is the bug the first test above pins.
        gate.observe(10f)
        assertEquals(10f, gate.error, 1e-4f)
    }

    @Test
    fun `reset drops a prediction that is still waiting to be scored`() {
        // The common case at pen-down, not an edge one: a tail is predicted
        // ahead of the pen every frame, so a prediction is almost always still
        // pending when the stroke ends. Carried into the next stroke it would
        // be interpolated against that stroke's first segment — an arbitrary
        // distance between two unrelated strokes, folded in as the seed
        // observation, which is where a wrong disable is most visible.
        val gate = PredictionGate()
        gate.actual(0f, 0f, 0L)
        gate.predicted(10f, 0f, 100 * ms)
        assertTrue(gate.hasPending, "the pen has not reached the predicted instant yet")
        gate.reset()
        assertFalse(gate.hasPending)
        // And the sample that would have scored it does not: the estimate is
        // still unseeded, so this seeds rather than blends.
        gate.actual(999f, 0f, 200 * ms)
        gate.observe(4f)
        assertEquals(4f, gate.error, 1e-4f, "a stale prediction must not have been scored")
    }

    @Test
    fun `a non-finite or negative miss cannot poison the estimate`() {
        val gate = PredictionGate()
        gate.observe(Float.NaN)
        gate.observe(Float.POSITIVE_INFINITY)
        gate.observe(-1f)
        assertTrue(gate.enabled, "a NaN compares false against the threshold, then infects every later value")
        assertEquals(0f, gate.error, 1e-4f)
        // Still unseeded: if any of the three had been folded in, this would
        // blend rather than seed and land somewhere other than 10.
        gate.observe(10f)
        assertEquals(10f, gate.error, 1e-4f)
    }

    // ------------------------------------------------------- interpolation

    @Test
    fun `the pen is interpolated to the predicted instant, not sampled at the nearest`() {
        val gate = PredictionGate()
        // Predicting (10,0) at t = 100 ms, and the pen runs 0 -> 20 over
        // 0..200 ms: at 100 ms it is at exactly (10,0), so the prediction was
        // perfect and the error is zero.
        gate.actual(0f, 0f, 0L)
        gate.predicted(10f, 0f, 100 * ms)
        gate.actual(20f, 0f, 200 * ms)
        assertFalse(gate.hasPending, "the pen passed the predicted instant, so it was scored")
        // Comparing against the nearest real sample instead — (20,0) — would
        // report 10 px of error for a prediction that was exactly right, and at
        // 240 Hz on a brisk stroke that alone trips the 12 px threshold.
        assertEquals(0f, gate.error, 1e-3f)
    }

    @Test
    fun `a genuinely wrong prediction is measured at its own instant`() {
        val gate = PredictionGate()
        gate.actual(0f, 0f, 0L)
        gate.predicted(10f, 0f, 100 * ms)
        // The pen did not move at all: at 100 ms it is still at the origin, so
        // the prediction missed by the full 10 px.
        gate.actual(0f, 0f, 200 * ms)
        assertEquals(10f, gate.error, 1e-3f)
    }

    @Test
    fun `a prediction is not scored before the pen reaches its instant`() {
        val gate = PredictionGate()
        gate.actual(0f, 0f, 0L)
        gate.predicted(50f, 0f, 100 * ms)
        gate.actual(1f, 0f, 50 * ms)
        assertTrue(gate.hasPending, "the pen is only halfway to the predicted time")
        assertEquals(0f, gate.error, 1e-4f, "and nothing has been scored, so the estimate is untouched")
        gate.actual(2f, 0f, 150 * ms)
        assertFalse(gate.hasPending)
        // Interpolated at 100 ms between (1,0)@50 and (2,0)@150: x = 1.5.
        assertEquals(48.5f, gate.error, 1e-3f)
    }

    @Test
    fun `the oldest pending prediction wins, and is scored once`() {
        val gate = PredictionGate()
        gate.actual(0f, 0f, 0L)
        gate.predicted(5f, 0f, 100 * ms)
        // A second tail arrives a frame later while the first is still waiting.
        // Overwriting would keep replacing the prediction just before the pen
        // got to it, so the estimate would sit at zero forever — a broken
        // predictor indistinguishable from a perfect one.
        gate.predicted(500f, 0f, 116 * ms)
        gate.actual(0f, 0f, 200 * ms)
        assertEquals(5f, gate.error, 1e-3f, "the 500 px guess must not be the one scored")
        // And it is not scored a second time: the next sample leaves the
        // estimate exactly where it was.
        assertFalse(gate.hasPending)
        gate.actual(0f, 0f, 300 * ms)
        assertEquals(5f, gate.error, 1e-3f)
    }

    @Test
    fun `a prediction at or before the last real sample is refused rather than left stuck`() {
        val gate = PredictionGate()
        gate.actual(0f, 0f, 100 * ms)
        gate.predicted(99f, 0f, 100 * ms)
        assertFalse(
            gate.hasPending,
            "a prediction the pen has already passed can never be interpolated to, " +
                "and holding the slot would block every later one",
        )
        gate.predicted(1f, 0f, 200 * ms)
        assertTrue(gate.hasPending, "so the next real prediction still gets the slot")
    }

    @Test
    fun `two samples at one instant use the newer position`() {
        // Routine, not exotic: a device may stamp a whole historical run with
        // the batch's event time, so `timeNs` is non-decreasing rather than
        // strictly increasing (StrokeInput's own KDoc). Reached here the way it
        // is reached in practice — a prediction recorded before the stroke's
        // first real sample, so the pen arrives already past its instant.
        val gate = PredictionGate()
        gate.predicted(10f, 0f, 150 * ms)
        gate.actual(4f, 0f, 150 * ms)
        assertTrue(gate.hasPending, "the first real sample has nothing to interpolate from")
        gate.actual(7f, 0f, 150 * ms)
        // Both samples carry one instant, so there is no segment to walk along
        // and the newer position is where the pen is. Taking the older one
        // instead would report 6 px for a 3 px miss.
        assertEquals(3f, gate.error, 1e-3f)
    }

    @Test
    fun `the first real sample of a stroke has nothing to interpolate from`() {
        val gate = PredictionGate()
        gate.predicted(10f, 0f, 100 * ms)
        gate.actual(0f, 0f, 200 * ms)
        // Scoring against a single point would mean scoring against an origin
        // the pen was never at. The prediction simply waits; in practice the
        // handler resets at pen-down and predicts after the first move.
        assertTrue(gate.hasPending)
        assertEquals(0f, gate.error, 1e-4f)
    }

    @Test
    fun `actual reports whether it scored, and publishes the pair it compared`() {
        // The overlay's feed (§8). The *actual* point is interpolated to the
        // predicted instant, so publishing it from here is what stops the
        // overlay from drawing points that disagree with the error printed
        // beside them.
        val gate = PredictionGate()
        assertFalse(gate.actual(0f, 0f, 0L), "the first sample has no prediction to score")
        gate.predicted(10f, 0f, 100 * ms)
        assertFalse(gate.actual(1f, 0f, 50 * ms), "the pen has not reached the instant yet")
        assertTrue(gate.actual(3f, 0f, 150 * ms), "now it has")

        assertEquals(10f, gate.scoredPredictedX, 1e-5f)
        assertEquals(0f, gate.scoredPredictedY, 1e-5f)
        // Interpolated at 100 ms between (1,0)@50 and (3,0)@150: exactly 2.
        assertEquals(2f, gate.scoredActualX, 1e-5f, "the pair must carry the INTERPOLATED point")
        assertEquals(0f, gate.scoredActualY, 1e-5f)
        // And it agrees with the error the same call folded in.
        assertEquals(8f, gate.error, 1e-4f)

        assertFalse(gate.actual(4f, 0f, 200 * ms), "nothing pending, so nothing scored")
    }

    // --------------------------------------------------------- truncation

    @Test
    fun `keepCount truncates at the first sample past the lookahead`() {
        val gate = PredictionGate()
        val ages = longArrayOf(1 * ms, PredictionGate.MAX_LOOKAHEAD_NS, PredictionGate.MAX_LOOKAHEAD_NS + 1, 2 * ms)
        assertEquals(
            2, gate.keepCount(ages.size) { ages[it] },
            "the lookahead itself is kept and the nanosecond past it is not",
        )
        // The fourth sample is young, and it is still dropped: this truncates a
        // run rather than filtering one. §8 drops "predicted historical samples
        // beyond" the limit — keeping a later sample after cutting an earlier
        // one would leave a gap in the middle of the tail.
    }

    @Test
    fun `keepCount keeps a whole tail inside the lookahead, and nothing of an empty one`() {
        val gate = PredictionGate()
        val ages = longArrayOf(0L, 4 * ms, 8 * ms)
        assertEquals(3, gate.keepCount(ages.size) { ages[it] })
        assertEquals(0, gate.keepCount(0) { 0L })
        val allTooOld = longArrayOf(PredictionGate.MAX_LOOKAHEAD_NS + 1)
        assertEquals(0, gate.keepCount(1) { allTooOld[it] }, "a tail entirely past the limit is no tail")
    }
}
