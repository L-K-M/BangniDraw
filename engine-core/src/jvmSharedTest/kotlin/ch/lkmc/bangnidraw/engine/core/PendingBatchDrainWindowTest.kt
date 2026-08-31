package ch.lkmc.bangnidraw.engine.core

import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingBatchDrainWindowTest {

    @Test
    fun `a frame leaves arrivals for the next frame`() {
        val pending = ArrayDeque(listOf("A"))
        val consumed = mutableListOf<String>()
        val window = PendingBatchDrainWindow()

        window.begin(PendingBatchDrainScope.FRAME_SNAPSHOT, pending.size)
        while (window.canPoll()) {
            val next = pending.poll() ?: break
            consumed += next
            if (next == "A") pending += "B"
        }

        assertEquals(listOf("A"), consumed)
        assertEquals(listOf("B"), pending.toList())

        window.begin(PendingBatchDrainScope.FRAME_SNAPSHOT, pending.size)
        while (window.canPoll()) pending.poll()?.let(consumed::add) ?: break

        assertEquals(listOf("A", "B"), consumed)
        assertEquals(emptyList(), pending.toList())
    }

    @Test
    fun `an exhaustive drain consumes arrivals`() {
        val pending = ArrayDeque(listOf("A"))
        val consumed = mutableListOf<String>()
        val window = PendingBatchDrainWindow()

        window.begin(PendingBatchDrainScope.EXHAUSTIVE, pending.size)
        while (window.canPoll()) {
            val next = pending.poll() ?: break
            consumed += next
            if (next == "A") pending += "B"
        }

        assertEquals(listOf("A", "B"), consumed)
        assertEquals(emptyList(), pending.toList())
    }

    @Test
    fun `a frame never polls beyond its initial backlog`() {
        val window = PendingBatchDrainWindow()

        window.begin(PendingBatchDrainScope.FRAME_SNAPSHOT, pendingAtStart = 3)

        assertEquals(listOf(true, true, true, false), List(4) { window.canPoll() })
    }
}
