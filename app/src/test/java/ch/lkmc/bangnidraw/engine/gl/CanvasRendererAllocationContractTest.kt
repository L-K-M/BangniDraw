package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasRendererAllocationContractTest {

    @Test
    fun `sandwich frame path reuses bounds and resolver`() {
        val renderer = source(CANVAS_RENDERER_PATH)
        val sync = renderer.substringAfter(SYNC_START).substringBefore(SYNC_END)
        val bounds = renderer.substringAfter(BOUNDS_START).substringBefore(BOUNDS_END)

        assertTrue(PENDING_GATE in sync, "idle caches must skip viewport rebuild work")
        assertTrue(OUT_BOUNDS_CALL in bounds, "visible bounds need caller-owned storage")
        assertFalse(CAPTURING_RESOLVER.containsMatchIn(sync), "rebuild must reuse its resolver")
        assertFalse(INT_RECT_CONSTRUCTION in bounds, "viewport bounds must not allocate IntRect")
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
        const val CANVAS_RENDERER_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/CanvasRenderer.kt"
        const val SYNC_START = "private fun readySandwichForFrame("
        const val SYNC_END = "/**\n     * The canvas-space rect"
        const val BOUNDS_START = "private fun updateVisibleCanvasBounds("
        const val BOUNDS_END = "// -------------------------------------------------------------- teardown"
        const val PENDING_GATE = "cache.hasPendingRebuild()"
        const val OUT_BOUNDS_CALL = "out = visibleCanvasBounds"
        val CAPTURING_RESOLVER = Regex("""cache\.rebuild\([^)]*\)\s*\{""")
        const val INT_RECT_CONSTRUCTION = "IntRect("
    }
}
