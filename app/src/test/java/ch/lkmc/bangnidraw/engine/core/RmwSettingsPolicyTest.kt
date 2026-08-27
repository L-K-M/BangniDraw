package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RmwSettingsPolicyTest {

    @Test
    fun `smudge pigment control follows the active mixer`() {
        assertTrue(RmwSettingsPolicy.showsPigmentControl(MixerChoice.PIGMENT))
        assertFalse(RmwSettingsPolicy.showsPigmentControl(MixerChoice.RGB))
    }
}
