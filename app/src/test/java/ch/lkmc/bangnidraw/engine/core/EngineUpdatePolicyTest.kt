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

    @Test
    fun `the session gate suppresses a repeated view update`() {
        val gate = EngineViewUpdateGate()
        val view = ViewTransform(scale = 2f, tx = 10f)
        val applied = mutableListOf<ViewTransform>()

        gate.update(view) { applied += view }
        gate.update(view.copy()) { applied += view.copy() }
        gate.update(view.copy(tx = 20f)) { applied += view.copy(tx = 20f) }

        assertEquals(listOf(view, view.copy(tx = 20f)), applied)
    }
}
