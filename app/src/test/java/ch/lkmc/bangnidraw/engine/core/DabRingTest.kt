package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The main → GL handoff (`docs/plan/02-architecture.md` §3.2). Identity
 * matters here: graphics-core holds the published batch references until it
 * has replayed them on the GL thread, so a slot reused early repaints a
 * stroke with the next one's dabs.
 */
class DabRingTest {

    @Test
    fun `a batch stores the eight per-dab fields and reads them back`() {
        val batch = DabBatch(capacity = 4)
        assertTrue(batch.add(1f, 2f, 3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f))
        assertEquals(1, batch.count)
        assertEquals(Dab(1f, 2f, 3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f), batch[0])
        // The stride is what the GL side uploads; if a field were dropped here
        // the shader would read a neighbour's value for it.
        assertEquals(PerfConstants.DAB_STRIDE, 8, "the batch's field count is the stride")
    }

    @Test
    fun `a full batch reports it rather than throwing or overwriting`() {
        // A full batch is the normal rhythm of a long stroke: the producer
        // publishes and takes the next slot. Silently overwriting dab 0 would
        // lose the start of every long stroke.
        val batch = DabBatch(capacity = 2)
        assertTrue(batch.add(0f, 0f, 1f, 1f, 1f, 0f, 1f, 0f))
        assertTrue(batch.add(1f, 1f, 1f, 1f, 1f, 0f, 1f, 0f))
        assertTrue(batch.isFull)
        assertTrue(!batch.add(2f, 2f, 1f, 1f, 1f, 0f, 1f, 0f), "a full batch must refuse")
        assertEquals(2, batch.count, "a refused add must not have counted")
        assertEquals(0f, batch[0].x, "the first dab must survive a refused add")
    }

    @Test
    fun `the dirty rect is the union of the dabs' own rects`() {
        val batch = DabBatch(capacity = 8)
        assertEquals(IntRect.EMPTY, batch.dirty, "an empty batch dirties nothing")
        batch.add(100f, 100f, 4f, 1f, 1f, 0f, 1f, 0f)
        val single = batch.dirty
        assertEquals(IntRect.forDab(100f, 100f, 4f), single)
        batch.add(200f, 50f, 8f, 1f, 1f, 0f, 1f, 0f)
        val both = batch.dirty
        val expected = IntRect.forDab(200f, 50f, 8f)
        assertTrue(both.left <= minOf(single.left, expected.left), "union lost the left edge")
        assertTrue(both.top <= minOf(single.top, expected.top), "union lost the top edge")
        assertTrue(both.right >= maxOf(single.right, expected.right), "union lost the right edge")
        assertTrue(
            both.bottom >= maxOf(single.bottom, expected.bottom),
            "union lost the bottom edge",
        )
    }

    @Test
    fun `clearing resets the count, the tail marker and the dirty rect`() {
        val batch = DabBatch(capacity = 4)
        batch.add(10f, 10f, 2f, 1f, 1f, 0f, 1f, 0f)
        batch.markPredictedFromHere()
        batch.add(20f, 20f, 2f, 1f, 1f, 0f, 1f, 0f)
        batch.strokeId = 7L
        batch.clear()
        assertEquals(0, batch.count)
        assertEquals(-1, batch.predictedFrom, "a reused slot must not inherit a tail marker")
        assertEquals(0L, batch.strokeId, "a reused slot must not inherit a stroke id")
        assertEquals(IntRect.EMPTY, batch.dirty, "a reused slot must not inherit a dirty rect")
    }

    @Test
    fun `the committed count excludes the predicted tail`() {
        // This is how the tail is removable without a second data path: the
        // multi-buffered pass ignores everything from `predictedFrom` on.
        val batch = DabBatch(capacity = 8)
        repeat(3) { batch.add(it.toFloat(), 0f, 1f, 1f, 1f, 0f, 1f, 0f) }
        assertEquals(3, batch.committedCount, "with no tail, everything commits")
        batch.markPredictedFromHere()
        repeat(2) { batch.add(10f + it, 0f, 1f, 1f, 1f, 0f, 1f, 0f) }
        assertEquals(5, batch.count)
        assertEquals(3, batch.committedCount, "the tail must not commit")
    }

    @Test
    fun `marking the tail twice keeps the first mark`() {
        // A second mark would move the boundary later and commit predicted
        // dabs into the stroke buffer, which is the one thing the tail must
        // never do.
        val batch = DabBatch(capacity = 8)
        batch.add(0f, 0f, 1f, 1f, 1f, 0f, 1f, 0f)
        batch.markPredictedFromHere()
        batch.add(1f, 0f, 1f, 1f, 1f, 0f, 1f, 0f)
        batch.markPredictedFromHere()
        assertEquals(1, batch.predictedFrom)
        assertEquals(1, batch.committedCount)
    }

