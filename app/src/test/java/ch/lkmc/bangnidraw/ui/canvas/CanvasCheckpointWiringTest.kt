package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasCheckpointWiringTest {

    private val source = File("src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt")
        .readText()

    @Test
    fun `lifecycle and autosave checkpoints enter the document gate`() {
        val lifecycle = source.substringAfter("fun checkpointNow()")
            .substringBefore("override fun onCleared()")
        val autosave = source.substringAfter("private fun noteChange()")
            .substringBefore("private suspend fun checkpoint(")

        assertTrue(lifecycle.contains("requestCheckpoint("))
        assertTrue(autosave.contains("requestCheckpoint("))
    }

    @Test
    fun `checkpoint completion always releases document work`() {
        val worker = source.substringAfter("private fun launchCheckpoint(")
            .substringBefore("private suspend fun checkpoint(")

        assertTrue(worker.contains("finally"))
        assertTrue(worker.contains("finishDocumentWork()"))
    }

    @Test
    fun `pending checkpoint outcomes schedule a retry`() {
        val handler = source.substringAfter("private fun finishCheckpoint(")
            .substringBefore("private suspend fun checkpoint(")

        assertTrue(handler.contains("CheckpointRetryPolicy.delayMs"))
        assertTrue(handler.contains("requestCheckpoint("))
    }

    @Test
    fun `project checkpoint failure reaches storage UI`() {
        val checkpoint = source.substringAfter("private suspend fun checkpoint(")
            .substringBefore("private suspend fun maybeSyncGallery(")

        assertTrue(checkpoint.contains("checkpointStorageFull.value = true"))
        assertFalse(checkpoint.contains("_uiState.value = UiState.Failed"))
    }

    @Test
    fun `late tile readback keeps a captured checkpoint dirty`() {
        val readback = source.substringAfter("fun onTileReadback(")
            .substringBefore("private fun onRmwStarted(")

        assertTrue(readback.contains("documentRevision.incrementAndGet()"))
    }

    @Test
    fun `checkpoint records exact journal sequences`() {
        val snapshot = source.substringAfter("private fun captureCheckpointSnapshot()")
            .substringBefore("private suspend fun checkpoint(")

        assertTrue(snapshot.contains("val j = journal ?: return null"))
        assertTrue(snapshot.contains("seqs = j.entries.map(HistoryEntry::seq)"))
    }

    @Test
    fun `checkpoint generation is captured before tile folding`() {
        val snapshot = source.substringAfter("private fun captureCheckpointSnapshot()")
            .substringBefore("private suspend fun checkpoint(")

        val revision = snapshot.indexOf("val revision = documentRevision.get()")
        val fold = snapshot.indexOf("val folded = fold(current, now)")
        assertTrue(revision >= 0)
        assertTrue(revision < fold)
    }

    @Test
    fun `gallery sync uses the checkpoint pixel revision`() {
        val snapshot = source.substringAfter("private fun captureCheckpointSnapshot()")
            .substringBefore("private suspend fun checkpoint(")
        val checkpoint = source.substringAfter("private suspend fun checkpoint(")
            .substringBefore("private suspend fun maybeSyncGallery(")
        val gallery = source.substringAfter("private suspend fun maybeSyncGallery(")
            .substringBefore("private fun fold(")

        assertTrue(snapshot.contains("pixelRevision = revisions.get()"))
        assertTrue(checkpoint.contains("pixelRevision = snapshot.pixelRevision"))
        assertFalse(gallery.contains("revisions.get()"))
    }

    @Test
    fun `failed tile flush cannot advance the project commit point`() {
        val checkpoint = source.substringAfter("private suspend fun checkpoint(")
            .substringBefore("private suspend fun maybeSyncGallery(")

        assertTrue(checkpoint.contains("if (!flusher.checkpointFlush())"))
        assertTrue(checkpoint.contains("CheckpointResult.STORAGE_PENDING"))
    }
}
