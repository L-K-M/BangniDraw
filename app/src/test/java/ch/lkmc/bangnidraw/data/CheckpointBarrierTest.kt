package ch.lkmc.bangnidraw.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CheckpointBarrierTest {

    @Test
    fun `a failed tile flush cannot reach the project commit`() = runBlocking {
        var projectCommitted = false

        val result = CheckpointBarrier.commitWhenFlushed(
            awaitReadbacks = { TileFlusher.ReadbackResult.COMPLETE },
            flushTiles = { false },
            commitProject = { projectCommitted = true },
        )

        assertEquals(CheckpointResult.DEFERRED, result)
        assertFalse(projectCommitted)
    }

    @Test
    fun `a pending readback cannot flush or commit`() = runBlocking {
        var tilesFlushed = false
        var projectCommitted = false

        val result = CheckpointBarrier.commitWhenFlushed(
            awaitReadbacks = { TileFlusher.ReadbackResult.PENDING },
            flushTiles = {
                tilesFlushed = true
                true
            },
            commitProject = { projectCommitted = true },
        )

        assertEquals(CheckpointResult.DEFERRED, result)
        assertFalse(tilesFlushed)
        assertFalse(projectCommitted)
    }

    @Test
    fun `a successful drain reaches the project commit`() = runBlocking {
        var tilesFlushed = false
        var projectCommitted = false

        val result = CheckpointBarrier.commitWhenFlushed(
            awaitReadbacks = { TileFlusher.ReadbackResult.COMPLETE },
            flushTiles = {
                tilesFlushed = true
                true
            },
            commitProject = { projectCommitted = true },
        )

        assertEquals(CheckpointResult.COMMITTED, result)
        assertTrue(tilesFlushed)
        assertTrue(projectCommitted)
    }
}
