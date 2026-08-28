package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckpointGenerationTest {

    @Test
    fun `a concurrent edit makes a checkpoint stale`() {
        val generation = CheckpointGeneration()
        val checkpoint = generation.capture()

        generation.noteChange()

        assertEquals(
            CheckpointFreshness.STALE,
            generation.freshness(checkpoint),
        )
    }

    @Test
    fun `an unchanged checkpoint may clear dirty state`() {
        val generation = CheckpointGeneration()
        generation.noteChange()

        val checkpoint = generation.capture()

        assertEquals(
            CheckpointFreshness.CURRENT,
            generation.freshness(checkpoint),
        )
    }
}
