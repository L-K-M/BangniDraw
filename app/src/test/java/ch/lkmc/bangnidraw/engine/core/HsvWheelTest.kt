package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HsvWheelTest {

    @Test
    fun `wheel maps angle and radius to hue and saturation`() {
        assertEquals(HsvColor(0f, 1f, 0.7f), HsvWheel.select(100f, 50f, 100f, 100f, 0.7f))
        assertEquals(HsvColor(90f, 1f, 0.7f), HsvWheel.select(50f, 100f, 100f, 100f, 0.7f))
        assertEquals(HsvColor(0f, 0f, 0.7f), HsvWheel.select(50f, 50f, 100f, 100f, 0.7f))
    }
}
