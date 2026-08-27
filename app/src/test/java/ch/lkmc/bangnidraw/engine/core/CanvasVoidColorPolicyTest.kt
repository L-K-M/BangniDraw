package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasVoidColorPolicyTest {

    @Test
    fun `quiet studio canvas void is opaque and theme specific`() {
        val light = CanvasVoidColorPolicy.argb(ThemeTone.LIGHT)
        val dark = CanvasVoidColorPolicy.argb(ThemeTone.DARK)

        assertEquals(LIGHT_VOID, light)
        assertEquals(DARK_VOID, dark)
        assertEquals(OPAQUE_ALPHA, light ushr ALPHA_SHIFT)
        assertEquals(OPAQUE_ALPHA, dark ushr ALPHA_SHIFT)
    }

    private companion object {
        const val LIGHT_VOID = 0xFFB8B2AA.toInt()
        const val DARK_VOID = 0xFF0F0E0D.toInt()
        const val ALPHA_SHIFT = 24
        const val OPAQUE_ALPHA = 0xFF
    }
}
