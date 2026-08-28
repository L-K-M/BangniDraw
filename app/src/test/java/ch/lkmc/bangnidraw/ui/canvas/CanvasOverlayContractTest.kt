package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasOverlayContractTest {

    @Test
    fun `bottom overlays share one stacked anchor`() {
        val source = File(repositoryRoot(), CANVAS_SCREEN_PATH).readText()

        val start = source.indexOf(OVERLAY_REGION_START)
        if (start < 0) fail("missing overlay region start")

        val end = source.indexOf(OVERLAY_REGION_END, start)
        if (end <= start) fail("missing overlay region end")

        val overlays = source.substring(start, end)

        assertEquals(
            1,
            BOTTOM_CENTER.findAll(overlays).count(),
            "bottom overlays must not own competing anchors",
        )
        assertTrue(STACK_SPACING.containsMatchIn(overlays))
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
        const val OVERLAY_REGION_START =
            "val overlayBottomPadding"
        const val OVERLAY_REGION_END =
            "val panel = state.chrome.openPanel"
        val BOTTOM_CENTER = Regex("""\.align\s*\(\s*Alignment\.BottomCenter\s*\)""")
        val STACK_SPACING = Regex(
            """verticalArrangement\s*=\s*Arrangement\.spacedBy\s*\(\s*BOTTOM_OVERLAY_GAP\s*\)""",
        )
    }
}
