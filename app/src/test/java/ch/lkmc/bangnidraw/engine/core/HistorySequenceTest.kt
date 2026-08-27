package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HistorySequenceTest {

    @Test
    fun `observing a sequence does not consume it`() {
        val sequence = HistorySequence(7L)

        assertEquals(7L, sequence.observe())
        assertEquals(7L, sequence.observe(), "failed admission keeps the candidate")
    }

    @Test
    fun `successful admission commits exactly one sequence`() {
        val sequence = HistorySequence(7L)
        val candidate = sequence.observe()

        sequence.commit(candidate)

        assertEquals(8L, sequence.observe())
    }

    @Test
    fun `a gap or stale commit is refused`() {
        val sequence = HistorySequence(7L)

        assertFailsWith<IllegalStateException> { sequence.commit(8L) }
        assertEquals(7L, sequence.observe(), "a refused gap cannot advance the journal")

        sequence.commit(7L)
        assertFailsWith<IllegalStateException> { sequence.commit(7L) }
        assertEquals(8L, sequence.observe(), "a stale retry cannot consume another sequence")
    }
}
