package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatestWriteTrackerTest {

    @Test
    fun `only the newest issued write remains current`() {
        val tracker = LatestWriteTracker<String>()
        val stale = tracker.issue("palette")
        val current = tracker.issue("palette")

        assertFalse(tracker.isCurrent("palette", stale))
        assertTrue(tracker.isCurrent("palette", current))

        tracker.complete("palette", stale)
        assertTrue(tracker.isCurrent("palette", current))

        tracker.complete("palette", current)
        assertFalse(tracker.isCurrent("palette", current))
    }
}
