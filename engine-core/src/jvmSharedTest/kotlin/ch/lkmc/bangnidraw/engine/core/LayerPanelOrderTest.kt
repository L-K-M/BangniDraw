package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayerPanelOrderTest {
    @Test
    fun `maps the top-first panel to the bottom-first stack`() {
        assertEquals(3, LayerPanelOrder.stackIndex(displayIndex = 0, size = 4))
        assertEquals(0, LayerPanelOrder.stackIndex(displayIndex = 3, size = 4))
        assertEquals(0, LayerPanelOrder.displayIndex(stackIndex = 3, size = 4))
        assertEquals(3, LayerPanelOrder.displayIndex(stackIndex = 0, size = 4))
    }

    @Test
    fun `maps a final panel drop to final stack indices`() {
        assertEquals(
            LayerPanelOrder.Move(from = 3, to = 1),
            LayerPanelOrder.move(fromDisplay = 0, toDisplay = 2, size = 4),
        )
        assertEquals(
            LayerPanelOrder.Move(from = 1, to = 3),
            LayerPanelOrder.move(fromDisplay = 2, toDisplay = 0, size = 4),
        )
        assertNull(LayerPanelOrder.move(fromDisplay = 1, toDisplay = 1, size = 4))
    }

    @Test
    fun `ignores stale drag indices after a stack change`() {
        assertNull(LayerPanelOrder.move(fromDisplay = -1, toDisplay = 1, size = 4))
        assertNull(LayerPanelOrder.move(fromDisplay = 1, toDisplay = 4, size = 4))
    }

    @Test
    fun `offers only reorder actions that move the layer`() {
        assertEquals(
            listOf(
                LayerReorderAction.UP,
                LayerReorderAction.DOWN,
                LayerReorderAction.TOP,
                LayerReorderAction.BOTTOM,
            ),
            LayerPanelOrder.actions(stackIndex = 1, size = 4),
        )
        assertEquals(
            listOf(LayerReorderAction.DOWN, LayerReorderAction.BOTTOM),
            LayerPanelOrder.actions(stackIndex = 3, size = 4),
        )
        assertEquals(
            listOf(LayerReorderAction.UP, LayerReorderAction.TOP),
            LayerPanelOrder.actions(stackIndex = 0, size = 4),
        )
        assertEquals(emptyList(), LayerPanelOrder.actions(stackIndex = 0, size = 1))
    }

    @Test
    fun `maps accessibility reorders to stack moves`() {
        assertEquals(
            LayerPanelOrder.Move(from = 1, to = 2),
            LayerPanelOrder.move(1, LayerReorderAction.UP, size = 4),
        )
        assertEquals(
            LayerPanelOrder.Move(from = 1, to = 0),
            LayerPanelOrder.move(1, LayerReorderAction.DOWN, size = 4),
        )
        assertEquals(
            LayerPanelOrder.Move(from = 1, to = 3),
            LayerPanelOrder.move(1, LayerReorderAction.TOP, size = 4),
        )
        assertEquals(
            LayerPanelOrder.Move(from = 2, to = 0),
            LayerPanelOrder.move(2, LayerReorderAction.BOTTOM, size = 4),
        )
        assertNull(LayerPanelOrder.move(3, LayerReorderAction.UP, size = 4))
        assertNull(LayerPanelOrder.move(0, LayerReorderAction.DOWN, size = 4))
    }

    @Test
    fun `rejects stale accessibility indices`() {
        assertEquals(
            emptyList<LayerReorderAction>(),
            LayerPanelOrder.actions(stackIndex = -1, size = 4),
        )
        assertEquals(
            emptyList<LayerReorderAction>(),
            LayerPanelOrder.actions(stackIndex = 4, size = 4),
        )
        assertNull(LayerPanelOrder.move(-1, LayerReorderAction.TOP, size = 4))
        assertNull(LayerPanelOrder.move(4, LayerReorderAction.UP, size = 4))
    }
}
