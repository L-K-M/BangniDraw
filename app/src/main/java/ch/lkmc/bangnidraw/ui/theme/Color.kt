package ch.lkmc.bangnidraw.ui.theme

import androidx.compose.ui.graphics.Color
import ch.lkmc.bangnidraw.engine.core.CanvasVoidColorPolicy
import ch.lkmc.bangnidraw.engine.core.ThemeTone
import ch.lkmc.bangnidraw.engine.core.ToolButtonEmphasis
import ch.lkmc.bangnidraw.engine.core.ToolRailColorPolicy

// A quiet studio (docs/plan/08-ui-and-layout.md "Design language"). The
// chrome is neutral and low-saturation in both themes so nothing competes
// with the picture; the one accent is the saffron of the icon's brush
// stroke, the selection color its indigo ground. No ad-hoc Color(0x…) in
// screens — everything comes from here.

// Light: warm paper-white surfaces, near-black ink.
val PaperLight = Color(0xFFF6F3EC)
val PanelLight = Color(0xFFFFFFFF)
private val LightInactiveRail = ToolRailColorPolicy.colors(
    ThemeTone.LIGHT,
    ToolButtonEmphasis.INACTIVE,
)
private val DarkInactiveRail = ToolRailColorPolicy.colors(
    ThemeTone.DARK,
    ToolButtonEmphasis.INACTIVE,
)
private val LightActiveRail = ToolRailColorPolicy.colors(
    ThemeTone.LIGHT,
    ToolButtonEmphasis.ACTIVE,
)
private val DarkActiveRail = ToolRailColorPolicy.colors(
    ThemeTone.DARK,
    ToolButtonEmphasis.ACTIVE,
)

val PanelLightVariant = Color(LightInactiveRail.containerArgb)
val InkLight = Color(LightInactiveRail.iconArgb)
val InkLightDim = Color(0xFF6E6A60)

// Dark: slate, not black — a black UI makes every painting look darker
// than it will print.
val PaperDark = Color(0xFF15161A)
val PanelDark = Color(0xFF22242A)
val PanelDarkVariant = Color(DarkInactiveRail.containerArgb)
val InkDark = Color(DarkInactiveRail.iconArgb)
val InkDarkDim = Color(0xFF9A978E)

// Accents, from media-sources/icon.png.
val Saffron = Color(LightActiveRail.containerArgb)
val SaffronDeep = Color(DarkActiveRail.containerArgb)
val Indigo = Color(0xFF2B2ED6)
val IndigoSoft = Color(0xFF7D80FF)

val OnAccent = Color(LightActiveRail.iconArgb)

internal data class RailButtonColors(val container: Color, val icon: Color)

internal fun railButtonColors(tone: ThemeTone, emphasis: ToolButtonEmphasis): RailButtonColors {
    val colors = ToolRailColorPolicy.colors(tone, emphasis)
    return RailButtonColors(
        container = Color(colors.containerArgb),
        icon = Color(colors.iconArgb),
    )
}

/** Compose wrapper over the pure theme policy; no colour decision lives here. */
internal fun canvasVoidColor(tone: ThemeTone): Color = Color(CanvasVoidColorPolicy.argb(tone))

// The New Canvas dialog's paper swatches (docs/plan/08-ui-and-layout.md
// §2.1): white, warm white, mid-gray, black; transparent is
// Color.Transparent. The "+" custom-paper picker waits for the color
// panel (roadmap step 7). Paper is a document property, so these become
// ARGB ints at creation.
val PaperSwatchWhite = Color(0xFFFFFFFF)
val PaperSwatchWarm = Color(0xFFF8F1E3)
val PaperSwatchGray = Color(0xFF9E9E9E)
val PaperSwatchBlack = Color(0xFF000000)

/** The custom-paper swatch's opening tint: a cool paper blue no fixed swatch has. */
val PaperSwatchCustomDefault = Color(0xFFDCE6EE)

/** Compact painting palette; unlike chrome colors these become document pixels. */
val DrawingSwatches = listOf(
    Color(0xFF111111),
    Color(0xFFFFFFFF),
    Color(0xFFE53935),
    Color(0xFFFFC107),
    Color(0xFF43A047),
    Color(0xFF1E88E5),
    Color(0xFF5E35B1),
    Color(0xFF8D6E63),
)
