package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasActionGateTest {

    @Test
    fun `actions during a stroke wait and retain FIFO order`() {
        val gate = CanvasActionGate()
        gate.beginStroke()

        assertEquals(CanvasActionDecision.Parked, gate.request(CanvasDocumentAction.Undo))
        assertEquals(CanvasActionDecision.Parked, gate.request(CanvasDocumentAction.Redo))
        assertEquals(2, gate.pendingCount)

        assertEquals(CanvasDocumentAction.Undo, gate.endStroke())
        assertEquals(CanvasDocumentAction.Redo, gate.next())
        assertNull(gate.next())
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

        assertNull(gate.endStroke())
        assertEquals(false, gate.beginStroke())
        assertEquals(CanvasDocumentAction.Undo, gate.finishWork())
    }
}
