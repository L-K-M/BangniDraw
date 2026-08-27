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
}
