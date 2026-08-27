package ch.lkmc.bangnidraw.engine.core

internal enum class BrushToolGlyph(val presetKey: String?) {
    PENCIL("pencil"),
    INK_PEN("ink_pen"),
    PAINTBRUSH("paintbrush"),
    AIRBRUSH("airbrush"),
    SPRAY_CAN("spray_can"),
    MARKER("marker"),
    ERASER(null),
    CUSTOM(null),
}

/** Resolves stored preset metadata to one stable rail-glyph role. */
internal object BrushToolGlyphPolicy {

    fun forPreset(preset: BrushPreset): BrushToolGlyph {
        if (preset.eraseMode) return BrushToolGlyph.ERASER

        BrushToolGlyph.entries.firstOrNull { it.presetKey == preset.icon }?.let { return it }

        // The code fallback predates the asset glyph key but can briefly reach UI at startup.
        if (preset.id == BrushPresets.INK_PEN_ID) return BrushToolGlyph.INK_PEN

        return BrushToolGlyph.CUSTOM
    }
}
