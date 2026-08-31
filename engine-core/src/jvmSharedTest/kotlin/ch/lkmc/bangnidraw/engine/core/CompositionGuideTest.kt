package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** The composition overlay's spec (`CompositionGuide`). */
class CompositionGuideTest {

    private val eps = 1e-4f

    @Test
    fun `thirds span the paper at exact thirds`() {
        val lines = CompositionGuide.thirds(CanvasSize(3000, 1200))

        assertEquals(4, lines.size, "two vertical and two horizontal third-lines")

        val (v1, v2, h1, h2) = lines
        // Vertical lines run the full height at x = W/3 and x = 2W/3.
        assertEquals(1000f, v1.x0, eps)
        assertEquals(v1.x0, v1.x1, eps)
        assertEquals(0f, v1.y0, eps)
        assertEquals(1200f, v1.y1, eps)
        assertEquals(2000f, v2.x0, eps)

        // Horizontal lines run the full width at y = H/3 and y = 2H/3.
        assertEquals(400f, h1.y0, eps)
        assertEquals(h1.y0, h1.y1, eps)
        assertEquals(0f, h1.x0, eps)
        assertEquals(3000f, h1.x1, eps)
        assertEquals(800f, h2.y0, eps)
    }

    @Test
    fun `center is the paper's midpoint`() {
        val (cx, cy) = CompositionGuide.center(CanvasSize(2048, 1001))
        assertEquals(1024f, cx, eps)
        assertEquals(500.5f, cy, eps)
    }

    @Test
    fun `visibility toggles both ways`() {
        assertEquals(
            CompositionGuideVisibility.VISIBLE,
            CompositionGuideVisibility.HIDDEN.toggled(),
        )
        assertEquals(
            CompositionGuideVisibility.HIDDEN,
            CompositionGuideVisibility.VISIBLE.toggled(),
        )
    }
}
