package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ColorSampleTest {

    @Test
    fun `transparent samples do not replace the brush color`() {
        assertEquals(null, ColorSample.opaqueArgb(0, 0, 0, 0))
    }

    @Test
    fun `premultiplied color is returned opaque`() {
        val sampled = ColorSample.opaqueArgb(
            redTotal = 64,
            greenTotal = 32,
            blueTotal = 0,
            alphaTotal = 128,
        )

        assertEquals(0xFF804000.toInt(), sampled)
    }

    @Test
    fun `transparent neighbors preserve the visible hue`() {
        val sampled = ColorSample.opaqueArgb(
            redTotal = 128,
            greenTotal = 64,
            blueTotal = 0,
            alphaTotal = 128,
        )

        assertEquals(0xFFFF8000.toInt(), sampled)
    }
}
