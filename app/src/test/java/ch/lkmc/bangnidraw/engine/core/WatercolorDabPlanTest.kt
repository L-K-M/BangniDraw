package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatercolorDabPlanTest {

    private val grid = TileGrid(512, 384)

    @Test
    fun `spread inflates the color footprint and one wet-cell halo`() {
        val plan = WatercolorDabPlan.forDab(
            grid = grid,
            x = 250f,
            y = 190f,
            radius = 10f,
            spread = 1f,
        )

        assertEquals(IntRect(234, 174, 266, 206), plan.output)
        assertEquals(IntRect(228, 168, 272, 212), plan.source)
        assertEquals(IntRect(58, 43, 67, 52), plan.wetOutput)
        assertEquals(IntRect(57, 42, 68, 53), plan.wetSource)
    }

    @Test
    fun `all footprints clip at the canvas edge`() {
        val plan = WatercolorDabPlan.forDab(
            grid = grid,
            x = 510f,
            y = 382f,
            radius = 8f,
            spread = 0.5f,
        )

        assertEquals(IntRect(499, 371, 512, 384), plan.output)
        assertEquals(IntRect(492, 364, 512, 384), plan.source)
        assertEquals(IntRect(124, 92, 128, 96), plan.wetOutput)
        assertEquals(IntRect(123, 91, 128, 96), plan.wetSource)
    }

    @Test
    fun `spread must be normalized`() {
        assertFailsWith<IllegalArgumentException> {
            WatercolorDabPlan.forDab(grid, 10f, 10f, 4f, spread = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            WatercolorDabPlan.forDab(grid, 10f, 10f, 4f, spread = 1.1f)
        }
    }

    @Test
    fun `scratch footprint stays within the GLES 3 minimum texture size`() {
        val plan = WatercolorDabPlan.forDab(
            grid = TileGrid(4096, 4096),
            x = 2048f,
            y = 2048f,
            radius = WatercolorDabPlan.MAX_DIAMETER_PX / 2f,
            spread = 1f,
        )

        assertEquals(true, plan.source.width <= WatercolorDabPlan.MIN_GL_TEXTURE_SIZE)
        assertEquals(true, plan.source.height <= WatercolorDabPlan.MIN_GL_TEXTURE_SIZE)
    }
    @Test
    fun `reusable bounds match the immutable plan`() {
        val bounds = WatercolorDabBounds(grid)

        assertTrue(bounds.set(250f, 190f, 10f, spread = 1f))
        assertEquals(234, bounds.outputLeft)
        assertEquals(174, bounds.outputTop)
        assertEquals(266, bounds.outputRight)
        assertEquals(206, bounds.outputBottom)
        assertEquals(228, bounds.sourceLeft)
        assertEquals(168, bounds.sourceTop)
        assertEquals(272, bounds.sourceRight)
        assertEquals(212, bounds.sourceBottom)
        assertEquals(58, bounds.wetOutputLeft)
        assertEquals(43, bounds.wetOutputTop)
        assertEquals(67, bounds.wetOutputRight)
        assertEquals(52, bounds.wetOutputBottom)
    }

}
