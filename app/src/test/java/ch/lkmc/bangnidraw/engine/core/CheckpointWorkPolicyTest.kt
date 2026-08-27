package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckpointWorkPolicyTest {

    @Test
    fun `a clean existing project still reaches gallery sync`() {
        assertEquals(
            CheckpointWork.SYNC_GALLERY,
            CheckpointWorkPolicy.decide(
                CheckpointProjectState.CLEAN_EXISTING,
                CheckpointStrokeState.IDLE,
            ),
        )
    }

    @Test
    fun `dirty and missing projects flush before sync and write`() {
        assertEquals(
            CheckpointWork.FLUSH_THEN_SYNC_AND_WRITE,
            CheckpointWorkPolicy.decide(
                CheckpointProjectState.DIRTY_EXISTING,
                CheckpointStrokeState.IDLE,
            ),
        )
        assertEquals(
            CheckpointWork.FLUSH_THEN_SYNC_AND_WRITE,
            CheckpointWorkPolicy.decide(
                CheckpointProjectState.MISSING,
                CheckpointStrokeState.IDLE,
            ),
        )
    }

    @Test
    fun `a live stroke defers gallery work`() {
        assertEquals(
            CheckpointWork.NONE,
            CheckpointWorkPolicy.decide(
                CheckpointProjectState.CLEAN_EXISTING,
                CheckpointStrokeState.LIVE,
            ),
        )
        assertEquals(
            CheckpointWork.FLUSH_THEN_WRITE,
            CheckpointWorkPolicy.decide(
                CheckpointProjectState.DIRTY_EXISTING,
                CheckpointStrokeState.LIVE,
            ),
        )
        assertEquals(
            CheckpointWork.FLUSH_THEN_WRITE,
            CheckpointWorkPolicy.decide(
                CheckpointProjectState.MISSING,
                CheckpointStrokeState.LIVE,
            ),
        )
    }
}
