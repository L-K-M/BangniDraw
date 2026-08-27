package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasIdleOperationTest {

    @Test
    fun `share and export defer during a stroke`() {
        val active = CanvasChromeState(strokeActivity = StrokeActivity.ACTIVE)

        for (operation in listOf(CanvasIdleOperation.SHARE, CanvasIdleOperation.EXPORT)) {
            assertEquals(
                CanvasIdleDecision.DEFER,
                CanvasUiPolicy.idleOperation(active.strokeActivity, operation),
                operation.name,
            )
        }
    }

    @Test
    fun `reset view is ignored during a stroke`() {
        assertEquals(
            CanvasIdleDecision.IGNORE,
            CanvasUiPolicy.idleOperation(
                StrokeActivity.ACTIVE,
                CanvasIdleOperation.RESET_VIEW,
            ),
        )
    }

    @Test
    fun `idle operations run without a stroke`() {
        for (operation in CanvasIdleOperation.entries) {
            assertEquals(
                CanvasIdleDecision.RUN,
                CanvasUiPolicy.idleOperation(StrokeActivity.IDLE, operation),
                operation.name,
            )
        }
    }
}
