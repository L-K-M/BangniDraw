package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasVoidColorPolicyTest {

    @Test
    fun `canvas void stays neutral and opaque across themes`() {
        for (theme in AppTheme.entries) {
            val canvasVoid = CanvasVoidColorPolicy.argb(theme)

            assertEquals(ThemeColorPolicy.colors(theme).canvasVoidArgb, canvasVoid)
            assertEquals(NEUTRAL_CANVAS_VOID, canvasVoid)
            assertEquals(OPAQUE_ALPHA, canvasVoid ushr ALPHA_SHIFT)
        }
    }

    private companion object {
        const val NEUTRAL_CANVAS_VOID = 0xFFB8B2AA.toInt()
        const val ALPHA_SHIFT = 24
        const val OPAQUE_ALPHA = 0xFF
    }
}
