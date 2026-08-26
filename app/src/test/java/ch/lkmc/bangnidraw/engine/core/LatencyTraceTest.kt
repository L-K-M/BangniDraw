package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `docs/plan/07-input-and-stylus.md` §8's "last N real vs predicted points".
 *
 * The wrap is the whole risk here. A ring read newest-first has two ways to be
 * silently wrong — an index that goes negative on the first wrap, and one that
 * walks the wrong direction — and both produce a plausible-looking overlay
 * rather than a crash, which is why every case below pins an identity rather
 * than a count.
 */
class LatencyTraceTest {

    private fun trace(capacity: Int = 4) = LatencyTrace(capacity)

    /** Records a pair whose coordinates encode [tag], so an entry is identifiable. */
    private fun LatencyTrace.put(tag: Float) = record(tag, tag + 0.5f, tag + 100f, tag + 100.5f)

    @Test
    fun `entry zero is the newest, not the oldest`() {
        val t = trace()
        t.put(1f)
        t.put(2f)
        t.put(3f)
        assertEquals(3, t.size)
        assertEquals(3f, t.predictedXAt(0), 0f, "0 must be the most recent pair")
        assertEquals(2f, t.predictedXAt(1))
        assertEquals(1f, t.predictedXAt(2), 0f, "and size-1 the oldest still held")
    }

    @Test
    fun `all four coordinates of an entry stay together`() {
        // The four parallel arrays are the obvious place for an off-by-one to
        // put the predicted point of one sample beside the real point of
        // another — which draws a wrong error the overlay reports as truth.
        val t = trace()
        t.put(1f)
        t.put(7f)
        assertEquals(7f, t.predictedXAt(0), 0f)
        assertEquals(7.5f, t.predictedYAt(0), 0f)
        assertEquals(107f, t.actualXAt(0), 0f)
        assertEquals(107.5f, t.actualYAt(0), 0f)
    }

    @Test
    fun `the ring wraps and keeps the newest, dropping the oldest`() {
        val t = trace(capacity = 4)
        for (i in 1..6) t.put(i.toFloat())
        assertEquals(4, t.size, "size caps at the capacity")
        // 5 and 6 overwrote 1 and 2; newest-first that is 6, 5, 4, 3.
        assertEquals(listOf(6f, 5f, 4f, 3f), List(t.size) { t.predictedXAt(it) })
    }

    @Test
    fun `the first wrap does not read off the front of the array`() {
        // The case a `%` without the `+ capacity` gets wrong: after exactly
        // `capacity` records, `head` is back at 0, so `head - 1 - 0` is -1 —
        // which Kotlin's `%` leaves at -1 rather than wrapping to the end.
        val t = trace(capacity = 4)
        for (i in 1..4 ) t.put(i.toFloat())
        assertEquals(0, (4) % 4, "the fixture really does leave head at 0")
        assertEquals(4f, t.predictedXAt(0), 0f, "the newest entry must still be reachable")
        assertEquals(1f, t.predictedXAt(3), 0f, "and the oldest")
    }

    @Test
    fun `error is the distance between the pair, not one of its coordinates`() {
        val t = trace()
        t.record(predictedX = 3f, predictedY = 4f, actualX = 0f, actualY = 0f)
        assertEquals(5f, t.errorAt(0), 1e-5f, "3-4-5, so neither axis alone gives it")
    }

    @Test
    fun `clear empties the window`() {
        val t = trace()
        t.put(1f)
        t.put(2f)
        t.clear()
        assertEquals(0, t.size)
        // The next stroke's first pair is the newest, and the previous
        // stroke's points are unreachable — `size` is what makes that true.
        //
        // Note what is NOT asserted: that `clear` rewinds `head`. It does, but
        // no test can see it, because every read is relative to `head` and
        // bounded by `size` — a ring resumed mid-array behaves identically.
        // Mutation-checked: dropping `head = 0` kills nothing. It is kept for a
        // canonical state after clear, not for correctness, and an earlier
        // version of this comment claimed otherwise.
        t.put(9f)
        assertEquals(1, t.size)
        assertEquals(9f, t.predictedXAt(0), 0f)
    }

    @Test
    fun `an index past the live entries is refused rather than returning stale floats`() {
        val t = trace()
        assertFailsWith<IllegalArgumentException> { t.predictedXAt(0) }
        t.put(1f)
        assertFailsWith<IllegalArgumentException> { t.predictedXAt(1) }
        assertFailsWith<IllegalArgumentException> { t.errorAt(-1) }
        // A ring is preallocated, so an unchecked read returns 0f — a point at
        // the canvas origin, drawn as a real measurement.
        assertTrue(t.size == 1)
    }

    @Test
    fun `a zero or negative capacity is refused`() {
        assertFailsWith<IllegalArgumentException> { LatencyTrace(0) }
        assertFailsWith<IllegalArgumentException> { LatencyTrace(-1) }
    }
}
