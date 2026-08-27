package ch.lkmc.bangnidraw.engine.core

/** Selects fill's merge arithmetic from the active color mixer. */
internal object FillMixingPolicy {
    fun mode(mixer: ColorMixer): StrokeMode {
        if (mixer.isPigment) return StrokeMode.MIX

        return StrokeMode.PAINT
    }
}
