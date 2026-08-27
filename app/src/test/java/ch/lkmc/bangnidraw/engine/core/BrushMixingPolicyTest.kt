package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BrushMixingPolicyTest {

    @Test
    fun `pigment preset selects pigment merge`() {
        assertEquals(
            StrokeMode.MIX,
            BrushMixingPolicy.mode(preset.copy(mixing = true), pigmentMixer),
        )
    }

    @Test
    fun `RGB selection keeps pigment preset on paint merge`() {
        assertEquals(
            StrokeMode.PAINT,
            BrushMixingPolicy.mode(preset.copy(mixing = true), RgbMixer),
        )
    }

    @Test
    fun `eraser never mixes`() {
        assertEquals(
            StrokeMode.ERASE,
            BrushMixingPolicy.mode(preset.copy(eraseMode = true), pigmentMixer),
        )
    }

    private val preset = BrushPreset(id = "test", name = "Test")

    private val pigmentMixer = object : ColorMixer {
        override val isPigment = true
        override fun mix(a: Int, b: Int, t: Float): Int = a
    }
}
