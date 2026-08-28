package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasCheckpointConcurrencyContractTest {

    @Test
    fun `checkpoint installs its snapshot on Main before disk IO`() {
        val viewModel = source()
        val checkpoint = section(viewModel, CHECKPOINT_START, CAPTURE_START)
        val captureCall = checkpoint.indexOf(CAPTURE_CALL)
        val storeWrite = checkpoint.indexOf(PROJECT_WRITE)
        if (captureCall < 0) fail("missing $CAPTURE_CALL")
        if (storeWrite < 0) fail("missing $PROJECT_WRITE")

        assertTrue(captureCall < storeWrite)

        val capture = section(viewModel, CAPTURE_START, HISTORY_START)
        assertTrue(MAIN_CONTEXT in capture)
        assertTrue(INSTALL_DOCUMENT in capture)
    }

    @Test
    fun `checkpoint completion clears only its current generation`() {
        val finish = section(source(), FINISH_START, GALLERY_START)
        val freshness = finish.indexOf(FRESHNESS_CHECK)
        val clear = finish.indexOf(CLEAR_DIRTY)
        if (freshness < 0) fail("missing $FRESHNESS_CHECK")
        if (clear < 0) fail("missing $CLEAR_DIRTY")

        assertTrue(freshness < clear)
    }

    @Test
    fun `checkpoint finishes content before gallery metadata becomes dirty`() {
        val checkpoint = section(source(), CHECKPOINT_START, CAPTURE_START)
        val finish = checkpoint.indexOf(FINISH_CALL)
        if (finish < 0) fail("missing $FINISH_CALL")
        val gallery = checkpoint.indexOf(GALLERY_CALL)
        if (gallery < 0) fail("missing $GALLERY_CALL")

        assertTrue(
            finish < gallery,
            "gallery metadata must belong to the next checkpoint generation",
        )
    }

    private fun source(): String = File(repositoryRoot(), CANVAS_VIEW_MODEL_PATH).readText()

    private fun section(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        if (start < 0) fail("missing $startMarker")
        val end = source.indexOf(endMarker, start + startMarker.length)
        if (end <= start) fail("missing $endMarker after $startMarker")

        return source.substring(start, end)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val CANVAS_VIEW_MODEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt"
        const val CHECKPOINT_START = "private suspend fun checkpoint("
        const val CAPTURE_START = "private suspend fun captureCheckpoint("
        const val HISTORY_START = "private fun historyRecordForCheckpoint("
        const val FINISH_START = "private fun finishCheckpoint("
        const val GALLERY_START = "private suspend fun maybeSyncGallery("
        const val CAPTURE_CALL = "captureCheckpoint(generation, now)"
        const val PROJECT_WRITE = "store.checkpoint(snapshot.document, snapshot.history)"
        const val MAIN_CONTEXT = "withContext(Dispatchers.Main)"
        const val INSTALL_DOCUMENT = "document = folded"
        const val FRESHNESS_CHECK = "checkpointGeneration.freshness(snapshot.generation)"
        const val CLEAR_DIRTY = "dirty = false"
        const val FINISH_CALL = "finishCheckpoint(snapshot)"
        const val GALLERY_CALL = "maybeSyncGallery(snapshot.document"
    }
}
