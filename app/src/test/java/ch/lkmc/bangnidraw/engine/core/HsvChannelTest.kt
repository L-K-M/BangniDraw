package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HsvChannelTest {

    @Test
    fun `each channel reads and replaces only its component`() {
        val color = HsvColor(h = 120f, s = 0.4f, v = 0.7f)

        assertEquals(120f, HsvChannel.HUE.read(color))
        assertEquals(HsvColor(240f, 0.4f, 0.7f), HsvChannel.HUE.replace(color, 240f))
        assertEquals(40f, HsvChannel.SATURATION.read(color), FLOAT_TOLERANCE)
        assertEquals(HsvColor(120f, 0.8f, 0.7f), HsvChannel.SATURATION.replace(color, 80f))
        assertEquals(70f, HsvChannel.VALUE.read(color), FLOAT_TOLERANCE)
        assertEquals(HsvColor(120f, 0.4f, 0.2f), HsvChannel.VALUE.replace(color, 20f))
    }

    @Test
    fun `channel replacement clamps to the adjustable range`() {
        val color = HsvColor(h = 120f, s = 0.4f, v = 0.7f)

        assertEquals(HsvChannel.HUE.range.endInclusive, HsvChannel.HUE.replace(color, 999f).h)
        assertEquals(0f, HsvChannel.SATURATION.replace(color, -1f).s)
        assertEquals(1f, HsvChannel.VALUE.replace(color, 200f).v)
    }

    @Test
    fun `channels expose degree and percent increments`() {
        assertEquals(HUE_RANGE, HsvChannel.HUE.range)
        assertEquals(HUE_STEPS, HsvChannel.HUE.steps)
        assertEquals(PERCENT_RANGE, HsvChannel.SATURATION.range)
        assertEquals(PERCENT_STEPS, HsvChannel.SATURATION.steps)
        assertEquals(PERCENT_RANGE, HsvChannel.VALUE.range)
        assertEquals(PERCENT_STEPS, HsvChannel.VALUE.steps)
    }

    private companion object {
        val HUE_RANGE = 0f..360f
        const val HUE_STEPS = 359
        val PERCENT_RANGE = 0f..100f
        const val PERCENT_STEPS = 99
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
