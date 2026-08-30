package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The committed (non-stroke) frame composites the VISIBLE canvas rect, not
 * the whole canvas. `CompositePass`'s cost contract — "bounded by output
 * pixels × layers and never by canvas size" — only holds when the culling
 * rect is bounded too; `fullCanvasRect` there made pan/zoom cost scale with
 * canvas size and paint coverage, at gesture frame rate. The front-buffered
 * path already passes a bounded rect; this pins the committed path beside
 * it, and pins the fallback that keeps the cull safe: a non-finite inverse
 * transform degrades to the full canvas.
 */
class CommittedFrameCullingContractTest {

    @Test
    fun `drawFrame composites the visible rect, not the whole canvas`() {
        val renderer = ContractTestSources.read(RENDERER_PATH).replace(WHITESPACE, " ")
        val start = renderer.indexOf(DRAW_FRAME_START)
        if (start < 0) fail("missing $DRAW_FRAME_START")
        val end = renderer.indexOf(STROKE_FRAME_START, start)
        if (end <= start) fail("missing $STROKE_FRAME_START after drawFrame")
        val frame = renderer.substring(start, end)

        // Both matches tolerate the optional space the whitespace collapse
        // leaves after a multiline call's opening paren — the negative one
        // especially, since a reintroduced fullCanvasRect would most likely
        // come back multiline and an exact single-line needle would miss it.
        assertTrue(
            CULLED_CALL.containsMatchIn(frame),
            "the committed frame must cull to the visible canvas rect",
        )
        assertTrue(
            !FULL_CANVAS_CALL.containsMatchIn(frame),
            "fullCanvasRect in drawFrame is the canvas-size-scaling regression this pins against",
        )
    }

    @Test
    fun `the visible rect stays canvas-clamped with a whole-canvas fallback`() {
        val renderer = ContractTestSources.read(RENDERER_PATH).replace(WHITESPACE, " ")
        val start = renderer.indexOf(VISIBLE_RECT_START)
        if (start < 0) fail("missing $VISIBLE_RECT_START")
        val body = renderer.drop(start).take(VISIBLE_RECT_SPAN)

        // Degenerate transforms compose everything rather than nothing.
        assertTrue("return IntRect(0, 0, canvas.width, canvas.height)" in body)
        // And the result never leaves the canvas, so tile selection sees the
        // same clamped domain fullCanvasRect provided.
        assertTrue(".coerceIn(0, canvas.width)" in body)
        assertTrue(".coerceIn(0, canvas.height)" in body)
    }

    private companion object {
        const val RENDERER_PATH = "app/src/main/java/ch/lkmc/bangnidraw/engine/gl/CanvasRenderer.kt"
        const val DRAW_FRAME_START = "fun drawFrame("
        const val STROKE_FRAME_START = "fun drawStrokeFrame("
        const val VISIBLE_RECT_START = "private fun visibleCanvasRect("
        const val VISIBLE_RECT_SPAN = 2_000
        val WHITESPACE = Regex("\\s+")
        val CULLED_CALL =
            Regex("compositeIntoAccum\\( ?current, screenTransform, pass, visibleCanvasRect\\(screenTransform\\)")
        val FULL_CANVAS_CALL = Regex("compositeIntoAccum\\( ?current, screenTransform, pass, fullCanvasRect")
    }
}
