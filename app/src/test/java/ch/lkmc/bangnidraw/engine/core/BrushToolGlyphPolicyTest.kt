package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BrushToolGlyphPolicyTest {

    @Test
    fun `built-in brushes receive distinct glyphs`() {
        val cases = mapOf(
            "pencil" to BrushToolGlyph.PENCIL,
            "ink_pen" to BrushToolGlyph.INK_PEN,
            "paintbrush" to BrushToolGlyph.PAINTBRUSH,
            "watercolor" to BrushToolGlyph.WATERCOLOR,
            "airbrush" to BrushToolGlyph.AIRBRUSH,
            "marker" to BrushToolGlyph.MARKER,
        )

        for ((icon, expected) in cases) {
            val preset = BrushPresets.INK_PEN.copy(id = "user.$icon", icon = icon)

            assertEquals(expected, BrushToolGlyphPolicy.forPreset(preset), icon)
        }
    }

    @Test
    fun `eraser semantics override the stored brush id`() {
        val eraser = BrushPresets.INK_PEN.copy(
            id = "user.eraser",
            icon = "marker",
            eraseMode = true,
        )

        assertEquals(BrushToolGlyph.ERASER, BrushToolGlyphPolicy.forPreset(eraser))
    }

    @Test
    fun `unknown presets use the settings glyph`() {
        val custom = BrushPresets.INK_PEN.copy(id = "user.custom", icon = "custom")

        assertEquals(BrushToolGlyph.CUSTOM, BrushToolGlyphPolicy.forPreset(custom))
    }

    @Test
    fun `code fallback ink pen keeps its built-in glyph`() {
        assertEquals(
            BrushToolGlyph.INK_PEN,
            BrushToolGlyphPolicy.forPreset(BrushPresets.INK_PEN),
        )
    }
}
