package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class EyedropperCommitWiringTest {

    private val source = File(
        "src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt",
    ).readText()

    @Test
    fun `pen-up queues one final pick before committing`() {
        val end = source.substringAfter("override fun onStrokeEnd(pointerId: Int)")
            .substringBefore("strokeState.fillParams = null")
        val final = end.indexOf("strokeState.requestFinalPick(")
        val commit = end.indexOf("viewModel.commitPickedColor()")

        assertTrue(final >= 0, "pen-up must queue its release-position read")
        assertTrue(final < commit, "the final callback must precede color commit")
        assertTrue(end.contains("engine.sampleColor("))
        assertTrue(end.contains("request.x"))
        assertTrue(end.contains("request.y"))
        assertTrue(end.contains("request.params"))
    }

    @Test
    fun `cancel invalidates a pick and releases the stroke gate`() {
        val cancel = source.substringAfter("override fun onStrokeCancel()")
            .substringBefore("// Roadmap 2.5b")

        assertTrue(cancel.contains("strokeState.cancelPick()"))
        assertTrue(
            cancel.contains(
                "viewModel.endStrokeTool(reason, StrokeEndDisposition.COMPLETE)",
            ),
        )
    }
}
