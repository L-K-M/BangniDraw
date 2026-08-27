package ch.lkmc.bangnidraw.engine.core

import kotlin.math.PI
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MouseNavigationPolicyTest {

    @Test
    fun `wheel zoom uses ticks and keeps the pointer anchored`() {
        val view = ViewTransform(scale = 1.5f, rotation = 0.2f, tx = 30f, ty = -20f)
        val pivotX = 240f
        val pivotY = 180f
        val canvasPoint = view.invert(pivotX, pivotY)

        val zoomed = MouseNavigationPolicy.scroll(
            view = view,
            pivotX = pivotX,
            pivotY = pivotY,
            ticks = 2f,
            mode = MouseScrollMode.ZOOM,
        )

        assertClose(view.scale * 1.1.pow(2.0).toFloat(), zoomed.scale)
        assertAnchored(zoomed, canvasPoint, pivotX, pivotY)
    }

    @Test
    fun `ctrl wheel rotates five degrees per tick around the pointer`() {
        val view = ViewTransform(scale = 1.5f, rotation = 0.2f, tx = 30f, ty = -20f)
        val pivotX = 240f
        val pivotY = 180f
        val canvasPoint = view.invert(pivotX, pivotY)

        val rotated = MouseNavigationPolicy.scroll(
            view = view,
            pivotX = pivotX,
            pivotY = pivotY,
            ticks = 2f,
            mode = MouseScrollMode.ROTATE,
        )

        assertEquals(view.scale, rotated.scale)
        assertClose(view.rotation + (10.0 * PI / 180.0).toFloat(), rotated.rotation)
        assertAnchored(rotated, canvasPoint, pivotX, pivotY)
    }

    @Test
    fun `middle drag pans without changing scale or rotation`() {
        val view = ViewTransform(scale = 1.5f, rotation = 0.2f, tx = 30f, ty = -20f)

        val panned = MouseNavigationPolicy.middleDrag(view, deltaX = 17f, deltaY = -9f)

        assertEquals(view.scale, panned.scale)
        assertEquals(view.rotation, panned.rotation)
        assertClose(view.tx + 17f, panned.tx)
        assertClose(view.ty - 9f, panned.ty)
    }

    private fun assertAnchored(
        view: ViewTransform,
        canvasPoint: Pair<Float, Float>,
        expectedX: Float,
        expectedY: Float,
    ) {
        val actual = view.apply(canvasPoint.first, canvasPoint.second)
        assertClose(expectedX, actual.first)
        assertClose(expectedY, actual.second)
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(kotlin.math.abs(expected - actual) < EPSILON, "expected $expected, got $actual")
    }

    private companion object {
        const val EPSILON = 1e-3f
    }
}
