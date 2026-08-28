package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

        val configureCall = factory.indexOf(CONFIGURE_CALL)
        val sessionPublish = factory.indexOf(SESSION_PUBLISH_CALL)
        assertTrue(configureCall >= 0, "initial configuration call is missing")
        assertTrue(sessionPublish >= 0, "session publication call is missing")
        assertTrue(
            configureCall < sessionPublish,
            "initial appearance must be queued before the session can bootstrap",
        )

        assertTrue(
            APPLY_APPEARANCE_CALL in configure,
            "initial configure must use the shared GL-thread appearance adapter",
        )
        for (assignment in APPEARANCE_ASSIGNMENTS) {
            assertTrue(assignment in update, "live update is missing $assignment")
        }

        val firstAppearance = configure.indexOf(APPLY_APPEARANCE_CALL)
        val firstRedraw = configure.indexOf(REDRAW_CALL)
        assertTrue(firstAppearance >= 0, "initial appearance application is missing")
        assertTrue(firstRedraw >= 0, "initial redraw is missing")
        assertTrue(
            firstAppearance < firstRedraw,
            "initial appearance must reach GL before its first redraw",
        )
        assertTrue(
            "pendingCanvasAppearance = null" in configure,
            "a full configure must supersede any deferred appearance",
        )
    }

    @Test
    fun `appearance changes wait for the active stroke boundary`() {
        val session = source(ENGINE_SESSION_PATH)
        val update = section(session, APPEARANCE_START, APPEARANCE_END)
        val endStroke = section(session, END_STROKE_START, END_STROKE_END)
        val cancel = section(session, CANCEL_STROKE_START, CANCEL_STROKE_END)

        assertTrue(
            ACTIVE_APPEARANCE_GUARD.containsMatchIn(update),
            "an active stroke must defer both appearance mutation and redraw",
        )

        val guardMatch = ACTIVE_APPEARANCE_GUARD.find(update)
            ?: fail("an active stroke must defer appearance mutation")
        val immediateMatch = IMMEDIATE_APPEARANCE_SUPERSEDES_DEFERRED.find(update)
            ?: fail("an immediate appearance must supersede any deferred value")
        assertTrue(
            guardMatch.range.first < immediateMatch.range.first,
            "the active-stroke guard must run before the immediate apply",
        )

        val boundaries = listOf(
            "commit" to (endStroke to END_STROKE_APPLY_TARGET),
            "cancel" to (cancel to CANCEL_STROKE_APPLY_TARGET),
        )
        for ((name, pair) in boundaries) {
            val apply = pair.first.indexOf(APPLY_DEFERRED_APPEARANCE)
            val target = pair.first.indexOf(pair.second)
            assertTrue(apply >= 0, "$name must apply the deferred appearance")
            assertTrue(target >= 0, "$name stroke target is missing")
            assertTrue(apply < target, "$name must apply appearance before its scene transition")
        }

        assertEquals(
            1,
            cancel.split(DEFERRED_APPEARANCE_RESTORE).size - 1,
            "cancel's dead-surface return must restore the deferred appearance",
        )
        assertEquals(
            2,
            endStroke.split(DEFERRED_APPEARANCE_RESTORE).size - 1,
            "both early commit returns must restore the deferred appearance",
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
        const val SURFACE_UPDATE_START = "update = { surface ->"
        const val CONFIGURE_START = "internal fun configure("
        const val CONFIGURE_END = "fun setView("
        const val APPEARANCE_START = "fun setCanvasAppearance("
        const val APPEARANCE_END = "fun sampleColor("
        const val END_STROKE_START = "fun endStroke("
        const val END_STROKE_END = "private fun completeStrokeWithoutMerge("
        const val CANCEL_STROKE_START = "internal fun cancelStroke("
        const val CANCEL_STROKE_END = "fun invalidate("
        const val END_STROKE_APPLY_TARGET = "renderer.endStroke("
        const val CANCEL_STROKE_APPLY_TARGET = "renderer.cancelStroke"
        const val OPACITY_BUTTON_START = "onClick = onOpacityClick,"
        const val OPACITY_BUTTON_END = "Box {"
        const val THEME_APPEARANCE = "val canvasAppearance = CanvasAppearance("
        const val SURFACE_APPEARANCE = "appearance = canvasAppearance,"
        const val APPEARANCE_CALL = "session?.setCanvasAppearance("
        const val INITIAL_APPEARANCE = "appearance = appearance,"
        const val CONFIGURE_CALL = "session.configure("
        const val SESSION_PUBLISH_CALL = "onSession(session)"
        const val REDRAW_CALL = "redraw()"
        const val APPLY_APPEARANCE_CALL = "applyCanvasAppearance(appearance)"
        // The session stores the deferred value as `pendingCanvasAppearance` and
        // snapshots it into a local `deferredAppearance` in endStroke/cancelStroke.
        const val APPLY_DEFERRED_APPEARANCE = "deferredAppearance?.let(::applyCanvasAppearance)"
        const val DEFERRED_APPEARANCE_RESTORE = "pendingCanvasAppearance = deferredAppearance"
        val APPEARANCE_ASSIGNMENTS = listOf(
            "renderer.checkerPx = appearance.checkerPx",
            "renderer.checkerA = appearance.checkerA",
            "renderer.checkerB = appearance.checkerB",
            "renderer.canvasVoid = appearance.canvasVoid",
        )
        val ACTIVE_APPEARANCE_GUARD = Regex(
            """if\s*\(activeStrokeSpec\s*!=\s*null\)\s*\{\s*""" +
                """pendingCanvasAppearance\s*=\s*appearance\s*""" +
                """redraw\(\)\s*return\s*\}""",
        )
        val IMMEDIATE_APPEARANCE_SUPERSEDES_DEFERRED = Regex(
            """pendingCanvasAppearance\s*=\s*null\s*""" +
                """glRenderer\.execute\s*\{\s*applyCanvasAppearance\(appearance\)\s*\}""",
        )
        val SECONDARY_SELECTION_MARKER = Regex(
            """if\s*\(selected\)\s*MaterialTheme\.colorScheme\.secondary\s*else\s*Color\.Transparent""",
        )
    }
}
