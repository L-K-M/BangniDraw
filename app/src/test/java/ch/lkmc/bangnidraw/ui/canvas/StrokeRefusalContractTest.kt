package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A pen-down the action gate refuses must say so through the stroke-notice
 * toast channel — the same one a locked layer uses. The pen moving with
 * nothing landing and nothing said reads as a dead app (review item glm.md
 * §A.9 / ANALYSIS third-pass #9).
 */
class StrokeRefusalContractTest {

    @Test
    fun `a refused stroke notifies instead of returning silently`() {
        val viewModel = source(CANVAS_VIEW_MODEL_PATH)
        val begin = viewModel.indexOf(BEGIN_STROKE_TOOL)
        if (begin < 0) fail("missing $BEGIN_STROKE_TOOL")
        val gateCheck = viewModel.indexOf(REFUSED_GATE, begin)
        if (gateCheck < 0) fail("missing $REFUSED_GATE in beginStrokeTool")

        val branch = viewModel.substring(gateCheck, gateCheck + BRANCH_WINDOW)
        assertTrue(NOTE_REFUSED in branch, "the refused branch must call $NOTE_REFUSED")
        assertTrue(SILENT_RETURN !in branch, "the refused branch must not return null silently")
    }

    @Test
    fun `the refusal notice rides the stroke-notice channel`() {
        val viewModel = source(CANVAS_VIEW_MODEL_PATH)
        val notice = viewModel.indexOf(NOTE_REFUSED_DEFINITION)
        if (notice < 0) fail("missing $NOTE_REFUSED_DEFINITION")
        val body = viewModel.substring(notice, notice + BRANCH_WINDOW)

        assertTrue(BUSY_NOTICE in body, "the refusal sets the busy string")
        assertTrue(NOTICE_REVISION in body, "the refusal bumps the notice revision")
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
        const val BEGIN_STROKE_TOOL = "fun beginStrokeTool("
        const val REFUSED_GATE = "if (!actionGate.beginStroke()) {"
        const val NOTE_REFUSED = "noteStrokeRefused()"
        const val NOTE_REFUSED_DEFINITION = "private fun noteStrokeRefused()"
        const val SILENT_RETURN = "} return null"
        const val BUSY_NOTICE = "R.string.canvas_busy"
        const val NOTICE_REVISION = "strokeLayerNoticeRevision += 1"
        const val BRANCH_WINDOW = 300
    }
}
