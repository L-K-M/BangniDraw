package ch.lkmc.bangnidraw.engine.core

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatercolorKernelTest {

    @Test
    fun `wet grid is quarter resolution with format-minimum padding`() {
        assertEquals(TileGrid.MIN_EDGE, WatercolorKernel.wetPixels(256))
        assertEquals(270, WatercolorKernel.wetPixels(1080))
        assertEquals(1024, WatercolorKernel.wetPixels(4096))
        assertFailsWith<IllegalArgumentException> {
            WatercolorKernel.wetPixels(TileGrid.MIN_EDGE - 1)
        }
    }

    @Test
    fun `four-neighbor diffusion is a bounded stable blend`() {
        assertEquals(
            0.5f,
            WatercolorKernel.diffuse(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, spread = 1f),
        )

        val fromPeak = WatercolorKernel.diffuse(1f, 0f, 0f, 0f, 0f, spread = 1f)
        val intoValley = WatercolorKernel.diffuse(0f, 1f, 1f, 1f, 1f, spread = 1f)

        assertTrue(fromPeak in 0f..1f)
        assertTrue(intoValley in 0f..1f)
        assertTrue(fromPeak < 1f)
        assertTrue(intoValley > 0f)
    }

    @Test
    fun `zero spread leaves the center unchanged`() {
        assertEquals(
            0.37f,
            WatercolorKernel.diffuse(0.37f, 0f, 1f, 0.2f, 0.8f, spread = 0f),
        )
    }

    @Test
    fun `wet coverage reaches a rotated flat tips far cell corner`() {
        val expected = WatercolorKernel.CELL_SIZE * 0.5f * sqrt(2f) /
            TipShape.Flat.MIN_ASPECT

        assertEquals(
            expected,
            WatercolorKernel.wetCoverageInflation(TipShape.Flat.MIN_ASPECT),
            1e-6f,
        )
    }

    @Test
    fun `drying is linear and reaches zero`() {
        val halfLife = WatercolorKernel.DRY_TIME_MILLIS / 2L

        assertEquals(1f, WatercolorKernel.retention(elapsedMillis = 0L))
        assertEquals(0.5f, WatercolorKernel.retention(elapsedMillis = halfLife))
        assertEquals(0f, WatercolorKernel.retention(WatercolorKernel.DRY_TIME_MILLIS))
        assertEquals(0.4f, WatercolorKernel.dry(0.8f, halfLife))
        assertEquals(0f, WatercolorKernel.dry(0.8f, WatercolorKernel.DRY_TIME_MILLIS * 2L))
    }

    @Test
    fun `tick age survives byte wrap`() {
        assertEquals(0, WatercolorKernel.tickAt(0L))
        assertEquals(1, WatercolorKernel.tickAt(WatercolorKernel.TICK_NANOS))
        assertEquals(
            0,
            WatercolorKernel.tickAt(
                WatercolorKernel.TICK_NANOS * WatercolorKernel.TICK_MODULUS,
            ),
        )
        assertEquals(3, WatercolorKernel.ageTicks(nowTick = 1, updatedTick = 65_534))
    }

    @Test
    fun `opaque monotonic values map to ticks`() {
        assertEquals(250L, WatercolorKernel.ageMillis(1_250L, 1_000L))
        assertEquals(0L, WatercolorKernel.ageMillis(900L, 1_000L))
        assertEquals(0L, WatercolorKernel.ageMillis(nowMillis = -1L, updatedAtMillis = 0L))
        assertEquals(WatercolorKernel.TICK_MODULUS - 1, WatercolorKernel.tickAt(-1L))
        assertFailsWith<IllegalArgumentException> {
            WatercolorKernel.ageTicks(nowTick = WatercolorKernel.TICK_MODULUS, updatedTick = 0)
        }
    }

    @Test
    fun `absolute lifetime expires before encoded tick wraps`() {
        val updated = -WatercolorKernel.TICK_NANOS
        val justWet = updated + WatercolorKernel.DRY_NANOS - 1L
        val dry = updated + WatercolorKernel.DRY_NANOS

        assertEquals(false, WatercolorKernel.isExpired(justWet, updated))
        assertEquals(true, WatercolorKernel.isExpired(dry, updated))
        assertEquals(false, WatercolorKernel.isExpired(updated - 1L, updated))
        assertEquals(-1L, WatercolorKernel.tickEpoch(-1L))
        assertEquals(
            1L,
            WatercolorKernel.tickEpoch(
                WatercolorKernel.TICK_NANOS * WatercolorKernel.TICK_MODULUS,
            ),
        )
    }

    @Test
    fun `kernel refuses values outside the normalized channel`() {
        assertFailsWith<IllegalArgumentException> {
            WatercolorKernel.diffuse(2f, 0f, 0f, 0f, 0f, spread = 1f)
        }
        assertFailsWith<IllegalArgumentException> {
            WatercolorKernel.dry(Float.NaN, elapsedMillis = 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            WatercolorKernel.retention(elapsedMillis = 1L, dryTimeMillis = 0L)
        }
    }
}
