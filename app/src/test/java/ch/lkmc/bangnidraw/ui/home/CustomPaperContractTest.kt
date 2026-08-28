package ch.lkmc.bangnidraw.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the custom paper colour's wiring (08 §2.1's "+" swatch): the dialog
 * owns a picked custom colour, the picker edits it through the shared HSV
 * channel model, and paper stays opaque.
 */
class CustomPaperContractTest {

    @Test
    fun `the new canvas dialog carries a custom paper swatch and picker`() {
        val dialog = source(NEW_CANVAS_DIALOG_PATH)

        assertTrue("R.string.paper_custom" in dialog)
        assertTrue("CustomPaperSwatch(" in dialog)
        assertTrue("PaperColorDialog(" in dialog)
        // The shared channel model: same ranges and steps as the colour panel.
        assertTrue("HsvChannel.entries" in dialog)
        // Paper is opaque; the picked colour's alpha is pinned, not typed.
        assertTrue("or OPAQUE_ALPHA" in dialog)
        // The picker's confirm selects the colour for creation.
        assertTrue(Regex("paper\\s*=\\s*color").containsMatchIn(dialog))
        // A custom colour equal to a fixed swatch must not read as double-selected.
        assertTrue("paperSwatchColors().none" in dialog)
        // The HSV draft survives rotation as raw channel floats (HsvColor is
        // not Bundle-savable, and an ARGB round-trip collapses hue on greys).
        assertTrue("listSaver(" in dialog)
        assertTrue("rememberSaveable(initial)" !in dialog)
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
    }
}
