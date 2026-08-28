package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class StrokeCommitOutcomeContractTest {

    @Test
    fun `stroke side effects wait for nonempty merged keys`() {
        val screen = section(
            source(CANVAS_SCREEN_PATH),
            "override fun onStrokeEnd(pointerId: Int)",
            "override fun onStrokeCancel()",
        )
        assertTrue(
            """engine.endStroke(driver.opacityCeiling) {
                        viewModel.onStrokeCommitted(colorUsage, strokeColor)
                    }""" in screen,
            "pen-up must defer commit side effects to the engine outcome",
        )

        val session = section(
            source(ENGINE_SESSION_PATH),
            "fun endStroke(opacityCeiling: Float, onCommitted: () -> Unit)",
            "private fun completeStrokeWithoutMerge",
        )
        val historyOutcome = session.indexOf("mergedListener?.invoke(spec, keys, thisRevision)")
        val nonemptyGuard = session.indexOf("if (keys.isNotEmpty())")
        val sideEffects = session.indexOf("pollHandler.post(onCommitted)")

        assertTrue(historyOutcome >= 0, "merged keys must reach history first")
        assertTrue(nonemptyGuard > historyOutcome, "empty merges must suppress side effects")
        assertTrue(sideEffects > nonemptyGuard, "successful merge side effects must return on Main")
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue(startIndex >= 0, "section start is missing: $start")

        val contentStart = startIndex + start.length
        val endIndex = source.indexOf(end, contentStart)
        assertTrue(endIndex >= contentStart, "section end is missing: $end")

        return source.substring(contentStart, endIndex)
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
        const val CANVAS_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt"
        const val ENGINE_SESSION_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/EngineSession.kt"
    }
}
