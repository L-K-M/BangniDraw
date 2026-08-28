package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the panels' close affordance: every canvas panel header shows one and
 * the screen wires each sheet's dismiss callback. The scrim tap that also
 * dismisses is invisible to a first-time user; the icon is the discoverable
 * path (08 §4.1 keeps both).
 */
class PanelCloseContractTest {

    @Test
    fun `every canvas panel header carries the close affordance`() {
        val header = source(PANEL_HEADER_PATH)
        assertTrue("Icons.Filled.Close" in header, "PanelHeader must render Icons.Filled.Close")
        assertTrue("R.string.panel_close" in header, "PanelHeader must announce R.string.panel_close")

        // The shared header covers the tool and colour sheets; the layer
        // panel's own header uses the same affordance through the button.
        mapOf(
            COLOR_PANEL_PATH to "PanelHeader(",
            BRUSH_SHEET_PATH to "PanelHeader(",
            FILL_SHEET_PATH to "PanelHeader(",
            RMW_SHEET_PATH to "PanelHeader(",
            LAYER_PANEL_PATH to "PanelCloseButton(",
        ).forEach { (path, marker) ->
            assertTrue(marker in source(path), "missing marker [$marker] in $path")
        }
    }

    @Test
    fun `the screen wires every sheet's dismiss`() {
        val screen = source(CANVAS_SCREEN_PATH)

        // Every call site of a dismissable sheet must wire the callback —
        // counted from the call sites, not a hand-kept constant, so a new
        // sheet kind cannot silently stay green. TracingReferencePanel is
        // excluded: it already carries a Done button.
        val sheetCallSites = SHEET_CALL_SITE.findAll(screen).count()
        val wirings = DISMISS_WIRING.findAll(screen).count()
        assertTrue(
            wirings == sheetCallSites,
            "expected every sheet call site ($sheetCallSites) to wire onDismiss, found $wirings",
        )
    }

    private fun source(path: String): String = File(repositoryRoot(), path).readText()

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
        const val CANVAS_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasScreen.kt"
        const val COLOR_PANEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ColorPanel.kt"
        const val BRUSH_SHEET_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/BrushSettingsSheet.kt"
        const val FILL_SHEET_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/FillSettingsSheet.kt"
        const val RMW_SHEET_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/RmwSettingsSheet.kt"
        const val LAYER_PANEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/LayerPanel.kt"
        const val PANEL_HEADER_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/PanelHeader.kt"

        val DISMISS_WIRING = Regex("onDismiss = viewModel::dismissPanel")
        val SHEET_CALL_SITE = Regex(
            "\\b(ColorPanel|BrushSettingsSheet|SmudgeSettingsSheet|WaterSettingsSheet|" +
                "BlurSettingsSheet|EyedropperSettingsSheet|FillSettingsSheet|LayerPanel)\\(",
        )
    }
}
