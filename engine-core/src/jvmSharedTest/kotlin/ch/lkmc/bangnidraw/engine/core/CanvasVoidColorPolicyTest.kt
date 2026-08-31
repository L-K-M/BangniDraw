package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasVoidColorPolicyTest {

    @Test
    fun `canvas void stays neutral and opaque within each tone`() {
        for (theme in AppTheme.entries) {
            val canvasVoid = CanvasVoidColorPolicy.argb(theme)
            val expected = when (theme.tone) {
                ThemeTone.LIGHT -> NEUTRAL_CANVAS_VOID_LIGHT
                ThemeTone.DARK -> NEUTRAL_CANVAS_VOID_DARK
            }

            assertEquals(ThemeColorPolicy.colors(theme).canvasVoidArgb, canvasVoid)
            assertEquals(expected, canvasVoid, "$theme has a tinted canvas void")
            assertEquals(OPAQUE_ALPHA, canvasVoid ushr ALPHA_SHIFT)
        }
    }

    private companion object {
        const val NEUTRAL_CANVAS_VOID_LIGHT = 0xFFB8B2AA.toInt()
        const val NEUTRAL_CANVAS_VOID_DARK = 0xFF171717.toInt()
        const val ALPHA_SHIFT = 24
        const val OPAQUE_ALPHA = 0xFF
    }
}
