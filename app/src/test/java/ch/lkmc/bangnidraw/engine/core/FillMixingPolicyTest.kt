package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FillMixingPolicyTest {

    @Test
    fun `fill mixes only when the active mixer is pigment`() {
        assertEquals(StrokeMode.MIX, FillMixingPolicy.mode(pigmentMixer))
        assertEquals(StrokeMode.PAINT, FillMixingPolicy.mode(RgbMixer))
    }

    private val pigmentMixer = object : ColorMixer {
        override val isPigment = true

        override fun mix(a: Int, b: Int, t: Float): Int = a
    }
}
