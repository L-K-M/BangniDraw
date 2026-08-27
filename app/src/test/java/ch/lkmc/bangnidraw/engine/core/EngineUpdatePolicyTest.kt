package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class EngineUpdatePolicyTest {

    @Test
    fun `the first value reaches the engine`() {
        assertEquals(
            EngineUpdate.APPLY,
            EngineUpdatePolicy.decide(previous = null, next = ViewTransform()),
        )
    }

    @Test
    fun `an equal recomposition does not redraw`() {
        val previous = ViewTransform(scale = 2f, tx = 10f)

        assertEquals(
            EngineUpdate.KEEP,
            EngineUpdatePolicy.decide(previous, previous.copy()),
        )
    }

    @Test
    fun `a changed value reaches the engine`() {
        assertEquals(
            EngineUpdate.APPLY,
            EngineUpdatePolicy.decide(ViewTransform(), ViewTransform(scale = 2f)),
        )
    }
}
