package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadbackPolicyTest {

    @Test
    fun `a stroke commits only after prior readbacks drain`() {
        assertEquals(StrokeCommitDecision.COMMIT, ReadbackPolicy.strokeCommit(0))
        assertEquals(StrokeCommitDecision.CANCEL, ReadbackPolicy.strokeCommit(1))
    }

    @Test
    fun `drain status reflects remaining chunks`() {
        assertEquals(ReadbackDrainResult.COMPLETE, ReadbackPolicy.drainResult(0))
        assertEquals(ReadbackDrainResult.PENDING, ReadbackPolicy.drainResult(2))
    }
}
