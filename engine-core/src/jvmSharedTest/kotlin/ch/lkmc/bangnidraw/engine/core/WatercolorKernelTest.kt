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
        val halfFullLoad = WatercolorKernel.FULL_LOAD_DRY_TIME_MILLIS / 2L

        assertEquals(1f, WatercolorKernel.retention(elapsedMillis = 0L))
        assertEquals(0.5f, WatercolorKernel.retention(elapsedMillis = halfFullLoad))
        assertEquals(
            0f,
            WatercolorKernel.retention(WatercolorKernel.FULL_LOAD_DRY_TIME_MILLIS),
        )
        assertEquals(0.3f, WatercolorKernel.dry(0.8f, halfFullLoad))
        assertEquals(
            0f,
            WatercolorKernel.dry(0.8f, WatercolorKernel.FULL_LOAD_DRY_TIME_MILLIS),
        )
    }

    @Test
    fun `water amount controls drying time`() {
        val halfLoadDryMillis = WatercolorKernel.FULL_LOAD_DRY_TIME_MILLIS / 2L

        assertEquals(0f, WatercolorKernel.dry(HALF_LOAD, halfLoadDryMillis))
        assertEquals(HALF_LOAD, WatercolorKernel.dry(FULL_LOAD, halfLoadDryMillis))
        assertEquals(
            0f,
            WatercolorKernel.dry(FULL_LOAD, WatercolorKernel.FULL_LOAD_DRY_TIME_MILLIS),
        )
    }

    @Test
    fun `split evaporation equals one lazy age`() {
        val elapsedTicks = WatercolorKernel.FULL_LOAD_DRY_TICKS / 2
        val firstElapsedTicks = elapsedTicks / 3
        val once = WatercolorKernel.evaporate(0.8f, 0.6f, elapsedTicks)
        val first = WatercolorKernel.evaporate(0.8f, 0.6f, firstElapsedTicks)
        val split = WatercolorKernel.evaporate(
            first.surfaceWater,
            first.saturation,
            elapsedTicks = elapsedTicks - firstElapsedTicks,
        )
        val empty = WatercolorKernel.evaporate(0f, 0f, elapsedTicks)

        assertEquals(once.surfaceWater, split.surfaceWater, EPSILON)
        assertEquals(once.saturation, split.saturation, EPSILON)
        assertEquals(WaterAmounts(0f, 0f), empty)
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
        val justWet = updated + WatercolorKernel.MAX_DRY_NANOS - 1L
        val dry = updated + WatercolorKernel.MAX_DRY_NANOS

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
    fun `page lifetime covers both water reservoirs`() {
        val updated = 0L
        val almostDry = WatercolorKernel.evaporate(
            FULL_LOAD,
            FULL_LOAD,
            WatercolorKernel.MAX_DRY_TICKS - 1,
        )
        val dry = WatercolorKernel.evaporate(
            FULL_LOAD,
            FULL_LOAD,
            WatercolorKernel.MAX_DRY_TICKS,
        )

        assertEquals(
            false,
            WatercolorKernel.isExpired(WatercolorKernel.MAX_DRY_NANOS - 1L, updated),
        )
        assertEquals(
            true,
            WatercolorKernel.isExpired(WatercolorKernel.MAX_DRY_NANOS, updated),
        )
        assertTrue(almostDry.surfaceWater > 0f || almostDry.saturation > 0f)
        assertEquals(WaterAmounts(0f, 0f), dry)
        assertTrue(WatercolorKernel.MAX_DRY_TICKS < WatercolorKernel.TICK_MODULUS)
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

    private companion object {
        const val EPSILON = 1e-6f
        const val HALF_LOAD = 0.5f
        const val FULL_LOAD = 1f
    }
}
