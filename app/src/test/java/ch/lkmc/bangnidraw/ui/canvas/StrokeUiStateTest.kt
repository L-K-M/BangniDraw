package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertEquals

class StrokeUiStateTest {

    @Test
    fun `brush color converts from opaque argb`() {
        val state = StrokeUiState()

        state.setColor(0xFF804020.toInt())

        assertEquals(0xFF804020.toInt(), state.colorArgb)
        assertEquals(128f / 255f, state.colorR)
        assertEquals(64f / 255f, state.colorG)
        assertEquals(32f / 255f, state.colorB)
    }

    @Test
    fun `a new pick generation invalidates queued samples`() {
        val state = StrokeUiState()
        val first = state.nextPickGeneration()

        val second = state.nextPickGeneration()

        assertEquals(1L, first)
        assertEquals(2L, second)
        assertEquals(second, state.pickGeneration)
    }
}
