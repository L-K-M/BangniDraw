package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckpointRetryPolicyTest {

    @Test
    fun `a completed checkpoint needs no retry`() {
        assertNull(CheckpointRetryPolicy.delayMs(CheckpointResult.COMPLETE))
    }

    @Test
    fun `pending readback retries promptly`() {
        assertEquals(
            CheckpointRetryPolicy.READBACK_RETRY_MS,
            CheckpointRetryPolicy.delayMs(CheckpointResult.READBACK_PENDING),
        )
    }

    @Test
    fun `storage failure retries on the autosave clock`() {
        assertEquals(
            AutosavePolicy.QUIET_MS,
            CheckpointRetryPolicy.delayMs(CheckpointResult.STORAGE_PENDING),
        )
    }
}
