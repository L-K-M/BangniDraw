package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the reset pill's two anchors: tap resets to fit, long-press jumps to
 * actual size through the touch handler's policy (the handler owns the fit,
 * so `1 / fit.scale` is computed there, never in the composable).
 */
class ActualSizeContractTest {

    @Test
    fun `the pill offers the long-press and the screen wires it`() {
        val pill = source(RESET_PILL_PATH)
        assertTrue("onLongClick = onActualSize" in pill)
        assertTrue("onLongClickLabel = stringResource(R.string.canvas_actual_size)" in pill)
        // A button inside a long-press wrapper would fire its own click on
        // the same release — the pill must not nest one.
        assertTrue("FilledTonalButton" !in pill)

        val screen = source(CANVAS_SCREEN_PATH)
        assertTrue("onActualSize = actualSizeView" in screen)
        assertTrue("touch.actualSizeView()" in screen)
    }

    // Whitespace-collapsed so reformatting the source cannot break the
    // contract; the assertions pin identifiers, not layout.
    private fun source(path: String): String =
        File(repositoryRoot(), path).readText().replace(WHITESPACE, " ")

    private fun repositoryRoot(): File {
        val userDirectory = System.getProperty(USER_DIRECTORY_PROPERTY)
            ?: fail("$USER_DIRECTORY_PROPERTY is unavailable")
        val workingDirectory = File(userDirectory).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        val WHITESPACE = Regex("\\s+")
        const val RESET_PILL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ResetViewPill.kt"
        const val CANVAS_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt"
    }
}
