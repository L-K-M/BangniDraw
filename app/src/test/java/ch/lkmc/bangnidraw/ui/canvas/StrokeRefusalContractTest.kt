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
 *
 * CanvasViewModel cannot be constructed on the JVM (Hilt + `Context` +
 * framework stores), so this follows the repo's established source-contract
 * pattern (`CanvasNavigationContractTest`, `WaterToolUiContractTest`):
 * assertions scope to whole function *bodies*, never fixed character
 * windows or brace styles.
 */
class StrokeRefusalContractTest {

    @Test
    fun `a refused stroke notifies instead of returning silently`() {
        val body = functionBody(source(CANVAS_VIEW_MODEL_PATH), BEGIN_STROKE_TOOL)

        val gateCheck = body.indexOf(REFUSED_GATE)
        if (gateCheck < 0) fail("missing $REFUSED_GATE in beginStrokeTool")
        val noticeCall = body.indexOf(NOTE_REFUSED)
        if (noticeCall < 0) fail("missing $NOTE_REFUSED in beginStrokeTool")

        // The notice must belong to the refused branch: it fires before the
        // stroke's chrome work could ever run.
        val strokeBegins = body.indexOf(STROKE_BEGINS)
        if (strokeBegins < 0) fail("missing $STROKE_BEGINS in beginStrokeTool")
        assertTrue(gateCheck < noticeCall && noticeCall < strokeBegins)
    }

    @Test
    fun `the refusal notice rides the stroke-notice channel`() {
        val body = functionBody(source(CANVAS_VIEW_MODEL_PATH), NOTE_REFUSED_DEFINITION)

        assertTrue(BUSY_NOTICE in body, "the refusal sets the busy string")
        assertTrue(NOTICE_REVISION in body, "the refusal bumps the notice revision")
    }

    /** [declaration] must name a `fun` on one line; returns its whole body. */
    private fun functionBody(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        if (start < 0) fail("missing $declaration")
        val next = NEXT_MEMBER.findAll(source)
            .map { it.range.first }
            .firstOrNull { it > start + declaration.length }
        val end = next ?: source.length
        return source.substring(start, end)
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
        const val NOTE_REFUSED_DEFINITION = "private fun noteStrokeRefused()"
        const val REFUSED_GATE = "if (!actionGate.beginStroke())"
        const val NOTE_REFUSED = "noteStrokeRefused()"
        const val STROKE_BEGINS = "CanvasUiPolicy.onStrokeBegin"
        const val BUSY_NOTICE = "R.string.canvas_busy"
        const val NOTICE_REVISION = "strokeLayerNoticeRevision += 1"

        /** The next class member after the one under test, at member indent. */
        val NEXT_MEMBER = Regex(
            """\n    ((public |internal |protected |private |open |override |final |suspend |inline )*)fun """,
        )
    }
}
