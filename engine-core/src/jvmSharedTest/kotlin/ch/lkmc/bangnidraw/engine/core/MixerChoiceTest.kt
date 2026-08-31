package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MixerChoiceTest {

    @Test
    fun `pigment is the default when available`() {
        assertEquals(MixerChoice.PIGMENT, MixerChoice.fromStored(null, PigmentAvailability.AVAILABLE))
    }

    @Test
    fun `RGB is the default when pigment is absent`() {
        assertEquals(MixerChoice.RGB, MixerChoice.fromStored(null, PigmentAvailability.ABSENT))
    }

    @Test
    fun `missing pigment falls back to RGB`() {
        assertEquals(MixerChoice.RGB, MixerChoice.fromStored("PIGMENT", PigmentAvailability.ABSENT))
        assertEquals(MixerChoice.RGB, MixerChoice.fromStored("unknown", PigmentAvailability.AVAILABLE))
    }

    @Test
    fun `resolver applies RGB override`() {
        val pigment = object : ColorMixer {
            override val isPigment = true
            override fun mix(a: Int, b: Int, t: Float): Int = a
        }

        assertSame(pigment, ColorMixerResolver.resolve(MixerChoice.PIGMENT, pigment))
        assertSame(RgbMixer, ColorMixerResolver.resolve(MixerChoice.RGB, pigment))
        assertSame(RgbMixer, ColorMixerResolver.resolve(MixerChoice.PIGMENT, RgbMixer))
    }
}
