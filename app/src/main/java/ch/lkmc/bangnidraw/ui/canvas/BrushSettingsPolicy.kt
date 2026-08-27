package ch.lkmc.bangnidraw.ui.canvas

import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.MixerChoice

/** Visibility rules for mixer-dependent brush controls. */
internal object BrushSettingsPolicy {

    fun showsPigmentControls(preset: BrushPreset, mixerChoice: MixerChoice): Boolean =
        !preset.eraseMode && mixerChoice == MixerChoice.PIGMENT
}
