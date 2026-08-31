package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RmwTouchTrackerTest {

    @Test
    fun `each touched tile is captured once`() {
        val tracker = RmwTouchTracker(TileGrid(512, 512))
        val out = IntArray(4)

        assertEquals(
            listOf(TileKey(0, 0), TileKey(1, 0)),
            added(tracker, IntRect(250, 20, 270, 40), out),
        )
        assertEquals(emptyList(), added(tracker, IntRect(255, 22, 260, 35), out))
        assertEquals(listOf(TileKey(1, 1)), added(tracker, IntRect(300, 300, 310, 310), out))
        val count = tracker.all(out)
        assertEquals(
            listOf(TileKey(0, 0), TileKey(1, 0), TileKey(1, 1)),
            List(count) { TileKey(out[it]) },
        )
    }

    @Test
    fun `reset starts a fresh capture`() {
        val tracker = RmwTouchTracker(TileGrid(256, 256))
        val out = IntArray(1)
        val rect = IntRect(1, 1, 2, 2)
        tracker.add(rect, out)

        tracker.reset()

        assertEquals(listOf(TileKey(0, 0)), added(tracker, rect, out))
    }

    private fun added(tracker: RmwTouchTracker, rect: IntRect, out: IntArray): List<TileKey> {
        val count = tracker.add(rect, out)
        return List(count) { TileKey(out[it]) }
    }
}
