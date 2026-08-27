package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasChromeRegressionTest {

    @Test
    fun `opening a shortcut panel exits focus mode`() {
        val focused = CanvasUiPolicy.enterFocus(CanvasChromeState())

        for (panel in listOf(CanvasPanel.LAYERS, CanvasPanel.COLOR)) {
            val opened = CanvasUiPolicy.togglePanel(focused, panel)

            assertEquals(FocusMode.CHROME, opened.focusMode, panel.name)
            assertEquals(panel, opened.openPanel)
        }
    }
}
