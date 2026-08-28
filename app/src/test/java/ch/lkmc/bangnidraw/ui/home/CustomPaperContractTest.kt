package ch.lkmc.bangnidraw.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The sixth paper swatch picks any colour (glm.md §A.8 / ANALYSIS #8): the
 * dialog keeps the custom choice alongside the fixed five, and Create passes
 * it through the same `onCreate(size, paperColor)` seam as every preset.
 */
class CustomPaperContractTest {

    @Test
    fun `a custom paper swatch routes through the existing create seam`() {
        val dialog = source(NEW_CANVAS_DIALOG_PATH)

        assertTrue("paperIsCustom" in dialog, "selection must distinguish the custom swatch")
        assertTrue(
            CREATE_SEAM.containsMatchIn(dialog),
            "Create must pass the custom colour through the same seam as the presets",
        )
        assertTrue(
            PAPER_CUSTOM_LABEL.containsMatchIn(dialog),
            "the custom swatch is labelled",
        )
    }

    @Test
    fun `the picker edits HSV through the shared channel math`() {
        val dialog = source(NEW_CANVAS_DIALOG_PATH)

        assertTrue("HsvChannel.entries" in dialog, "sliders must derive from HsvChannel")
        assertTrue("HsvColor.fromArgb" in dialog, "the picker opens on the current colour")
        assertTrue("hsv.toArgb()" in dialog, "the choice commits as ARGB, like every preset")
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
        const val NEW_CANVAS_DIALOG_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/NewCanvasDialog.kt"

        /** Whitespace-tolerant: line wrapping cannot break the contract. */
        val CREATE_SEAM = Regex(
            """onCreate\(\s*it\.preset\.size,\s*if\s*\(\s*paperIsCustom\s*\)\s*customPaper\s*else\s*paper\s*\)""",
        )

        /** Word-boundary: does not match `paper_custom_preview`. */
        val PAPER_CUSTOM_LABEL = Regex("""R\.string\.paper_custom\b""")
    }
}
