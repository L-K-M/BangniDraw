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

    @Test
    fun `redo bytes are durable before transition setup`() {
        val apply = source.substringAfter("private fun applyHistory(")
            .substringBefore("private fun applyPreparedHistory(")
        val captured = apply.indexOf("val capturedRedoBytes = redoBytes?.await()")
        val accounted = apply.indexOf("journal?.noteRedoBytes(entry.seq, capturedRedoBytes)")
        val transition = apply.indexOf("transitions.begin(entry, direction, fromCursor)")

        assertTrue(captured >= 0)
        assertTrue(accounted > captured)
        assertTrue(accounted < transition)
    }

    @Test
    fun `redo pruning waits for the applied transition checkpoint`() {
        val prepared = source.substringAfter("private fun applyPreparedHistory(")
            .substringBefore("private suspend fun checkpointHistoryTransition(")
        val firstCheckpoint = prepared.indexOf("checkpointHistoryTransition(snapshot)")
        val prune = prepared.indexOf("captureRedoPruneCheckpoint()")
        val secondCheckpoint = prepared.indexOf(
            "checkpointHistoryTransition(pruneSnapshot)",
        )
        val finish = prepared.indexOf("finishDocumentWork()")
        val helper = source.substringAfter("private fun captureRedoPruneCheckpoint()")
            .substringBefore("private fun historyFlushKeys(")

        assertTrue(firstCheckpoint >= 0)
        assertTrue(prune > firstCheckpoint)
        assertTrue(secondCheckpoint > prune)
        assertTrue(finish > secondCheckpoint)
        assertTrue(helper.contains("j.pruneAfterRedoAccounting()"))
        assertTrue(helper.contains("pendingDeletes += pruned"))
        assertTrue(helper.contains("historyCursor = j.cursor"))
        assertTrue(helper.contains("dirty = true"))
        assertTrue(helper.contains("documentRevision.incrementAndGet()"))
        assertTrue(helper.contains("captureCheckpointSnapshot()"))
    }
}
