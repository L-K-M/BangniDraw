package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.StrokeMerge.Rgba
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmudgeKernelTest {

    @Test
    fun `deposit linearly carries premultiplied alpha`() {
        val layer = Rgba.straight(1f, 0f, 0f, 0.5f)
        val pickup = Rgba.straight(0f, 0f, 1f, 0.25f)

        val result = SmudgeKernel.deposit(layer, pickup, 0.4f)

        assertRgba(Rgba(0.3f, 0f, 0.1f, 0.4f), result)
        assertTrue(result.isPremultiplied())
    }

    @Test
    fun `pickup reads the layer before deposit`() {
        val pickup = Rgba.straight(1f, 0f, 0f, 1f)
        val layerBefore = Rgba.straight(0f, 0f, 1f, 1f)
        val deposited = SmudgeKernel.deposit(layerBefore, pickup, 0.75f)

        val absorbed = SmudgeKernel.absorb(pickup, layerBefore, 0.5f)

        assertRgba(Rgba.straight(0.5f, 0f, 0.5f, 1f), absorbed)
        assertTrue(absorbed != SmudgeKernel.absorb(pickup, deposited, 0.5f))
    }

    @Test
    fun `pigment seam receives straight colors and pigment share`() {
        val layer = Rgba.straight(0.8f, 0.2f, 0.1f, 0.5f)
        val pickup = Rgba.straight(0.1f, 0.3f, 0.9f, 0.25f)
        var receivedT = -1f
        val seam = StrokeMerge.ColorLerp { from, to, t, out ->
            assertEquals(0.8f, from[0], EPSILON)
            assertEquals(0.9f, to[2], EPSILON)
            receivedT = t
            out[0] = 0.25f
            out[1] = 0.5f
            out[2] = 0.75f
        }

        val result = SmudgeKernel.deposit(layer, pickup, 0.4f, seam)

        assertEquals(0.25f, receivedT, EPSILON)
        assertRgba(Rgba(0.1f, 0.2f, 0.3f, 0.4f), result)
    }

    @Test
    fun `transparent mixtures stay finite`() {
        val result = SmudgeKernel.deposit(Rgba.TRANSPARENT, Rgba.TRANSPARENT, 0.7f)

        assertEquals(Rgba.TRANSPARENT, result)
        assertTrue(listOf(result.r, result.g, result.b, result.a).all(Float::isFinite))
    }

    @Test
    fun `separable blur crosses both axes without darkening`() {
        val transparent = Rgba.TRANSPARENT
        val white = Rgba.straight(1f, 1f, 1f, 1f)
        val source = Array(9) { transparent }
        source[4] = white
        val out = Array(9) { transparent }
        val scratch = Array(9) { transparent }

        BlurKernel.separable(
            source,
            width = 3,
            height = 3,
            radius = 1,
            scratch = scratch,
            out = out,
        )

        for (pixel in out) {
            assertRgba(Rgba(1f / 9f, 1f / 9f, 1f / 9f, 1f / 9f), pixel)
        }
    }

    @Test
    fun `blur radius follows size and stays bounded`() {
        assertEquals(1, BlurKernel.radius(size = 1f, fraction = 0.01f))
        assertEquals(9, BlurKernel.radius(size = 60f, fraction = 0.15f))
        assertEquals(24, BlurKernel.radius(size = 400f, fraction = 1f))
    }

    private fun assertRgba(expected: Rgba, actual: Rgba) {
        assertEquals(expected.r, actual.r, EPSILON, "red")
        assertEquals(expected.g, actual.g, EPSILON, "green")
        assertEquals(expected.b, actual.b, EPSILON, "blue")
        assertEquals(expected.a, actual.a, EPSILON, "alpha")
    }

    private companion object {
        const val EPSILON = 1e-6f
    }
}
