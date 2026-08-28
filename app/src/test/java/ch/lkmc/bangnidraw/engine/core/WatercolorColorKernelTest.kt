package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.StrokeMerge.Rgba
import ch.lkmc.bangnidraw.engine.core.WatercolorColorKernel.AlphaLock
import ch.lkmc.bangnidraw.engine.core.WatercolorColorKernel.DepositMode
import ch.lkmc.bangnidraw.engine.core.WatercolorColorKernel.Neighbors
import ch.lkmc.bangnidraw.engine.core.WatercolorColorKernel.Parameters
import ch.lkmc.bangnidraw.engine.core.WatercolorColorKernel.StraightRgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatercolorColorKernelTest {

    @Test
    fun `wet flow mixes the center with the four-neighbor average`() {
        val center = Rgba.straight(1f, 0f, 0f, 0.5f)
        val blue = Rgba.straight(0f, 0f, 1f, 1f)
        var receivedAmount = -1f
        val seam = StrokeMerge.ColorLerp { from, to, amount, out ->
            assertEquals(1f, from[0], EPSILON)
            assertEquals(1f, to[2], EPSILON)
            receivedAmount = amount
            for (component in 0..2) {
                out[component] = from[component] + (to[component] - from[component]) * amount
            }
        }

        val result = WatercolorColorKernel.evaluate(
            center = center,
            neighbors = Neighbors(
                north = blue,
                east = Rgba.TRANSPARENT,
                south = Rgba.TRANSPARENT,
                west = Rgba.TRANSPARENT,
            ),
            parameters = parameters(
                surfaceWater = 1f,
                spread = 1f,
                flowMask = 1f,
            ),
            lerp = seam,
        )

        assertEquals(WatercolorKernel.MAX_DIFFUSION, receivedAmount, EPSILON)
        assertRgba(Rgba(0.3344f, 0f, 0.1056f, 0.44f), result)
        assertTrue(result.isPremultiplied())
    }

    @Test
    fun `alpha lock preserves coverage through flow and deposit`() {
        val center = Rgba.straight(1f, 0f, 0f, 0.4f)
        val blue = Rgba.straight(0f, 0f, 1f, 0.8f)

        val result = WatercolorColorKernel.evaluate(
            center = center,
            neighbors = sameNeighbors(blue),
            parameters = parameters(
                surfaceWater = 1f,
                spread = 1f,
                flowMask = 1f,
                strength = 0.5f,
                color = StraightRgb(0f, 1f, 0f),
                depositMode = DepositMode.PIGMENT,
                alphaLock = AlphaLock.ENABLED,
            ),
        )

        assertRgba(Rgba(0.152f, 0.2f, 0.048f, 0.4f), result)
        assertTrue(result.isPremultiplied())

        val transparent = WatercolorColorKernel.evaluate(
            center = Rgba.TRANSPARENT,
            neighbors = sameNeighbors(blue),
            parameters = parameters(
                surfaceWater = 1f,
                spread = 1f,
                flowMask = 1f,
                strength = 1f,
                depositMode = DepositMode.PIGMENT,
                alphaLock = AlphaLock.ENABLED,
            ),
        )
        assertEquals(Rgba.TRANSPARENT, transparent)

        val tinyAlpha = SmudgeKernel.ALPHA_EPSILON / 2f
        val tiny = WatercolorColorKernel.evaluate(
            center = Rgba.straight(1f, 0f, 0f, tinyAlpha),
            neighbors = sameNeighbors(Rgba.TRANSPARENT),
            parameters = parameters(
                strength = 0.5f,
                depositMode = DepositMode.PIGMENT,
                alphaLock = AlphaLock.ENABLED,
            ),
        )
        assertRgba(Rgba(tinyAlpha * 0.5f, 0f, 0f, tinyAlpha), tiny)
    }

    @Test
    fun `clear water moves existing color without depositing brush pigment`() {
        val existing = Rgba.straight(1f, 0f, 0f, 0.5f)

        val result = WatercolorColorKernel.evaluate(
            center = existing,
            neighbors = sameNeighbors(existing),
            parameters = parameters(
                strength = 1f,
                color = StraightRgb(0f, 0f, 1f),
                depositMode = DepositMode.CLEAR_WATER,
            ),
        )

        assertRgba(existing, result)
    }

    @Test
    fun `paper relief and rim scale pigment deposition`() {
        val roughPaper = WatercolorColorKernel.evaluate(
            center = Rgba.TRANSPARENT,
            neighbors = sameNeighbors(Rgba.TRANSPARENT),
            parameters = parameters(
                paperRelief = 0f,
                granulation = 1f,
                strength = 0.2f,
                color = StraightRgb(1f, 0f, 0f),
                depositMode = DepositMode.PIGMENT,
            ),
        )
        val outerRim = WatercolorColorKernel.evaluate(
            center = Rgba.TRANSPARENT,
            neighbors = sameNeighbors(Rgba.TRANSPARENT),
            parameters = parameters(
                paperRelief = 1f,
                granulation = 1f,
                normalizedRadius = WatercolorKernel.RIM_OUTER_RADIUS,
                strength = 0.2f,
                edgeDarkening = 1f,
                color = StraightRgb(1f, 0f, 0f),
                depositMode = DepositMode.PIGMENT,
            ),
        )

        assertRgba(Rgba(0.11f, 0f, 0f, 0.11f), roughPaper)
        assertRgba(Rgba(0.3f, 0f, 0f, 0.3f), outerRim)
    }

    @Test
    fun `inputs must be normalized and premultiplied`() {
        assertFailsWith<IllegalArgumentException> {
            WatercolorColorKernel.evaluate(
                center = Rgba(0.8f, 0f, 0f, 0.2f),
                neighbors = sameNeighbors(Rgba.TRANSPARENT),
                parameters = parameters(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parameters(surfaceWater = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            parameters(normalizedRadius = -0.1f)
        }
        assertFailsWith<IllegalArgumentException> {
            StraightRgb(1.1f, 0f, 0f)
        }
    }

    private fun sameNeighbors(color: Rgba) = Neighbors(color, color, color, color)

    private fun parameters(
        surfaceWater: Float = 0f,
        absorbedSaturation: Float = 0f,
        spread: Float = 0f,
        flowMask: Float = 0f,
        paperRelief: Float = 1f,
        granulation: Float = 0f,
        dabMask: Float = 1f,
        normalizedRadius: Float = 0f,
        strength: Float = 0f,
        edgeDarkening: Float = 0f,
        dilution: Float = 0f,
        color: StraightRgb = StraightRgb(1f, 0f, 0f),
        depositMode: DepositMode = DepositMode.CLEAR_WATER,
        alphaLock: AlphaLock = AlphaLock.DISABLED,
    ) = Parameters(
        surfaceWater = surfaceWater,
        absorbedSaturation = absorbedSaturation,
        spread = spread,
        flowMask = flowMask,
        paperRelief = paperRelief,
        granulation = granulation,
        dabMask = dabMask,
        normalizedRadius = normalizedRadius,
        strength = strength,
        edgeDarkening = edgeDarkening,
        dilution = dilution,
        color = color,
        depositMode = depositMode,
        alphaLock = alphaLock,
    )

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
