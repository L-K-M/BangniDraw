package ch.lkmc.bangnidraw.engine.core

/** Visibility rules for read-modify-write tool settings. */
internal object RmwSettingsPolicy {
    fun showsPigmentControl(mixerChoice: MixerChoice): Boolean =
        mixerChoice == MixerChoice.PIGMENT
}
