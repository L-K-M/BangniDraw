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
}
