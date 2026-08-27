package ch.lkmc.bangnidraw.engine.core

/** Resolves the merge path once at pen-down. */
object BrushMixingPolicy {
    fun mode(preset: BrushPreset, mixer: ColorMixer): StrokeMode {
        if (preset.eraseMode) return StrokeMode.ERASE
        if (preset.mixing && mixer.isPigment) return StrokeMode.MIX

        return StrokeMode.PAINT
    }
}
