package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasStrokeAdmissionWiringTest {

    private val screen = File(
        "src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt",
    ).readText()
    private val viewModel = File(
        "src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt",
    ).readText()

    @Test
    fun `stroke uses the layer snapshot admitted by the view model`() {
        val callback = screen.substringAfter("override fun onStrokeBegin")
            .substringBefore("override fun onStrokeSample")

        assertTrue(callback.contains("val admission = viewModel.beginStrokeTool"))
        assertTrue(callback.contains("val active = admission.activeLayer"))
        assertFalse(callback.contains("stack.layers.getOrNull"))
    }

    @Test
    fun `input handler does not capture compose stack`() {
        val rememberCall = screen.substringAfter("val touch = remember(")
            .substringBefore(") {")

        assertFalse(rememberCall.contains("stack"))
    }

    @Test
    fun `admission snapshots the current document active layer`() {
        val admission = viewModel.substringAfter("fun beginStrokeTool")
            .substringBefore("internal fun endStrokeTool")

        assertTrue(admission.contains("val activeLayer = stack.active"))
        assertTrue(admission.contains("return StrokeAdmission(selection, activeLayer)"))
    }
}
