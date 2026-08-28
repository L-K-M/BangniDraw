package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatestPublicationGateTest {

    @Test
    fun `older delayed result cannot replace the latest result`() {
        val gate = LatestPublicationGate()
        val delayed = gate.nextGeneration()
        val latest = gate.nextGeneration()
        val shelf = mutableListOf<String>()
        val scheduledSyncs = mutableListOf<String>()

        assertTrue(
            gate.publishIfCurrent(latest) {
                shelf += "latest"
                scheduledSyncs += "latest"
            },
        )
        assertFalse(
            gate.publishIfCurrent(delayed) {
                shelf += "delayed"
                scheduledSyncs += "delayed"
            },
        )
        assertEquals(listOf("latest"), shelf)
        assertEquals(listOf("latest"), scheduledSyncs)
    }
}
