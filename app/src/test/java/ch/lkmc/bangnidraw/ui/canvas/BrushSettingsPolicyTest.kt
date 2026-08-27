package ch.lkmc.bangnidraw.ui.canvas

import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrushSettingsPolicyTest {

    @Test
    fun `pigment controls require a paint brush and pigment mixer`() {
        val paint = BrushPresets.INK_PEN
        val eraser = paint.copy(eraseMode = true)

        assertTrue(BrushSettingsPolicy.showsPigmentControls(paint, MixerChoice.PIGMENT))
        assertFalse(BrushSettingsPolicy.showsPigmentControls(paint, MixerChoice.RGB))
        assertFalse(BrushSettingsPolicy.showsPigmentControls(eraser, MixerChoice.PIGMENT))
        assertFalse(BrushSettingsPolicy.showsPigmentControls(eraser, MixerChoice.RGB))
    }
}
