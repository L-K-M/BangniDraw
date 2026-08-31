package ch.lkmc.bangnidraw.engine.core

enum class BrushToolGlyph(val presetKey: String?) {
    PENCIL("pencil"),
    INK_PEN("ink_pen"),
    PAINTBRUSH("paintbrush"),
    WATERCOLOR("watercolor"),
    AIRBRUSH("airbrush"),
    SPRAY_CAN("spray_can"),
    MARKER("marker"),
    CHARCOAL("charcoal"),
    SOFT_PASTEL("soft_pastel"),
    TECHNICAL_PEN("technical_pen"),
    CALLIGRAPHY("calligraphy"),
    DRY_BRUSH("dry_brush"),
    OIL_PAINT("oil_paint"),
    PIGMENT_WASH("pigment_wash"),
    ERASER(null),
    CUSTOM(null),
}

/** Resolves stored preset metadata to one stable rail-glyph role. */
object BrushToolGlyphPolicy {

    fun forPreset(preset: BrushPreset): BrushToolGlyph {
        if (preset.eraseMode) return BrushToolGlyph.ERASER

        BrushToolGlyph.entries.firstOrNull { it.presetKey == preset.icon }?.let { return it }

        // The code fallback predates the asset glyph key but can briefly reach UI at startup.
        if (preset.id == BrushPresets.INK_PEN_ID) return BrushToolGlyph.INK_PEN

        return BrushToolGlyph.CUSTOM
    }
}
