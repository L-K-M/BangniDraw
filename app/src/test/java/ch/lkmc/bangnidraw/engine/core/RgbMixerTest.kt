package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RgbMixerTest {

    @Test
    fun `blue plus yellow is gray`() {
        assertEquals(GRAY, RgbMixer.mix(BLUE, YELLOW, 0.5f))
        assertFalse(RgbMixer.isPigment)
    }

    @Test
    fun `endpoints are exact and alpha is ignored`() {
        assertEquals(OPAQUE_RED, RgbMixer.mix(TRANSPARENT_RED, BLUE, -1f))
        assertEquals(BLUE, RgbMixer.mix(TRANSPARENT_RED, BLUE, 2f))
    }

    @Test
    fun `rgb mixer is component linear in stored sRGB`() {
        assertEquals(0xFF7040A0.toInt(), RgbMixer.mix(0xFF204060.toInt(), 0xFFC040E0.toInt(), 0.5f))
    }

    @Test
    fun `latent conversion round trips`() {
        val latent = FloatArray(RgbMixer.latentSize)

        RgbMixer.toLatent(0x00123456, latent)

        assertEquals(0xFF123456.toInt(), RgbMixer.fromLatent(latent))
    }

    private companion object {
        const val BLUE = 0xFF0000FF.toInt()
        const val YELLOW = 0xFFFFFF00.toInt()
        const val GRAY = 0xFF808080.toInt()
        const val TRANSPARENT_RED = 0x00FF0000
        const val OPAQUE_RED = 0xFFFF0000.toInt()
    }
}
