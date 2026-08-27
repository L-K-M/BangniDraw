package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasRendererGeometryContractTest {

    @Test
    fun `paper uses canvas geometry and the same screen transform as tiles`() {
        val source = File(repositoryRoot(), CANVAS_RENDERER_PATH).readText()
        val composite = section(source, COMPOSITE_START, COMPOSITE_END)
        val paper = section(source, PAPER_START, PAPER_END)

        assertTrue(PAPER_CALL in composite, "the frame compositor must draw its paper")
        assertTrue(
            PAPER_SCREEN_UNIFORM.containsMatchIn(paper),
            "paper must use the renderer's composed canvas-to-screen transform",
        )
        assertTrue(
            PAPER_CANVAS_QUAD_DRAW in paper,
            "paper must stop at the transformed canvas edges, not fill the viewport",
        )
        assertTrue(
            SCREEN_QUAD_DRAW !in paper,
            "the viewport present quad must not double as the canvas-sized paper quad",
        )
    }

    @Test
    fun `canvas void is cleared behind paper and owns a dedicated quad lifetime`() {
        val source = File(repositoryRoot(), CANVAS_RENDERER_PATH).readText()
        val paper = section(source, PAPER_START, PAPER_END)
        val release = section(source, RELEASE_START, RELEASE_END)

        val clear = paper.indexOf(VOID_CLEAR)
        val draw = paper.indexOf(PAPER_CANVAS_QUAD_DRAW)
        assertTrue(clear >= 0, "Accum must start with the themed canvas void")
        assertTrue(draw > clear, "the void clear must stay behind the paper draw")
        assertTrue(
            PAPER_QUAD_DECLARATION in source,
            "canvas and viewport geometry need separate caches",
        )
        assertTrue(
            PAPER_QUAD_RELEASE in release,
            "the dedicated paper quad must be released with the other GL geometry",
        )
    }

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

    @Test
    fun `accum and window targets keep their distinct row conventions`() {
        val source = File(repositoryRoot(), CANVAS_RENDERER_PATH).readText()
        val composite = section(source, COMPOSITE_START, COMPOSITE_END)
        val present = section(source, PRESENT_START, PRESENT_END)

        assertTrue(
            ACCUM_SCISSOR_FLIP in composite,
            "the viewport-oriented Accum texture still needs GL's lower-left conversion",
        )
        assertTrue(
            HARDWARE_BUFFER_SCISSOR in present,
            "the SurfaceControl target must keep top-first HardwareBuffer rows",
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

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue(startIndex >= 0, "section start is missing: $start")
        val contentStart = startIndex + start.length
        val endIndex = source.indexOf(end, contentStart)
        assertTrue(endIndex >= contentStart, "section end is missing: $end")
        return source.substring(contentStart, endIndex)
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
        const val COMPOSITE_START = "private fun compositeIntoAccum("
        const val COMPOSITE_END = "private fun drawLayer("
        const val PAPER_START = "private fun drawPaper("
        const val PAPER_END = "private fun setColorUniform("
        const val RELEASE_START = "fun release() {"
        const val RELEASE_END = "private fun failQueuedThumbnails("
        const val CANVAS_COVERAGE_CALL = "screenTransform.canvasBoundsOf("
        const val CANVAS_COVERAGE_DRAW =
            "pass,\n                compositeCanvasRect,\n                compositeWindowRect,"
        const val LOGICAL_QUAD_DRAW =
            "screenQuad.draw(accum.width.toFloat(), accum.height.toFloat())"
        const val ACCUM_SCISSOR_FLIP = "accum.height - accumScissor.bottom"
        const val HARDWARE_BUFFER_SCISSOR =
            "BufferScissor.toHardwareBufferScissor(scissor, bufferHeight, scissorScratch)"
        const val PAPER_CALL = "drawPaper(screenTransform, bakedIntoBelow = useSandwich)"
        val PAPER_SCREEN_UNIFORM = Regex(
            """program\.uniform4f\(\s*"u_screen",\s*screenTransform\.a,\s*""" +
                """screenTransform\.b,\s*screenTransform\.tx,\s*""" +
                """screenTransform\.ty,?\s*\)""",
        )
        const val PAPER_CANVAS_QUAD_DRAW =
            "paperQuad.draw(canvas.width.toFloat(), canvas.height.toFloat())"
        const val SCREEN_QUAD_DRAW = "screenQuad.draw("
        const val VOID_CLEAR = "clearColor(canvasVoid)"
        const val PAPER_QUAD_DECLARATION = "private val paperQuad = FullRectQuad()"
        const val PAPER_QUAD_RELEASE = "paperQuad.release()"
    }
}
