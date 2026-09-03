package ch.lkmc.bangnidraw.ui.glyphs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.OilBarrel
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import ch.lkmc.bangnidraw.engine.core.BrushToolGlyph

/**
 * The one glyph per brush role, shared by the Android rail and the desktop
 * one — this directory is the single copy both modules compile (see the
 * `kotlin.srcDir` in `desktop/build.gradle.kts`).
 *
 * One distinct glyph per tool: the pencil must not share `Gesture` with the
 * smudge tool, nor the airbrush `BlurOn` with blur — identical glyphs in one
 * rail defeat the glance-recognition the rail exists for. Sharing the
 * mapping is what keeps that true on both platforms at once.
 */
internal fun brushGlyphIcon(glyph: BrushToolGlyph): ImageVector = when (glyph) {
    BrushToolGlyph.PENCIL -> Icons.Filled.Draw
    BrushToolGlyph.INK_PEN -> Icons.Filled.Create
    BrushToolGlyph.PAINTBRUSH -> Icons.Filled.Brush
    BrushToolGlyph.WATERCOLOR -> WaterToolGlyphs.Watercolor
    BrushToolGlyph.AIRBRUSH -> Icons.Filled.Air
    BrushToolGlyph.SPRAY_CAN -> ToolGlyphs.SprayCan
    BrushToolGlyph.MARKER -> ToolGlyphs.Marker
    BrushToolGlyph.CHARCOAL -> Icons.Filled.Texture
    BrushToolGlyph.SOFT_PASTEL -> Icons.Filled.Gradient
    BrushToolGlyph.TECHNICAL_PEN -> Icons.Filled.Architecture
    BrushToolGlyph.CALLIGRAPHY -> Icons.Filled.HistoryEdu
    BrushToolGlyph.DRY_BRUSH -> Icons.Filled.FormatPaint
    BrushToolGlyph.OIL_PAINT -> Icons.Filled.OilBarrel
    BrushToolGlyph.PIGMENT_WASH -> ToolGlyphs.PigmentWash
    BrushToolGlyph.ERASER -> ToolGlyphs.Eraser
    BrushToolGlyph.CUSTOM -> Icons.Filled.Tune
}
