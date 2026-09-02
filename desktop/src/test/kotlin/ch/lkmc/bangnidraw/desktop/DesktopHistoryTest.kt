package ch.lkmc.bangnidraw.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHistoryTest {

    @Test
    fun `first entry undoes and redoes without an invalid index`() {
        val history = DesktopHistory<String>(maxSteps = 10, maxBytes = 100) { it.length.toLong() }
        history.record("first")

        assertEquals("first", history.move(HistoryDirection.Undo))
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        assertEquals("first", history.move(HistoryDirection.Redo))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `new edit after undo truncates the redo branch`() {
        val history = DesktopHistory<String>(maxSteps = 10, maxBytes = 100) { it.length.toLong() }
        history.record("one")
        history.record("two")

        assertEquals("two", history.move(HistoryDirection.Undo))
        history.record("three")

        assertNull(history.move(HistoryDirection.Redo))
        assertEquals(listOf("three", "one"), history.undoSequence())
    }

    @Test
    fun `old entries leave when either memory limit is exceeded`() {
        val bySteps = DesktopHistory<String>(maxSteps = 2, maxBytes = 100) { it.length.toLong() }
        bySteps.record("one")
        bySteps.record("two")
        bySteps.record("three")

        assertEquals(listOf("three", "two"), bySteps.undoSequence())

        val byBytes = DesktopHistory<String>(maxSteps = 10, maxBytes = 8) { it.length.toLong() }
        byBytes.record("one")
        byBytes.record("two")
        byBytes.record("three")

        assertEquals(listOf("three", "two"), byBytes.undoSequence())
    }

    private fun DesktopHistory<String>.undoSequence(): List<String> = buildList {
        while (true) add(move(HistoryDirection.Undo) ?: break)
    }
}
