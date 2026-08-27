package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class EyedropperStrokeContractTest {

    @Test
    fun `pen up invalidates queued picks before committing`() {
        val source = File(repositoryRoot(), CANVAS_SCREEN_PATH).readText()
        val endHandler = section(source, STROKE_END_START, STROKE_CANCEL_START)
        val pickBranch = requireNotNull(PICK_BRANCH.find(endHandler)?.value) {
            "pen-up pick branch is missing"
        }
        val invalidation = pickBranch.indexOf(PICK_INVALIDATION)
        val commit = pickBranch.indexOf(PICK_COMMIT)

        assertTrue(invalidation >= 0, "pen-up must invalidate queued pick callbacks")
        assertTrue(invalidation < commit, "queued picks must be invalid before commit")
    }

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
        const val STROKE_END_START = "override fun onStrokeEnd(pointerId: Int)"
        const val STROKE_CANCEL_START = "override fun onStrokeCancel()"
        const val PICK_INVALIDATION = "strokeState.nextPickGeneration()"
        const val PICK_COMMIT = "viewModel.commitPickedColor()"
        val PICK_BRANCH = Regex(
            """if \(strokeState\.pickParams != null\) \{[\s\S]*?\n\s+return\n\s+\}""",
        )
    }
}
