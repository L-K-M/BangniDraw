package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.WatercolorOverlayKernel.Refresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatercolorOverlayKernelTest {

    @Test
    fun `wet cue fades on the wet clock and stays premultiplied`() {
        val stamp = WatercolorWetKernel.encodeTick(FRESH_TICK)
        val cell = WatercolorWetKernel.StoredCell(
            surfaceWater = 0.8f,
            tickHigh = stamp.high,
            tickLow = stamp.low,
            saturation = 0.2f,
        )

        val fresh = WatercolorOverlayKernel.cue(cell, FRESH_TICK)
        val halfway = WatercolorOverlayKernel.cue(
            cell,
            FRESH_TICK + WatercolorKernel.DRY_TICKS / 2,
        )
        val dry = WatercolorOverlayKernel.cue(
            cell,
            FRESH_TICK + WatercolorKernel.DRY_TICKS,
        )

        assertEquals(EXPECTED_FRESH_ALPHA, fresh.a, EPSILON)
        assertEquals(fresh.a / 2f, halfway.a, EPSILON)
        assertEquals(0f, dry.a, EPSILON)
        assertTrue(fresh.r in 0f..fresh.a)
        assertTrue(fresh.g in 0f..fresh.a)
        assertTrue(fresh.b in 0f..fresh.a)
    }

    @Test
    fun `absorbed water remains visible`() {
        val stamp = WatercolorWetKernel.encodeTick(FRESH_TICK)
        val cell = WatercolorWetKernel.StoredCell(
            surfaceWater = 0f,
            tickHigh = stamp.high,
            tickLow = stamp.low,
            saturation = 1f,
        )

        val cue = WatercolorOverlayKernel.cue(cell, FRESH_TICK)

        assertEquals(WatercolorOverlayKernel.MAX_ALPHA, cue.a, EPSILON)
    }

    @Test
    fun `wet cue ages across the tick wrap`() {
        val updatedTick = WatercolorKernel.TICK_MODULUS - WRAP_AGE_TICKS / 2
        val nowTick = WRAP_AGE_TICKS / 2
        val stamp = WatercolorWetKernel.encodeTick(updatedTick)
        val cell = WatercolorWetKernel.StoredCell(
            surfaceWater = 1f,
            tickHigh = stamp.high,
            tickLow = stamp.low,
            saturation = 0f,
        )

        val cue = WatercolorOverlayKernel.cue(cell, nowTick)
        val expectedRetention =
            1f - WRAP_AGE_TICKS.toFloat() / WatercolorKernel.DRY_TICKS

        assertEquals(
            WatercolorOverlayKernel.MAX_ALPHA * expectedRetention,
            cue.a,
            EPSILON,
        )
    }

    @Test
    fun `overlay refresh redraws once when the final wet tile expires`() {
        assertEquals(Refresh.IDLE, WatercolorOverlayKernel.refresh(0, 0))
        assertEquals(Refresh.REDRAW, WatercolorOverlayKernel.refresh(1, 0))
        assertEquals(Refresh.REDRAW_AND_CONTINUE, WatercolorOverlayKernel.refresh(1, 1))
    }

    private companion object {
        const val FRESH_TICK = 10
        const val EXPECTED_FRESH_ALPHA = 0.1512f
        const val EPSILON = 1e-6f
        const val WRAP_AGE_TICKS = 10
    }
}
