package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasActionGateTest {

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
        historyGate.endStroke(StrokeRelease.AFTER_HISTORY)

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

        gate.endStroke(StrokeRelease.AFTER_HISTORY)
        assertFalse(gate.idleWorkReady, "pen-up has not made the new stroke undoable")

        gate.finishStrokeHistory()
        assertTrue(gate.idleWorkReady)
    }

    @Test
    fun `actions during a stroke wait and retain FIFO order`() {
        val gate = CanvasActionGate()
        gate.beginStroke()

        assertEquals(CanvasActionDecision.Parked, gate.request(CanvasDocumentAction.Undo))
        assertEquals(CanvasActionDecision.Parked, gate.request(CanvasDocumentAction.Redo))
        assertEquals(2, gate.pendingCount)

        assertNull(gate.endStroke(StrokeRelease.AFTER_HISTORY))
        assertNull(gate.next(), "pen-up is not history completion")
        assertEquals(false, gate.beginStroke(), "the committing stroke still owns the gate")

        assertEquals(CanvasDocumentAction.Undo, gate.finishStrokeHistory())
        assertEquals(CanvasDocumentAction.Redo, gate.next())
        assertNull(gate.next())
    }

    @Test
    fun `an empty stroke completion still releases parked actions`() {
        val gate = CanvasActionGate()
        gate.beginStroke()
        gate.request(CanvasDocumentAction.Undo)

        assertNull(gate.endStroke(StrokeRelease.AFTER_HISTORY))

        // A zero-dab merge has no entry to push, but it is still an explicit
        // terminal callback from the engine and must release the gate.
        assertEquals(CanvasDocumentAction.Undo, gate.finishStrokeHistory())
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

        assertEquals(false, gate.beginStroke())
        assertEquals(false, gate.strokeInFlight)
    }

    @Test
    fun `RMW cancel restore keeps parked actions behind the stroke`() {
        val gate = CanvasActionGate()
        gate.beginStroke()
        gate.request(CanvasDocumentAction.Undo)

        gate.beginWork()

        assertNull(gate.endStroke(StrokeRelease.IMMEDIATE))
        assertEquals(false, gate.beginStroke())
        assertEquals(CanvasDocumentAction.Undo, gate.finishWork())
    }

    @Test
    fun `leave during a stroke waits for history and earlier actions`() {
        val gate = CanvasActionGate()
        gate.beginStroke()
        gate.request(CanvasDocumentAction.Undo)
        gate.request(CanvasDocumentAction.Leave)

        assertNull(gate.endStroke(StrokeRelease.AFTER_HISTORY))
        assertEquals(false, gate.beginStroke(), "pending leave forbids another stroke")

        assertEquals(CanvasDocumentAction.Undo, gate.finishStrokeHistory())
        assertEquals(false, gate.beginStroke(), "queued leave forbids another stroke")
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

        assertEquals(false, gate.beginStroke())
        assertEquals(CanvasDocumentAction.Undo, gate.finishWork())

        assertEquals(false, gate.beginStroke(), "queued leave keeps the canvas closed to input")
        gate.beginWork()
        assertEquals(CanvasDocumentAction.Leave, gate.finishWork())
    }
}
