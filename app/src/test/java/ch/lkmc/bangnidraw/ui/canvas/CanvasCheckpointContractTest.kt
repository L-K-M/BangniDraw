package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasCheckpointContractTest {

    @Test
    fun `thumbnail stays dirty until its write succeeds`() {
        val checkpoint = checkpointSource()
        val write = checkpoint.indexOf(THUMBNAIL_WRITE)
        if (write < 0) fail("missing $THUMBNAIL_WRITE")
        val clear = checkpoint.indexOf(CLEAR_THUMBNAIL_DIRTY)
        if (clear < 0) fail("missing $CLEAR_THUMBNAIL_DIRTY")

        assertTrue(write < clear, "$CLEAR_THUMBNAIL_DIRTY must follow $THUMBNAIL_WRITE")
    }

    @Test
    fun `clean project fast path retains maintenance work`() {
        val checkpoint = checkpointSource()
        val barrier = checkpoint.indexOf(COMMIT_BARRIER)
        if (barrier < 0) fail("missing $COMMIT_BARRIER")
        val fastPath = checkpoint.substring(0, barrier)

        assertTrue(THUMBNAIL_DIRTY in fastPath)
        assertTrue(PENDING_DELETES in fastPath)
    }

    private fun checkpointSource(): String {
        val viewModel = source(CANVAS_VIEW_MODEL_PATH)
        val start = viewModel.indexOf(CHECKPOINT_START)
        if (start < 0) fail("missing $CHECKPOINT_START")
        val end = viewModel.indexOf(CHECKPOINT_END, start)
        if (end <= start) fail("missing $CHECKPOINT_END after checkpoint")

        return viewModel.substring(start, end)
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

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
        const val CHECKPOINT_END = "private suspend fun maybeSyncGallery("
        const val COMMIT_BARRIER = "CheckpointBarrier.commitWhenFlushed("
        const val THUMBNAIL_WRITE = "Thumbnails.write("
        const val CLEAR_THUMBNAIL_DIRTY = "thumbDirty = false"
        const val THUMBNAIL_DIRTY = "thumbDirty"
        const val PENDING_DELETES = "pendingDeletes"
    }
}
