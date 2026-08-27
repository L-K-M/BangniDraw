package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StrokeLayerPolicyTest {
    @Test
    fun `lock refuses before hidden-layer notice`() {
        val hidden = LayerProps(
            id = LayerId("hidden"),
            name = "Hidden",
            visible = false,
        )

        assertEquals(
            StrokeLayerDecision.REFUSE_LOCKED,
            StrokeLayerPolicy.decide(
                hidden.copy(locked = true),
                StrokeOperation.PAINT,
            ),
        )
        assertEquals(
            StrokeLayerDecision.DRAW_HIDDEN,
            StrokeLayerPolicy.decide(hidden, StrokeOperation.PAINT),
        )
        assertEquals(
            StrokeLayerDecision.DRAW,
            StrokeLayerPolicy.decide(
                hidden.copy(visible = true),
                StrokeOperation.PAINT,
            ),
        )
    }

    @Test
    fun `alpha lock refuses erase before the engine starts`() {
        val layer = LayerProps(
            id = LayerId("ink"),
            name = "Ink",
            alphaLock = true,
        )

        assertEquals(
            StrokeLayerDecision.REFUSE_ALPHA_LOCKED,
            StrokeLayerPolicy.decide(layer, StrokeOperation.ERASE),
        )
        assertEquals(
            StrokeLayerDecision.DRAW,
            StrokeLayerPolicy.decide(layer, StrokeOperation.PAINT),
        )
    }
}
