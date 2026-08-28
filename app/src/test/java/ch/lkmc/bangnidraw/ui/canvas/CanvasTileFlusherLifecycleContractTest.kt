package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasTileFlusherLifecycleContractTest {

    @Test
    fun `teardown closes the flusher after its final checkpoint`() {
        val source = source(CANVAS_VIEW_MODEL_PATH)
        val teardown = section(source, ON_CLEARED, NOTE_CHANGE)

        assertTrue(CLOSE_METHOD_CALL in teardown)

        val closeMethod = section(source, CLOSE_METHOD, CHECKPOINT_METHOD)

        val checkpointAt = closeMethod.indexOf(LEAVE_CHECKPOINT)
        if (checkpointAt < 0) fail("missing final leave checkpoint")
        val finallyAt = closeMethod.indexOf(FINALLY)
        if (finallyAt < 0) fail("missing teardown finally")
        val closeAt = closeMethod.indexOf(CLOSE_FLUSHER)
        if (closeAt < 0) fail("missing $CLOSE_FLUSHER")

        assertTrue(checkpointAt < finallyAt)
        assertTrue(finallyAt < closeAt)
    }

    @Test
    fun `teardown detaches readbacks before its final checkpoint`() {
        val source = source(CANVAS_VIEW_MODEL_PATH)
        val teardown = section(source, ON_CLEARED, NOTE_CHANGE)
        val detach = teardown.indexOf(SESSION_DETACH)
        if (detach < 0) fail("missing $SESSION_DETACH")
        val close = teardown.indexOf(CLOSE_METHOD_CALL)
        if (close < 0) fail("missing $CLOSE_METHOD_CALL")

        assertTrue(detach < close)

        val awaitReadbacks = section(source, AWAIT_READBACKS, STREAM_TILES)
        assertTrue(NO_SESSION_COMPLETES in awaitReadbacks)
    }

    @Test
    fun `teardown logs close failures without crashing the process`() {
        val source = source(CANVAS_VIEW_MODEL_PATH)
        val teardown = section(source, ON_CLEARED, NOTE_CHANGE)
        val close = teardown.indexOf(CLOSE_METHOD_CALL)
        if (close < 0) fail("missing $CLOSE_METHOD_CALL")
        val cancellation = teardown.indexOf(CLOSE_CANCELLATION)
        if (cancellation < 0) fail("missing $CLOSE_CANCELLATION")
        val failure = teardown.indexOf(CLOSE_FAILURE)
        if (failure < 0) fail("missing $CLOSE_FAILURE")
        val report = teardown.indexOf(CLOSE_FAILURE_REPORT)
        if (report < 0) fail("missing $CLOSE_FAILURE_REPORT")

        assertTrue(close < cancellation)
        assertTrue(cancellation < failure)
        assertTrue(failure < report)
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun section(source: String, startMarker: String, endMarker: String): String {
        val start = uniqueIndexOf(source, startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        if (end < 0) fail("missing $endMarker after $startMarker")

        return source.substring(start, end)
    }

    private fun uniqueIndexOf(source: String, marker: String): Int {
        val first = source.indexOf(marker)
        if (first < 0) fail("missing $marker")
        if (source.indexOf(marker, first + marker.length) >= 0) {
            fail("ambiguous $marker")
        }

        return first
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
        const val ON_CLEARED = "override fun onCleared()"
        const val NOTE_CHANGE = "private fun noteChange()"
        const val CLOSE_METHOD_CALL =
            "withContext(NonCancellable) { checkpointAndCloseFlusher() }"
        const val CLOSE_CANCELLATION =
            "catch (e: kotlinx.coroutines.CancellationException)"
        const val CLOSE_FAILURE = "catch (e: Exception)"
        const val CLOSE_FAILURE_REPORT = "final checkpoint or flusher close failed"
        const val CLOSE_METHOD = "private suspend fun checkpointAndCloseFlusher()"
        const val CHECKPOINT_METHOD = "private suspend fun checkpointLocked("
        const val LEAVE_CHECKPOINT = "checkpointLocked(GallerySyncDecision.Trigger.LEAVE)"
        const val FINALLY = "finally"
        const val CLOSE_FLUSHER = "flusher.closeAndJoin()"
        const val SESSION_DETACH = "session = null"
        const val AWAIT_READBACKS = "private suspend fun awaitReadbacks()"
        const val STREAM_TILES = "private suspend fun streamTiles("
        const val NO_SESSION_COMPLETES =
            "val engine = session ?: return TileFlusher.ReadbackResult.COMPLETE"
    }
}
