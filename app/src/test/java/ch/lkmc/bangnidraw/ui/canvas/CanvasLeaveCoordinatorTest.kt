package ch.lkmc.bangnidraw.ui.canvas

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CanvasLeaveCoordinatorTest {

    @Test
    fun `navigation runs once when the checkpoint throws`() = runBlocking {
        val events = mutableListOf<Event>()
        val failure = IllegalStateException("checkpoint failed")

        CanvasLeaveCoordinator.run(
            checkpoint = {
                events += Event.CHECKPOINT
                throw failure
            },
            onCheckpointFailure = {
                assertSame(failure, it)
                events += Event.FAILURE_REPORTED
            },
            navigate = { events += Event.NAVIGATE },
        )

        assertEquals(
            listOf(Event.CHECKPOINT, Event.FAILURE_REPORTED, Event.NAVIGATE),
            events,
        )
    }

    private enum class Event {
        CHECKPOINT,
        FAILURE_REPORTED,
        NAVIGATE,
    }
}
