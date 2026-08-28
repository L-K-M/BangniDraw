package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasAppearanceContractTest {

    @Test
    fun `theme canvas appearance reaches GL before bootstrap and keeps updating`() {
        val screen = source(CANVAS_SCREEN_PATH)
        val surface = source(CANVAS_SURFACE_PATH)
        val session = source(ENGINE_SESSION_PATH)
        val factory = section(surface, SURFACE_FACTORY_START, SURFACE_UPDATE_START)
        val configure = section(session, CONFIGURE_START, CONFIGURE_END)
        val update = section(session, APPEARANCE_START, APPEARANCE_END)

        assertTrue(THEME_APPEARANCE in screen, "CanvasScreen must resolve themed appearance")
        assertTrue(SURFACE_APPEARANCE in screen, "CanvasScreen must pass appearance to its surface")
        assertTrue(APPEARANCE_CALL in screen, "theme changes must update the live session")
        assertTrue(
            INITIAL_APPEARANCE in factory,
            "CanvasSurface must include appearance in initial configuration",
        )
        assertTrue(
            factory.indexOf("session.configure(") < factory.indexOf("onSession(session)"),
            "initial appearance must be queued before the session can bootstrap",
        )

        for (assignment in APPEARANCE_ASSIGNMENTS) {
            assertTrue(assignment in configure, "initial configure is missing $assignment")
        }
        for (assignment in LIVE_APPEARANCE_ASSIGNMENTS) {
            assertTrue(assignment in update, "live update is missing $assignment")
        }

        assertTrue(
            configure.indexOf(APPEARANCE_ASSIGNMENTS.first()) < configure.indexOf("redraw()"),
            "initial appearance must reach GL before its first redraw",
        )
    }

    @Test
    fun `canvas selections use paired theme roles`() {
        val layers = source(LAYER_PANEL_PATH)
        val tools = source(TOOL_RAIL_PATH)
        val opacityButton = section(layers, OPACITY_BUTTON_START, OPACITY_BUTTON_END)

        assertTrue(
            "if (selected) MaterialTheme.colorScheme.secondaryContainer" in layers,
            "selected layers need the secondary container",
        )
        assertTrue(
            SECONDARY_SELECTION_MARKER.containsMatchIn(layers),
            "selected layers need the secondary marker",
        )
        assertTrue(
            "if (selected) MaterialTheme.colorScheme.onSecondaryContainer" in layers,
            "selected layer captions need the paired content role",
        )
        assertTrue(
            "color = captionColor" in opacityButton,
            "selected layer opacity text needs the paired content role",
        )
        assertTrue(
            "val temporaryColor = buttonColors.icon" in tools,
            "temporary tool rings need the active container's contrast-safe role",
        )
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
        const val CANVAS_SURFACE_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasSurface.kt"
        const val LAYER_PANEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/LayerPanel.kt"
        const val TOOL_RAIL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/ToolRail.kt"
        const val ENGINE_SESSION_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/EngineSession.kt"
        const val SURFACE_FACTORY_START = "factory = { ctx ->"
        const val SURFACE_UPDATE_START = "        update = { surface ->"
        const val CONFIGURE_START = "internal fun configure("
        const val CONFIGURE_END = "    /**\n     * Sets the view transform"
        const val APPEARANCE_START = "fun setCanvasAppearance("
        const val APPEARANCE_END = "fun sampleColor("
        const val OPACITY_BUTTON_START = "onClick = onOpacityClick,"
        const val OPACITY_BUTTON_END = "            Box {"
        const val THEME_APPEARANCE = "val canvasAppearance = CanvasAppearance("
        const val SURFACE_APPEARANCE = "appearance = canvasAppearance,"
        const val APPEARANCE_CALL = "session?.setCanvasAppearance("
        const val INITIAL_APPEARANCE = "appearance = appearance,"
        val APPEARANCE_ASSIGNMENTS = listOf(
            "renderer.checkerPx = appearance.checkerPx",
            "renderer.checkerA = appearance.checkerA",
            "renderer.checkerB = appearance.checkerB",
            "renderer.canvasVoid = appearance.canvasVoid",
        )
        val LIVE_APPEARANCE_ASSIGNMENTS = listOf(
            "renderer.checkerPx = checkerPx",
            "renderer.checkerA = colorA",
            "renderer.checkerB = colorB",
            "renderer.canvasVoid = canvasVoid",
        )
        val SECONDARY_SELECTION_MARKER = Regex(
            """if\s*\(selected\)\s*MaterialTheme\.colorScheme\.secondary\s*else\s*Color\.Transparent""",
        )
    }
}
