package ch.lkmc.bangnidraw.engine.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorsTest {

    @Test
    fun `HSV fixtures convert to ARGB`() {
        assertEquals(RED, HsvColor(0f, 1f, 1f).toArgb())
        assertEquals(GREEN, HsvColor(120f, 1f, 1f).toArgb())
        assertEquals(BLUE, HsvColor(240f, 1f, 1f).toArgb())
        assertEquals(GRAY, HsvColor(37f, 0f, 0.5f).toArgb())
    }

    @Test
    fun `ARGB fixtures convert to HSV`() {
        val green = HsvColor.fromArgb(GREEN)

        assertTrue(abs(green.h - 120f) < EPSILON)
        assertEquals(1f, green.s)
        assertEquals(1f, green.v)
    }

    @Test
    fun `hue wraps and channels clamp`() {
        assertEquals(RED, HsvColor(360f, 1f, 1f).toArgb())
        assertEquals(RED, HsvColor(-360f, 2f, 2f).toArgb())
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val GRAY = 0xFF808080.toInt()
        const val EPSILON = 0.001f
    }
}
