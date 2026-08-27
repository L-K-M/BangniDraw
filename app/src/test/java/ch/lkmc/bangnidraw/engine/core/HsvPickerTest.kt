package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HsvPickerTest {

    @Test
    fun `ring changes hue without coupling saturation and value`() {
        val current = HsvColor(200f, 0.4f, 0.7f)

        assertEquals(HsvColor(0f, 0.4f, 0.7f), HsvPicker.select(100f, 50f, 100f, current))
        assertEquals(HsvColor(90f, 0.4f, 0.7f), HsvPicker.select(50f, 100f, 100f, current))
    }

    @Test
    fun `square maps independent saturation and value axes`() {
        val current = HsvColor(200f, 0.4f, 0.7f)

        assertEquals(HsvColor(200f, 0f, 1f), HsvPicker.select(25f, 25f, 100f, current))
        assertEquals(HsvColor(200f, 1f, 0f), HsvPicker.select(75f, 75f, 100f, current))
        assertEquals(HsvColor(200f, 0.5f, 0.5f), HsvPicker.select(50f, 50f, 100f, current))
    }

    @Test
    fun `greyscale commits keep the selected hue for saturation`() {
        val grey = HsvSelection.fromArgb(0xFF808080.toInt())
        val ringSelection = grey.commit(
            HsvPicker.select(50f, 100f, 100f, grey.hsv),
        )

        assertEquals(grey.argb, ringSelection.argb)

        val lighterSelection = ringSelection.commit(
            ringSelection.hsv.copy(v = 0.75f),
        )
        val parentEcho = lighterSelection.sync(lighterSelection.argb)
        val saturated = parentEcho.commit(
            parentEcho.hsv.copy(s = 1f),
        )

        assertEquals(90f, saturated.hsv.h)
        assertEquals(HsvColor(90f, 1f, 0.75f).toArgb(), saturated.argb)
    }

    @Test
    fun `external color resets the selection`() {
        val grey = HsvSelection.fromArgb(0xFF808080.toInt()).commit(
            HsvColor(90f, 0f, 0.5f),
        )
        val external = 0xFF0000FF.toInt()

        val synced = grey.sync(external)

        assertEquals(HsvColor.fromArgb(external), synced.hsv)
        assertEquals(external, synced.argb)
    }

    @Test
    fun `ARGB round trip stays exact for picker colors`() {
        repeat(CHANNEL_LEVELS) { channel ->
            val grey = Composite.argb(OPAQUE_ALPHA, channel, channel, channel)
            assertEquals(grey, HsvSelection.fromArgb(grey).argb)
        }

        for (red in 0 until CHANNEL_LEVELS step CHANNEL_SAMPLE_STEP) {
            for (green in 0 until CHANNEL_LEVELS step CHANNEL_SAMPLE_STEP) {
                for (blue in 0 until CHANNEL_LEVELS step CHANNEL_SAMPLE_STEP) {
                    val color = Composite.argb(OPAQUE_ALPHA, red, green, blue)
                    assertEquals(color, HsvSelection.fromArgb(color).argb)
                }
            }
        }
    }

    private companion object {
        const val CHANNEL_LEVELS = 256
        const val CHANNEL_SAMPLE_STEP = 17
        const val OPAQUE_ALPHA = 255
    }
}
