package ch.lkmc.bangnidraw.engine.mixbox

import ch.lkmc.bangnidraw.engine.core.ColorMixer

/** Keeps Mixbox types outside the common source set. */
object MixboxBinding {
    fun create(): ColorMixer? = MixboxMixer()
}
