package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasUiStateTest {

    @Test
    fun `a dialog requested mid stroke waits for stroke end`() {
        val active = CanvasUiPolicy.onStrokeBegin(CanvasChromeState())

        val parked = CanvasUiPolicy.requestDialog(active, CanvasDialog.FlattenLayers)

        assertNull(parked.dialog)
        assertEquals(CanvasDialog.FlattenLayers, parked.pendingDialog)

        val ended = CanvasUiPolicy.onStrokeEnd(parked)
        assertEquals(CanvasDialog.FlattenLayers, ended.dialog)
        assertNull(ended.pendingDialog)
    }

    @Test
    fun `a canvas tap dismisses a panel and does not draw`() {
        val open = CanvasUiPolicy.togglePanel(CanvasChromeState(), CanvasPanel.LAYERS)

        val result = CanvasUiPolicy.canvasTap(open)

        assertEquals(CanvasTapEffect.DISMISS_PANEL, result.effect)
        assertNull(result.state.openPanel)
    }

    @Test
    fun `a first hint tap dismisses the hint before a panel`() {
        val state = CanvasChromeState(
            openPanel = CanvasPanel.COLOR,
            hint = HintVisibility.VISIBLE,
        )

        val result = CanvasUiPolicy.canvasTap(state)

        assertEquals(CanvasTapEffect.DISMISS_HINT, result.effect)
        assertEquals(HintVisibility.HIDDEN, result.state.hint)
        assertEquals(CanvasPanel.COLOR, result.state.openPanel)
    }

    @Test
    fun `a bare canvas tap reaches drawing`() {
        val result = CanvasUiPolicy.canvasTap(CanvasChromeState())

        assertEquals(CanvasTapEffect.DRAW, result.effect)
        assertEquals(CanvasChromeState(), result.state)
    }

    @Test
    fun `hardware back closes dialog panel and focus before leaving`() {
        val start = CanvasChromeState(
            openPanel = CanvasPanel.LAYERS,
            focusMode = FocusMode.FOCUSED,
            dialog = CanvasDialog.FlattenLayers,
        )

        val dialog = CanvasUiPolicy.back(start)
        assertEquals(CanvasBackEffect.CLOSE_DIALOG, dialog.effect)
        assertNull(dialog.state.dialog)

        val panel = CanvasUiPolicy.back(dialog.state)
        assertEquals(CanvasBackEffect.CLOSE_PANEL, panel.effect)
        assertNull(panel.state.openPanel)

        val focus = CanvasUiPolicy.back(panel.state)
        assertEquals(CanvasBackEffect.EXIT_FOCUS, focus.effect)
        assertEquals(FocusMode.CHROME, focus.state.focusMode)

        val leave = CanvasUiPolicy.back(focus.state)
        assertEquals(CanvasBackEffect.LEAVE, leave.effect)
    }

    @Test
    fun `entering focus closes the open panel`() {
        val open = CanvasChromeState(openPanel = CanvasPanel.BRUSH_SETTINGS)

        val focused = CanvasUiPolicy.enterFocus(open)

        assertEquals(FocusMode.FOCUSED, focused.focusMode)
        assertNull(focused.openPanel)
    }

    @Test
    fun `clear layer dialog is parked mid stroke like other layer dialogs`() {
        val active = CanvasUiPolicy.onStrokeBegin(CanvasChromeState())
        val parked = CanvasUiPolicy.requestDialog(active, CanvasDialog.ClearLayer(2))
        assertNull(parked.dialog)
        assertEquals(CanvasDialog.ClearLayer(2), parked.pendingDialog)
        val ended = CanvasUiPolicy.onStrokeEnd(parked)
        assertEquals(CanvasDialog.ClearLayer(2), ended.dialog)
        assertNull(ended.pendingDialog)
    }
}
