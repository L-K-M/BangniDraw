package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasOverlayClearanceTest {

    @Test
    fun `bottom overlays clear every rail mode`() {
        assertEquals(120, CanvasOverlayClearance.bottomPaddingDp(RailMode.DOCK))
        assertEquals(64, CanvasOverlayClearance.bottomPaddingDp(RailMode.SHORT))
        assertEquals(16, CanvasOverlayClearance.bottomPaddingDp(RailMode.GROUPED))
        assertEquals(16, CanvasOverlayClearance.bottomPaddingDp(RailMode.FULL))
    }
}
