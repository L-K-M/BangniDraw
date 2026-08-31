package ch.lkmc.bangnidraw.engine.mixbox

import ch.lkmc.bangnidraw.engine.core.ColorMixer

/** Stripped builds expose no pigment mixer. */
object MixboxBinding {
    fun create(): ColorMixer? = null
}
