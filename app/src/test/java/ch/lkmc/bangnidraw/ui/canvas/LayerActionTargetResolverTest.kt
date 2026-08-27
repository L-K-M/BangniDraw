package ch.lkmc.bangnidraw.ui.canvas

import ch.lkmc.bangnidraw.engine.core.LayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayerActionTargetResolverTest {

    private val a = LayerId("layer-a")
    private val b = LayerId("layer-b")
    private val c = LayerId("layer-c")
    private val d = LayerId("layer-d")

    @Test
    fun `delete B then clear C keeps C as the queued target`() {
        val original = listOf(a, b, c)
        val deleteTarget = LayerActionTargetResolver.capture(original, index = 1)
        val clearTarget = LayerActionTargetResolver.capture(original, index = 2)

        assertEquals(b, deleteTarget)
        assertEquals(c, clearTarget)

        val afterDelete = original.toMutableList().apply {
            removeAt(LayerActionTargetResolver.resolve(this, deleteTarget!!)!!)
        }
        val clearIndex = LayerActionTargetResolver.resolve(afterDelete, clearTarget!!)

        assertEquals(1, clearIndex)
        assertEquals(c, afterDelete[clearIndex!!])
    }

    @Test
    fun `a removed target does not fall through to its old index`() {
        val target = LayerActionTargetResolver.capture(listOf(a, b, c), index = 1)

        assertNull(LayerActionTargetResolver.resolve(listOf(a, c), target!!))
        assertNull(LayerActionTargetResolver.capture(listOf(a, c), index = 2))
    }

    @Test
    fun `a queued move keeps its stable anchor after another deletion`() {
        val target = LayerActionTargetResolver.captureMove(
            layers = listOf(a, b, c, d),
            from = 1,
            to = 3,
        )

        assertEquals(
            LayerMoveIndices(from = 1, to = 2),
            LayerActionTargetResolver.resolveMove(listOf(a, b, d), target!!),
        )
    }

    @Test
    fun `a queued move is dropped when its anchor disappears`() {
        val target = LayerActionTargetResolver.captureMove(
            layers = listOf(a, b, c),
            from = 2,
            to = 0,
        )

        assertNull(LayerActionTargetResolver.resolveMove(listOf(b, c), target!!))
    }

    @Test
    fun `a queued merge does not use a different lower partner`() {
        val target = LayerActionTargetResolver.captureMerge(listOf(a, b, c), upper = 2)

        assertEquals(2, LayerActionTargetResolver.resolveMerge(listOf(a, b, c), target!!))
        assertNull(LayerActionTargetResolver.resolveMerge(listOf(a, b, d, c), target))
    }
}
