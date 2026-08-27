package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class VisibleCanvasBoundsTest {

    @Test
    fun `inverse viewport bounds reuse the caller scratch`() {
        val transform = ScreenTransform(a = 2f, b = 0f, tx = -20f, ty = -40f)
        val out = MutableIntRect()

        val first = transform.canvasBoundsOfViewport(
            viewportWidth = 200,
            viewportHeight = 100,
            canvasWidth = 256,
            canvasHeight = 256,
            margin = 8,
            out = out,
        )
        val second = transform.canvasBoundsOfViewport(
            viewportWidth = 100,
            viewportHeight = 100,
            canvasWidth = 256,
            canvasHeight = 256,
            margin = 8,
            out = out,
        )

        assertSame(out, first)
        assertSame(out, second)
        assertEquals(2, out.left)
        assertEquals(12, out.top)
        assertEquals(68, out.right)
        assertEquals(78, out.bottom)
    }
}
