package ch.lkmc.bangnidraw.engine.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActualSizePolicyTest {

    private val fit = FitTransform(
        viewWidth = 1600f,
        viewHeight = 1000f,
        imageWidth = 2048f,
        imageHeight = 2048f,
    )

    @Test
    fun `actual size is one canvas px per view px`() {
        val result = ActualSizePolicy.transform(fit, ViewTransform())

        // screen = view ∘ fit, so effectiveScale == fit.scale × view.scale.
        assertEquals(1f, fit.scale * result.scale, 1e-6f)
    }

    @Test
    fun `the canvas point under the centre stays under the centre`() {
        val view = ViewTransform(scale = 3f, rotation = 0.6f, tx = -120f, ty = 77f)
        val centerX = fit.viewWidth / 2f
        val centerY = fit.viewHeight / 2f
        val canvasX = view.invertX(centerX, centerY)
        val canvasY = view.invertY(centerX, centerY)

        val result = ActualSizePolicy.transform(fit, view)

        // rotation is zero, so apply() is scale + translate only.
        assertEquals(centerX, result.scale * canvasX + result.tx, 1e-3f)
        assertEquals(centerY, result.scale * canvasY + result.ty, 1e-3f)
    }

    @Test
    fun `rotation zeroes`() {
        val result = ActualSizePolicy.transform(fit, ViewTransform(rotation = 1f))

        assertEquals(0f, result.rotation)
    }

    @Test
    fun `a canvas smaller than the viewport clamps at the scale floor`() {
        // fit.scale = 20 (a 200² canvas letterboxed up to a 4000² view), so
        // 1/fit is 0.05 — below MIN_SCALE, clamped up to the floor.
        val huge = FitTransform(
            viewWidth = 4000f,
            viewHeight = 4000f,
            imageWidth = 200f,
            imageHeight = 200f,
        )

        val result = ActualSizePolicy.transform(huge, ViewTransform())

        assertEquals(ViewTransform.MIN_SCALE, result.scale)
        assertTrue(abs(result.rotation) == 0f)
    }

    @Test
    fun `the clamp boundary itself is reachable`() {
        // fit.scale = 4, so 1/fit = 0.25 = MIN_SCALE exactly: the floor is
        // met, not clamped to.
        val edge = FitTransform(
            viewWidth = 4000f,
            viewHeight = 4000f,
            imageWidth = 1000f,
            imageHeight = 1000f,
        )

        val result = ActualSizePolicy.transform(edge, ViewTransform())

        assertEquals(ViewTransform.MIN_SCALE, result.scale)
    }
}
