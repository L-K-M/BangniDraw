package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ColorMixerTest {

    @Test
    fun `weighted mix normalizes weights`() {
        val result = RgbMixer.mixWeighted(
            colors = intArrayOf(BLACK, WHITE, RED),
            weights = floatArrayOf(2f, 1f, 1f),
        )

        assertEquals(0xFF804040.toInt(), result)
    }

    @Test
    fun `weighted mix rejects invalid inputs`() {
        assertFailsWith<IllegalArgumentException> {
            RgbMixer.mixWeighted(intArrayOf(BLACK), floatArrayOf())
        }
        assertFailsWith<IllegalArgumentException> {
            RgbMixer.mixWeighted(intArrayOf(BLACK), floatArrayOf(0f))
        }
        assertFailsWith<IllegalArgumentException> {
            RgbMixer.mixWeighted(intArrayOf(BLACK), floatArrayOf(-1f))
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
