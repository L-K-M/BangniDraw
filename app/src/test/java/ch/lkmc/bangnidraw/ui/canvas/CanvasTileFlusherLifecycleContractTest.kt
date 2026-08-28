package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasTileFlusherLifecycleContractTest {

    @Test
    fun `teardown closes the flusher after its final checkpoint`() {
        val source = source(CANVAS_VIEW_MODEL_PATH)
        val start = source.indexOf(ON_CLEARED)
        if (start < 0) fail("missing $ON_CLEARED")
        val end = source.indexOf(NOTE_CHANGE, start)
        if (end <= start) fail("missing $NOTE_CHANGE after $ON_CLEARED")

        assertTrue(CLOSE_METHOD_CALL in source.substring(start, end))

        val closeStart = source.indexOf(CLOSE_METHOD)
        if (closeStart < 0) fail("missing $CLOSE_METHOD")
        val closeEnd = source.indexOf(CHECKPOINT_METHOD, closeStart)
        if (closeEnd <= closeStart) fail("missing $CHECKPOINT_METHOD after $CLOSE_METHOD")

        val closeMethod = source.substring(closeStart, closeEnd)
        val checkpointAt = closeMethod.indexOf(LEAVE_CHECKPOINT)
        if (checkpointAt < 0) fail("missing final leave checkpoint")
        val finallyAt = closeMethod.indexOf(FINALLY)
        if (finallyAt < 0) fail("missing teardown finally")
        val closeAt = closeMethod.indexOf(CLOSE_FLUSHER)
        if (closeAt < 0) fail("missing $CLOSE_FLUSHER")

        assertTrue(checkpointAt < finallyAt)
        assertTrue(finallyAt < closeAt)
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
        const val ON_CLEARED = "override fun onCleared()"
        const val NOTE_CHANGE = "private fun noteChange()"
        const val CLOSE_METHOD_CALL = "checkpointAndCloseFlusher()"
        const val CLOSE_METHOD = "private suspend fun checkpointAndCloseFlusher()"
        const val CHECKPOINT_METHOD = "private suspend fun checkpointLocked("
        const val LEAVE_CHECKPOINT = "checkpointLocked(GallerySyncDecision.Trigger.LEAVE)"
        const val FINALLY = "finally"
        const val CLOSE_FLUSHER = "flusher.closeAndJoin()"
    }
}
