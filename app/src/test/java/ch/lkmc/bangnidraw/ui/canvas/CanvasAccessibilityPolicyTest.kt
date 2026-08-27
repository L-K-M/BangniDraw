package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasAccessibilityPolicyTest {
    @Test
    fun `only available history actions are exposed`() {
        assertEquals(
            emptyList<CanvasHistoryAction>(),
            availableCanvasHistoryActions(
                undo = ActionAvailability.DISABLED,
                redo = ActionAvailability.DISABLED,
            ),
        )
        assertEquals(
            listOf(CanvasHistoryAction.UNDO),
            availableCanvasHistoryActions(
                undo = ActionAvailability.ENABLED,
                redo = ActionAvailability.DISABLED,
            ),
        )
        assertEquals(
            listOf(CanvasHistoryAction.REDO),
            availableCanvasHistoryActions(
                undo = ActionAvailability.DISABLED,
                redo = ActionAvailability.ENABLED,
            ),
        )
        assertEquals(
            listOf(CanvasHistoryAction.UNDO, CanvasHistoryAction.REDO),
            availableCanvasHistoryActions(
                undo = ActionAvailability.ENABLED,
                redo = ActionAvailability.ENABLED,
            ),
        )
    }
}
