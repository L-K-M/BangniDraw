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

        assertTrue(COMMIT_HELPER_CALL in redraw, "redraw must use graphics-core commit sequencing")
        assertFalse(
            DIRECT_MULTI_CALL in redraw,
            "direct multi rendering bypasses graphics-core's front release barrier",
        )
    }

    @Test
    fun `the commit helper registers coordinated rendering first`() {
        val source = source(ENGINE_SESSION_PATH)
        val helper = section(source, COMMIT_HELPER_START, COMMIT_HELPER_END)

        val registration = helper.indexOf(REGISTER_COMMIT_CALL)
        val commit = helper.indexOf(COMMIT_CALL)
        assertTrue(registration >= 0, "commit coordination is missing")
        assertTrue(QUEUED_REGISTER_COMMIT_CALL in helper, "commit coordination must use the GL queue")
        assertTrue(commit > registration, "coordination must precede the library commit")
    }

    @Test
    fun `scene changes invalidate recovery inside the GL queue`() {
        val source = source(ENGINE_SESSION_PATH)
        val helper = section(source, SCENE_HELPER_START, SCENE_HELPER_END)

        val mutation = helper.indexOf(SCENE_MUTATION_CALL)
        val invalidation = helper.indexOf(SCENE_CHANGED_CALL)
        assertTrue(mutation >= 0, "the queued scene mutation is missing")
        assertTrue(invalidation > mutation, "recovery must follow the queued scene mutation")
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

    @Test
    fun `replacement touch handler receives the current viewport`() {
        val source = source(CANVAS_SURFACE_PATH)
        val update = section(source, UPDATE_START, UPDATE_END)

        val viewport = update.indexOf(SET_VIEWPORT_CALL)
        val listener = update.indexOf(SET_TOUCH_LISTENER_CALL)

        assertTrue(viewport >= 0, "the current handler must receive the existing surface size")
        assertTrue(viewport < listener, "the handler needs its fit before input is attached")
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
        const val COMMIT_HELPER_START = "private fun commitMultiBuffered()"
        const val COMMIT_HELPER_END = "/** Runs [block] on the GL thread. */"
        const val SCENE_HELPER_START = "private fun executeSceneChange("
        const val SCENE_HELPER_END = "/** Runs [block] on the GL thread. */"
        const val CONFIGURE_START = "internal fun configure("
        const val CONFIGURE_END = "/**\n     * Sets the view transform and redraws."
        const val FACTORY_START = "factory = { ctx ->"
        const val FACTORY_END = "update = { surface ->"
        const val UPDATE_START = "update = { surface ->"
        const val UPDATE_END = "// A multi-buffer redraw hides live front-buffer ink."
        const val COMMIT_CALL = "frontBuffered.commit()"
        const val COMMIT_HELPER_CALL = "commitMultiBuffered()"
        const val REGISTER_COMMIT_CALL = "renderPolicy.registerCommit()"
        const val QUEUED_REGISTER_COMMIT_CALL =
            "frontBuffered.execute { renderPolicy.registerCommit() }"
        const val SCENE_MUTATION_CALL = "block()"
        const val SCENE_CHANGED_CALL = "renderPolicy.sceneChanged()"
        const val DIRECT_MULTI_CALL = "frontBuffered.renderMultiBufferedLayer("
        const val CONFIGURE_CALL = "session.configure(stack, paperColor, view)"
        const val SET_STACK_CALL = "session.setStack(stack)"
        const val SET_PAPER_CALL = "session.setPaperColor(paperColor)"
        const val SET_VIEW_CALL = "session.setView(view)"
        const val RENDERER_STACK_CALL = "renderer.setStack(stack)"
        const val RENDERER_PAPER_CALL = "renderer.setPaperColor(paperColor)"
        const val RENDERER_VIEW_CALL = "renderer.setView(view)"
        const val SET_VIEWPORT_CALL =
            "touchHandler?.setViewport(canvas, surface.width, surface.height)"
        const val SET_TOUCH_LISTENER_CALL = "surface.setOnTouchListener(touchHandler)"
        val REDRAW_CALL = Regex("""\bredraw\(\)""")
    }
}
