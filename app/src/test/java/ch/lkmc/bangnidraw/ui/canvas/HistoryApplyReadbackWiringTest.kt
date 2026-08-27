package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class HistoryApplyReadbackWiringTest {

    private val source = File(
        "src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt",
    ).readText()

    @Test
    fun `history restore drains composite readback before tile flush`() {
        val apply = source.substringAfter("private fun applyPreparedHistory(")
            .substringBefore("private fun historyFlushKeys(")
        val drain = apply.indexOf("awaitReadbacks(engine)")
        val flush = apply.indexOf("pixels.flushChanged(keys)")
        val release = apply.indexOf("finishDocumentWork()")

        assertTrue(drain >= 0)
        assertTrue(drain < flush)
        assertTrue(drain < release)
    }

    @Test
    fun `history publishes its restore-folded stack on the GL thread`() {
        val apply = source.substringAfter("private fun applyPreparedHistory(")
            .substringBefore("private fun historyFlushKeys(")
        val fold = apply.indexOf("val foldedStack = LayerTileUpdates.apply(")
        val dispatch = apply.indexOf("engine.applyLayerEdit(")
        val dispatchedStack = apply.substring(dispatch).substringBefore("pixelOps =")

        assertTrue(fold >= 0)
        assertTrue(fold < dispatch)
        assertTrue(dispatchedStack.contains("stack = foldedStack"))
    }
}
