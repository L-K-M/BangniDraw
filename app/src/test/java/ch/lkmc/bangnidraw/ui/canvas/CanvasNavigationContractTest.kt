package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasNavigationContractTest {

    @Test
    fun `system back top back and settings share the leave gate`() {
        val screen = source(CANVAS_SCREEN_PATH)
        val viewModel = source(CANVAS_VIEW_MODEL_PATH)

        assertEquals(BACK_ENTRY_POINTS, HANDLE_BACK.findAll(screen).count())
        assertEquals(DIRECT_LEAVE_ENTRY_POINTS, REQUEST_LEAVE.findAll(screen).count())
        assertFalse("viewModel.leave(" in screen)
        assertTrue("requestLeave(afterWrite)" in viewModel)
    }

    @Test
    fun `reopening the leave gate invalidates the prior job`() {
        val viewModel = source(CANVAS_VIEW_MODEL_PATH)
        val start = viewModel.indexOf(RELEASE_LEAVE_GATE)
        if (start < 0) fail("missing $RELEASE_LEAVE_GATE")
        val end = viewModel.indexOf(NOTE_LEAVE_FAILURE, start)
        if (end <= start) fail("missing $NOTE_LEAVE_FAILURE after release gate")

        assertTrue("leaveJob = null" in viewModel.substring(start, end))
    }

    @Test
    fun `closing clears keyboard focus before the delayed scrim`() {
        val screen = source(CANVAS_SCREEN_PATH)
        val start = screen.indexOf(CLOSING_EFFECT)
        if (start < 0) fail("missing $CLOSING_EFFECT")
        val end = screen.indexOf(CLOSING_SCRIM, start)
        if (end <= start) fail("missing $CLOSING_SCRIM after closing effect")

        val effect = screen.substring(start, end)
        assertTrue(effect.indexOf(CLEAR_FOCUS) in 0 until effect.indexOf(SCRIM_DELAY))
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
        const val CANVAS_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt"
        const val CANVAS_VIEW_MODEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt"
        const val BACK_ENTRY_POINTS = 2
        const val DIRECT_LEAVE_ENTRY_POINTS = 2
        const val RELEASE_LEAVE_GATE = "private fun releaseLeaveGate()"
        const val NOTE_LEAVE_FAILURE = "private fun noteLeaveFailure()"
        const val CLOSING_EFFECT = "LaunchedEffect(state.closing)"
        const val CLOSING_SCRIM = "if (closingScrim)"
        const val CLEAR_FOCUS = "focusManager.clearFocus()"
        const val SCRIM_DELAY = "delay(CLOSING_SCRIM_DELAY_MS)"
        val HANDLE_BACK = Regex("""viewModel\.handleBack\(onBack\)""")
        val REQUEST_LEAVE = Regex("""viewModel\.requestLeave\(""")
    }
}