    @Test
    fun `reading past the count is refused`() {
        val batch = DabBatch(capacity = 4)
        batch.add(0f, 0f, 1f, 1f, 1f, 0f, 1f, 0f)
        assertFailsWith<IllegalArgumentException> { batch[1] }
        assertFailsWith<IllegalArgumentException> { batch[-1] }
    }

    @Test
    fun `the ring hands out every slot once and then reports backpressure`() {
        val ring = DabRing(slots = 3, capacity = 4)
        assertEquals(3, ring.freeSlots)
        val held = List(3) { assertNotNull(ring.acquire(), "slot $it should have been free") }
        assertEquals(0, ring.freeSlots)
        assertNull(ring.acquire(), "a full ring must report backpressure, not allocate")
        // Distinct objects: identity is what graphics-core holds on to.
        assertEquals(3, held.distinct().size, "the ring handed out the same slot twice")
        ring.release(held[1])
        assertSame(held[1], ring.acquire(), "the released slot should come back")
    }

    @Test
    fun `an acquired slot arrives clean`() {
        val ring = DabRing(slots = 1, capacity = 4)
        val first = assertNotNull(ring.acquire())
        first.add(5f, 5f, 2f, 1f, 1f, 0f, 1f, 0f)
        first.strokeId = 42L
        ring.release(first)
        val again = assertNotNull(ring.acquire())
        assertSame(first, again, "the premise: a one-slot ring reuses its slot")
        assertEquals(0, again.count, "a reused slot still held the last stroke's dabs")
        assertEquals(0L, again.strokeId)
    }

    @Test
    fun `a double release is refused`() {
        // Not idempotent on purpose: a double release hands one slot to two
        // producers, and the resulting stroke interleaves two strokes' dabs —
        // a corruption far more expensive to find than this throw.
        val ring = DabRing(slots = 2, capacity = 4)
        val batch = assertNotNull(ring.acquire())
        ring.release(batch)
        assertFailsWith<IllegalArgumentException> { ring.release(batch) }
    }

    @Test
    fun `releasing a foreign batch is refused`() {
        val ring = DabRing(slots = 2, capacity = 4)
        assertFailsWith<IllegalArgumentException> { ring.release(DabBatch(capacity = 4)) }
    }

    @Test
    fun `cancelling a stroke frees every slot it held`() {
        // Palm rejection drops the active segment without ever replaying it.
        // Without this, each rejection leaks a stroke's worth of slots and the
        // ring starves after a few.
        val ring = DabRing(slots = 4, capacity = 4)
        val cancelled = ring.newStrokeId()
        val kept = ring.newStrokeId()
        val a = assertNotNull(ring.acquire()).also { it.strokeId = cancelled }
        assertNotNull(ring.acquire()).also { it.strokeId = cancelled }
        val c = assertNotNull(ring.acquire()).also { it.strokeId = kept }
        assertEquals(1, ring.freeSlots)

        ring.releaseStroke(cancelled)
        assertEquals(3, ring.freeSlots, "both cancelled slots should be back")
        // And the surviving stroke's slot is untouched.
        assertFailsWith<IllegalArgumentException> { ring.release(a) }
        ring.release(c)
        assertEquals(4, ring.freeSlots)
    }

    @Test
    fun `stroke ids are monotonic`() {
        // A late batch has to be recognisable as belonging to a finished
        // stroke; reusing ids would make that impossible.
        val ring = DabRing(slots = 2, capacity = 2)
        val ids = List(100) { ring.newStrokeId() }
        assertEquals(ids.size, ids.distinct().size, "a stroke id was reissued")
        for (i in 1 until ids.size) {
            assertTrue(ids[i] > ids[i - 1], "stroke ids went backwards at $i")
        }
    }

    @Test
    fun `degenerate sizes are refused`() {
        assertFailsWith<IllegalArgumentException> { DabBatch(capacity = 0) }
        assertFailsWith<IllegalArgumentException> { DabBatch(capacity = -1) }
        assertFailsWith<IllegalArgumentException> { DabRing(slots = 0) }
        assertFailsWith<IllegalArgumentException> { DabRing(slots = 2, capacity = 0) }
    }

    @Test
    fun `the ring's defaults are the pinned performance constants`() {
        // `10-performance.md` §4 sizes the ring for a full stroke at 120 Hz.
        // If the defaults drifted from the constants, the budget table would
        // describe a ring the engine does not build.
        val ring = DabRing()
        assertEquals(PerfConstants.DAB_RING_SLOTS, ring.slots)
        assertEquals(PerfConstants.DAB_BATCH_CAPACITY, DabBatch().capacity)
        assertEquals(
            PerfConstants.DAB_RING_CAPACITY,
            PerfConstants.DAB_RING_SLOTS * PerfConstants.DAB_BATCH_CAPACITY,
        )
    }
}
