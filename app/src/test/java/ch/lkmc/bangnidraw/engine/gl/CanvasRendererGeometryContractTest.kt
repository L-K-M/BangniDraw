package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasRendererGeometryContractTest {

    @Test
    fun `front damage redraws every tile under its inflated scissor`() {
        val source = File(repositoryRoot(), CANVAS_RENDERER_PATH).readText()
        val strokeFrame = source.substringAfter(STROKE_FRAME_START).substringBefore(STROKE_FRAME_END)

        assertTrue(
            CANVAS_COVERAGE_CALL in strokeFrame,
            "front damage must inverse-map its inflated window scissor",
        )
        assertTrue(
            CANVAS_COVERAGE_DRAW in strokeFrame,
            "front composition must consume the inverse-mapped coverage",
        )
    }

    @Test
    fun `pre-rotated present quad keeps logical dimensions`() {
        val source = File(repositoryRoot(), CANVAS_RENDERER_PATH).readText()
        val present = source.substringAfter(PRESENT_START).substringBefore(PRESENT_END)

        assertTrue(
            LOGICAL_QUAD_DRAW in present,
            "the buffer transform consumes a logical-size quad",
        )
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
        const val CANVAS_RENDERER_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/CanvasRenderer.kt"
        const val STROKE_FRAME_START = "fun drawStrokeFrame("
        const val STROKE_FRAME_END = "/**\n     * Hands one front-buffered frame"
        const val PRESENT_START = "private fun presentToWindow("
        const val PRESENT_END = "private fun rebuildSandwichIfNeeded("
        const val CANVAS_COVERAGE_CALL = "screenTransform.canvasBoundsOf("
        const val CANVAS_COVERAGE_DRAW =
            "pass,\n                compositeCanvasRect,\n                compositeWindowRect,"
        const val LOGICAL_QUAD_DRAW =
            "screenQuad.draw(accum.width.toFloat(), accum.height.toFloat())"
    }
}
