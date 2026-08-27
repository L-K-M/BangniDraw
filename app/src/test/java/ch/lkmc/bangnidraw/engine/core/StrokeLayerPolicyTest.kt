package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StrokeLayerPolicyTest {
    @Test
    fun `lock refuses before hidden-layer notice`() {
        assertEquals(
            StrokeLayerDecision.REFUSE_LOCKED,
            StrokeLayerPolicy.decide(visible = false, locked = true),
        )
        assertEquals(
            StrokeLayerDecision.DRAW_HIDDEN,
            StrokeLayerPolicy.decide(visible = false, locked = false),
        )
        assertEquals(
            StrokeLayerDecision.DRAW,
            StrokeLayerPolicy.decide(visible = true, locked = false),
        )
    }
}
