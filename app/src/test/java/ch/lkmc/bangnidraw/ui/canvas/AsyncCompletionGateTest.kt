package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncCompletionGateTest {

    @Test
    fun `release cannot complete work after history owns it`() {
        val results = mutableListOf<Int>()
        val gate = AsyncCompletionGate<Int>()
        assertTrue(gate.begin(results::add))
        assertTrue(gate.handOffToHistory())

        assertFalse(gate.complete(AsyncCompletionOwner.ENGINE, -1))
        assertTrue(gate.complete(AsyncCompletionOwner.HISTORY, 7))
        assertEquals(listOf(7), results)
    }

    @Test
    fun `engine completes work that was never handed off`() {
        val results = mutableListOf<Int>()
        val gate = AsyncCompletionGate<Int>()
        assertTrue(gate.begin(results::add))

        assertTrue(gate.complete(AsyncCompletionOwner.ENGINE, 3))
        assertFalse(gate.complete(AsyncCompletionOwner.HISTORY, 4))
        assertEquals(listOf(3), results)
    }
}
