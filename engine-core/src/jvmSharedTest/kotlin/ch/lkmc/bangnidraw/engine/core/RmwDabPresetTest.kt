package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RmwDabPresetTest {

    @Test
    fun `smudge strength is emitted as pressure-shaped flow`() {
        val params = SmudgeParams(
            size = 40f,
            hardness = 0.6f,
            spacing = 0.16f,
            strength = 0.8f,
            pressureStrength = Curve(0.25f, 0.5f, 0.75f, 1f),
            stabilizer = 0.4f,
        )
        val preset = RmwDabPreset.smudge(params)

        assertEquals(params.size, preset.size)
        assertEquals(params.hardness, preset.hardness)
        assertEquals(params.spacing, preset.spacing)
        assertEquals(params.strength, preset.flow)
        assertEquals(params.pressureStrength, preset.pressureFlow)
        assertEquals(params.stabilizer, preset.stabilizer)
    }

    @Test
    fun `blur strength is emitted through a soft round mask`() {
        val params = BlurParams(size = 60f, strength = 0.5f, spacing = 0.3f)
        val preset = RmwDabPreset.blur(params)

        assertEquals(params.size, preset.size)
        assertEquals(params.strength, preset.flow)
        assertEquals(params.spacing, preset.spacing)
        assertEquals(0f, preset.hardness)
        assertEquals(Curve.One, preset.pressureSize)
        assertEquals(Curve.One, preset.pressureOpacity)
    }

    @Test
    fun `water pressure shapes a normalized flow`() {
        val params = WaterParams(
            size = 72f,
            hardness = 0.2f,
            spacing = 0.18f,
            waterLoad = 0.75f,
            pressureWater = Curve(0.2f, 0.4f, 0.8f, 1f),
            stabilizer = 0.25f,
        )

        val preset = RmwDabPreset.water(params)

        assertEquals(params.size, preset.size)
        assertEquals(params.hardness, preset.hardness)
        assertEquals(params.spacing, preset.spacing)
        assertEquals(1f, preset.flow)
        assertEquals(params.pressureWater, preset.pressureFlow)
        assertEquals(params.stabilizer, preset.stabilizer)
    }
}
