package ch.lkmc.bangnidraw.ui.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class EngineSessionRenderContractTest {

    @Test
    fun `generic redraw uses the commit release barrier`() {
        val source = source(ENGINE_SESSION_PATH)
        val redraw = section(source, REDRAW_START, REDRAW_END)

        assertTrue(COMMIT_CALL in redraw, "redraw must use graphics-core commit sequencing")
        assertFalse(
            DIRECT_MULTI_CALL in redraw,
            "direct multi rendering bypasses graphics-core's front release barrier",
        )
    }

    @Test
    fun `canvas startup configures one scene before one redraw`() {
        val source = source(CANVAS_SURFACE_PATH)
        val factory = section(source, FACTORY_START, FACTORY_END)

        assertTrue(CONFIGURE_CALL in factory, "startup scene configuration is missing")
        assertFalse(SET_STACK_CALL in factory, "startup must not queue a stack redraw")
        assertFalse(SET_PAPER_CALL in factory, "startup must not queue a paper redraw")
        assertFalse(SET_VIEW_CALL in factory, "startup must not queue a view redraw")
    }

    @Test
    fun `scene configuration applies all values before one redraw`() {
        val source = source(ENGINE_SESSION_PATH)
        val configure = section(source, CONFIGURE_START, CONFIGURE_END)

        assertTrue(RENDERER_STACK_CALL in configure, "startup stack configuration is missing")
        assertTrue(RENDERER_PAPER_CALL in configure, "startup paper configuration is missing")
        assertTrue(RENDERER_VIEW_CALL in configure, "startup view configuration is missing")
        assertEquals(1, REDRAW_CALL.findAll(configure).count(), "startup must redraw once")
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
        const val ENGINE_SESSION_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/EngineSession.kt"
        const val CANVAS_SURFACE_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasSurface.kt"
        const val REDRAW_START = "private fun redrawNow()"
        const val REDRAW_END = "/** Runs [block] on the GL thread. */"
        const val CONFIGURE_START = "internal fun configure("
        const val CONFIGURE_END = "/**\n     * Sets the view transform and redraws."
        const val FACTORY_START = "factory = { ctx ->"
        const val FACTORY_END = "update = { surface ->"
        const val COMMIT_CALL = "frontBuffered.commit()"
        const val DIRECT_MULTI_CALL = "frontBuffered.renderMultiBufferedLayer("
        const val CONFIGURE_CALL = "session.configure(stack, paperColor, view)"
        const val SET_STACK_CALL = "session.setStack(stack)"
        const val SET_PAPER_CALL = "session.setPaperColor(paperColor)"
        const val SET_VIEW_CALL = "session.setView(view)"
        const val RENDERER_STACK_CALL = "renderer.setStack(stack)"
        const val RENDERER_PAPER_CALL = "renderer.setPaperColor(paperColor)"
        const val RENDERER_VIEW_CALL = "renderer.setView(view)"
        val REDRAW_CALL = Regex("""\bredraw\(\)""")
    }
}
