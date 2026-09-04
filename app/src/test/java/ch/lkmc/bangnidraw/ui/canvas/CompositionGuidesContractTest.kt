package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the composition-guides wiring (the overflow toggle and the overlay):
 * the menu item carries the toggle with a check when on, and the overlay is
 * fed the touch handler's live screen transform so the guides follow the
 * canvas while navigating.
 */
class CompositionGuidesContractTest {

    @Test
    fun `overflow menu exposes the toggle`() {
        val menu = sourceSection(
            TOP_STRIP_PATH,
            OVERFLOW_MENU_START,
            OVERFLOW_ITEM_START,
        ).replace(WHITESPACE, " ")

        listOf(
            "R.string.canvas_guides",
            "guideVisibility",
            "onToggleGuides()",
            "Icons.Filled.Check",
            "selected = guideVisibility == CompositionGuideVisibility.VISIBLE",
        ).forEach { marker ->
            assertTrue(marker in menu, "missing marker [$marker]")
        }
    }

    @Test
    fun `overlay maps through the touch handler's transform`() {
        val screen = sourceSection(
            CANVAS_SCREEN_PATH,
            OVERLAY_START,
            OVERLAY_END,
        ).replace(WHITESPACE, " ")

        listOf(
            "visibility = state.compositionGuideVisibility",
            "canvas = state.canvas",
            "screenTransform = touch.screenTransform",
        ).forEach { marker ->
            assertTrue(marker in screen, "missing marker [$marker]")
        }

        val overlay = File(repositoryRoot(), COMPOSITION_GUIDES_PATH).readText()
            .replace(WHITESPACE, " ")
        listOf(
            "transform.canvasPerScreen",
            "transform.offset(center.x - tick, center.y)",
            "transform.offset(center.x, center.y - tick)",
        ).forEach { marker ->
            assertTrue(marker in overlay, "missing marker [$marker]")
        }
    }

    private fun sourceSection(path: String, start: String, end: String): String {
        val source = File(repositoryRoot(), path).readText()
        val startIndex = source.indexOf(start)
        if (startIndex < 0) fail("missing source marker: $start")

        val endIndex = source.indexOf(end, startIndex)
        if (endIndex <= startIndex) fail("missing source marker: $end")

        return source.substring(startIndex, endIndex)
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
        const val TOP_STRIP_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/TopStrip.kt"
        const val CANVAS_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt"
        const val COMPOSITION_GUIDES_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/shared/CompositionGuides.kt"
        const val OVERFLOW_MENU_START = "private fun OverflowMenu("
        const val OVERFLOW_ITEM_START = "private fun OverflowItem("
        const val OVERLAY_START = "CompositionGuides("
        const val OVERLAY_END = "HoverCursor("
        val WHITESPACE = Regex("\\s+")
    }
}
