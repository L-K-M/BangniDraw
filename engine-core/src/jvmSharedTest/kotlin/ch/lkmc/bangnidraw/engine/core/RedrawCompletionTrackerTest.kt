package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedrawCompletionTrackerTest {

    @Test
    fun `current generation finishes each callback once`() {
        val tracker = RedrawCompletionTracker()
        var completions = 0
        tracker.beginGeneration(FIRST_GENERATION)
        tracker.queue(FIRST_GENERATION, Runnable { completions += 1 })

        tracker.finish(FIRST_GENERATION).forEach(Runnable::run)
        tracker.finish(FIRST_GENERATION).forEach(Runnable::run)

        assertEquals(1, completions)
    }

    @Test
    fun `stale finish preserves current callbacks`() {
        val tracker = RedrawCompletionTracker()
        var staleCompletions = 0
        var currentCompletions = 0
        tracker.beginGeneration(FIRST_GENERATION)
        tracker.queue(FIRST_GENERATION, Runnable { staleCompletions += 1 })

        tracker.beginGeneration(SECOND_GENERATION).forEach(Runnable::run)
        tracker.queue(SECOND_GENERATION, Runnable { currentCompletions += 1 })
        tracker.finish(FIRST_GENERATION).forEach(Runnable::run)

        assertEquals(1, staleCompletions)
        assertEquals(0, currentCompletions)

        tracker.finish(SECOND_GENERATION).forEach(Runnable::run)
        assertEquals(1, currentCompletions)
    }

    @Test
    fun `superseding a generation returns all abandoned callbacks`() {
        val tracker = RedrawCompletionTracker()
        tracker.beginGeneration(FIRST_GENERATION)
        tracker.queue(FIRST_GENERATION, Runnable {})
        tracker.queue(FIRST_GENERATION, Runnable {})

        val abandoned = tracker.beginGeneration(SECOND_GENERATION)

        assertEquals(2, abandoned.size)
        assertTrue(tracker.finish(FIRST_GENERATION).isEmpty())
    }

    @Test
    fun `invalid target abandons only its current generation`() {
        val tracker = RedrawCompletionTracker()
        tracker.beginGeneration(FIRST_GENERATION)
        tracker.queue(FIRST_GENERATION, Runnable {})

        assertEquals(1, tracker.abandon(FIRST_GENERATION).size)
        assertTrue(tracker.abandon(FIRST_GENERATION).isEmpty())
        assertEquals(1, tracker.queue(FIRST_GENERATION, Runnable {}).size)
    }

    @Test
    fun `destroy drains pending callbacks exactly once`() {
        val tracker = RedrawCompletionTracker()
        tracker.beginGeneration(FIRST_GENERATION)
        tracker.queue(FIRST_GENERATION, Runnable {})

        assertEquals(1, tracker.destroy().size)
        assertTrue(tracker.destroy().isEmpty())
    }

    @Test
    fun `release drains and absorbs future callbacks`() {
        val tracker = RedrawCompletionTracker()
        tracker.beginGeneration(FIRST_GENERATION)
        tracker.queue(FIRST_GENERATION, Runnable {})

        assertEquals(1, tracker.release().size)
        assertTrue(tracker.release().isEmpty())
        assertEquals(1, tracker.queue(FIRST_GENERATION, Runnable {}).size)
        assertTrue(tracker.finish(FIRST_GENERATION).isEmpty())
    }

    private companion object {
        const val FIRST_GENERATION = 1L
        const val SECOND_GENERATION = 2L
    }
}
