package ch.lkmc.bangnidraw.ui.canvas

import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.HistoryJournal
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.TileKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasActionGateTest {

    private val layerId = LayerId("layer-a")

    private fun entry(seq: Long): HistoryEntry =
        HistoryEntry.Stroke(
            activeBefore = layerId,
            activeAfter = layerId,
            layerId = layerId,
            tiles = listOf(TileKey(0, 0)),
        ).stamp(seq = seq, timestamp = seq, bytes = 10L)

    @Test
    fun `committed checkpoint can run during a live stroke`() {
        val gate = CanvasActionGate()
        gate.beginStroke()

        assertTrue(gate.beginCommittedCheckpoint())
        assertTrue(gate.busy)
    }

    @Test
    fun `committed checkpoint waits for history and document work`() {
        val historyGate = CanvasActionGate()
        historyGate.beginStroke()
        historyGate.endStrokeInput()

        assertFalse(historyGate.beginCommittedCheckpoint())

        val busyGate = CanvasActionGate()
        busyGate.beginWork()

        assertFalse(busyGate.beginCommittedCheckpoint())
    }

    @Test
    fun `idle work waits for stroke history completion`() {
        val gate = CanvasActionGate()

        assertTrue(gate.idleWorkReady)
        gate.beginStroke()
        assertFalse(gate.idleWorkReady)

        gate.endStrokeInput()
        assertFalse(gate.idleWorkReady, "pen-up has not made the new stroke undoable")

        gate.completeStroke()
        assertTrue(gate.idleWorkReady)
    }

    @Test
    fun `actions during a stroke wait and retain FIFO order`() {
        val gate = CanvasActionGate()
        gate.beginStroke()

        assertEquals(CanvasActionDecision.Parked, gate.request(CanvasDocumentAction.Undo))
        assertEquals(CanvasActionDecision.Parked, gate.request(CanvasDocumentAction.Redo))
        assertEquals(2, gate.pendingCount)

        assertNull(gate.endStrokeInput())
        assertNull(gate.next(), "pen-up is not history completion")
        assertFalse(gate.beginStroke(), "the committing stroke still owns the gate")

        assertEquals(CanvasDocumentAction.Undo, gate.completeStroke())
        assertEquals(CanvasDocumentAction.Redo, gate.next())
        assertNull(gate.next())
    }

    @Test
    fun `an empty stroke completion still releases parked actions`() {
        val gate = CanvasActionGate()
        gate.beginStroke()
        gate.request(CanvasDocumentAction.Undo)

        assertNull(gate.endStrokeInput())

        // A no-op merge still reports terminal completion.
        assertEquals(CanvasDocumentAction.Undo, gate.completeStroke())
    }

    @Test
    fun `an asynchronous edit holds later actions until completion`() {
        val gate = CanvasActionGate()
        val first = assertIs<CanvasActionDecision.Run>(
            gate.request(CanvasDocumentAction.AddLayer),
        )
        assertEquals(CanvasDocumentAction.AddLayer, first.action)
        gate.beginWork()

        assertEquals(CanvasActionDecision.Parked, gate.request(CanvasDocumentAction.Undo))
        assertTrue(gate.busy)
        assertEquals(CanvasDocumentAction.Undo, gate.finishWork())
    }

    @Test
    fun `a stroke cannot start during document work`() {
        val gate = CanvasActionGate()
        gate.beginWork()

        assertFalse(gate.beginStroke())
        assertFalse(gate.strokeInFlight)
    }

    @Test
    fun `session streaming blocks input and document actions`() {
        val gate = CanvasActionGate()
        gate.beginSessionSync()

        assertTrue(gate.busy)
        assertFalse(gate.beginStroke())
        assertEquals(CanvasActionDecision.Parked, gate.request(CanvasDocumentAction.Undo))
        assertEquals(CanvasDocumentAction.Undo, gate.finishSessionSync())
    }

    @Test
    fun `RMW cancel restore keeps parked actions behind the stroke`() {
        val gate = CanvasActionGate()
        gate.beginStroke()
        gate.request(CanvasDocumentAction.Undo)
        gate.beginWork()

        assertNull(gate.endStrokeInput())
        assertNull(gate.completeStroke())
        assertFalse(gate.beginStroke())
        assertEquals(CanvasDocumentAction.Undo, gate.finishWork())
    }

    @Test
    fun `queued undo runs after a delayed stroke entry is pushed`() {
        val journal = HistoryJournal(HistoryJournal.Limits(maxEntries = 100, maxBytes = 1_000L))
        journal.push(entry(1))
        val gate = CanvasActionGate()
        gate.beginStroke()

        assertNull(gate.endStrokeInput())
        assertEquals(CanvasActionDecision.Parked, gate.request(CanvasDocumentAction.Undo))
        assertFalse(gate.beginStroke())
        journal.push(entry(2))
        assertEquals(CanvasDocumentAction.Undo, gate.completeStroke())
        journal.undo()

        assertEquals(listOf(1L, 2L), journal.entries.map { it.seq })
        assertEquals(1, journal.cursor)
    }

    @Test
    fun `fill result before pen-up releases its stroke once`() {
        val gate = CanvasActionGate()
        gate.beginStroke()
        gate.request(CanvasDocumentAction.Undo)

        assertNull(gate.completeStroke())
        assertNull(gate.completeStroke())
        assertEquals(CanvasDocumentAction.Undo, gate.endStrokeInput())
        assertNull(gate.endStrokeInput())
        assertNull(gate.completeStroke())
    }

    @Test
    fun `leave waits for stroke history and becomes terminal`() {
        val gate = CanvasActionGate()
        gate.beginStroke()

        assertEquals(
            CanvasActionDecision.Parked,
            gate.request(CanvasDocumentAction.Leave),
        )
        assertEquals(
            CanvasActionDecision.Rejected,
            gate.request(CanvasDocumentAction.Leave),
        )
        assertEquals(CanvasActionDecision.Rejected, gate.request(CanvasDocumentAction.Undo))
        assertEquals(1, gate.pendingCount)

        assertNull(gate.endStrokeInput())
        assertEquals(CanvasDocumentAction.Leave, gate.completeStroke())
        assertNull(gate.next())
        assertEquals(false, gate.beginStroke())
    }

    @Test
    fun `leave survives stroke completion before pen-up`() {
        val gate = CanvasActionGate()
        gate.beginStroke()
        gate.request(CanvasDocumentAction.Leave)

        assertNull(gate.completeStroke())
        assertEquals(CanvasDocumentAction.Leave, gate.endStrokeInput())
        assertNull(gate.next())
    }

    @Test
    fun `failed leave reopens the action gate`() {
        val gate = CanvasActionGate()
        assertIs<CanvasActionDecision.Run>(gate.request(CanvasDocumentAction.Leave))
        gate.beginWork()

        gate.finishLeave()

        assertTrue(gate.beginStroke())
    }

    @Test
    fun `leave during a stroke waits for history and earlier actions`() {
        val gate = CanvasActionGate()
        gate.beginStroke()
        gate.request(CanvasDocumentAction.Undo)
        gate.request(CanvasDocumentAction.Leave)

        assertNull(gate.endStrokeInput())
        assertFalse(gate.beginStroke(), "pending leave forbids another stroke")

        assertEquals(CanvasDocumentAction.Undo, gate.completeStroke())
        assertFalse(gate.beginStroke(), "queued leave forbids another stroke")
        gate.beginWork()
        assertNull(gate.next(), "leave remains behind the running undo")
        assertEquals(CanvasDocumentAction.Leave, gate.finishWork())
    }

    @Test
    fun `leave during document work waits and prevents another stroke`() {
        val gate = CanvasActionGate()
        gate.beginWork()
        gate.request(CanvasDocumentAction.Undo)
        gate.request(CanvasDocumentAction.Leave)

        assertFalse(gate.beginStroke())
        assertEquals(CanvasDocumentAction.Undo, gate.finishWork())

        assertFalse(gate.beginStroke(), "queued leave keeps the canvas closed to input")
        gate.beginWork()
        assertEquals(CanvasDocumentAction.Leave, gate.finishWork())
    }
}
