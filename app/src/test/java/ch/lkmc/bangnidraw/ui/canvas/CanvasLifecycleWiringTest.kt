package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasLifecycleWiringTest {

    private val source = File("src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt")
        .readText()

    @Test
    fun `ready screen passes navigation callbacks without nesting leave`() {
        assertFalse(source.contains("val leave = { viewModel.leave(onBack) }"))
        assertTrue(source.contains("onLeave = onBack"))
        assertTrue(source.contains("beforeLeave = ::finishOpenStrokeForLeave"))
    }

    @Test
    fun `input view changes reach the renderer synchronously`() {
        val callback = source.substringAfter("override fun onViewChanged(view: ViewTransform)")
            .substringBefore("override fun onRotationSnapped()")

        assertTrue(callback.contains("session?.setView(view)"))
    }
}
