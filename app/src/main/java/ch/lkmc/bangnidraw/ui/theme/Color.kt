package ch.lkmc.bangnidraw.ui.theme

import androidx.compose.ui.graphics.Color

// A quiet studio (docs/plan/08-ui-and-layout.md "Design language"). The
// chrome is neutral and low-saturation in both themes so nothing competes
// with the picture; the one accent is the saffron of the icon's brush
// stroke, the selection color its indigo ground. No ad-hoc Color(0x…) in
// screens — everything comes from here.

// Light: warm paper-white surfaces, near-black ink.
val PaperLight = Color(0xFFF6F3EC)
val PanelLight = Color(0xFFFFFFFF)
val PanelLightVariant = Color(0xFFECE8DF)
val InkLight = Color(0xFF1E1B16)
val InkLightDim = Color(0xFF6E6A60)

// Dark: slate, not black — a black UI makes every painting look darker
// than it will print.
val PaperDark = Color(0xFF15161A)
val PanelDark = Color(0xFF22242A)
val PanelDarkVariant = Color(0xFF2C2F36)
val InkDark = Color(0xFFECE8DF)
val InkDarkDim = Color(0xFF9A978E)

// Accents, from media-sources/icon.png.
val Saffron = Color(0xFFFFB300)
val SaffronDeep = Color(0xFFCC8A00)
val Indigo = Color(0xFF2B2ED6)
val IndigoSoft = Color(0xFF7D80FF)

val OnAccent = Color(0xFF1E1B16)

// The New Canvas dialog's paper swatches (docs/plan/08-ui-and-layout.md
// §2.1): white, warm white, mid-gray, black; transparent is
// Color.Transparent. The "+" custom-paper picker waits for the color
// panel (roadmap step 7). Paper is a document property, so these become
// ARGB ints at creation.
val PaperSwatchWhite = Color(0xFFFFFFFF)
val PaperSwatchWarm = Color(0xFFF8F1E3)
val PaperSwatchGray = Color(0xFF9E9E9E)
val PaperSwatchBlack = Color(0xFF000000)

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
