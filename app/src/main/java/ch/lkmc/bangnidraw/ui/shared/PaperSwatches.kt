package ch.lkmc.bangnidraw.ui.shared

import androidx.compose.ui.graphics.Color

/**
 * The paper choices' own colours, and the two neutrals the canvas overlays
 * are keylined with.
 *
 * They live here rather than in `ui/theme` because both products draw with
 * them: this directory is compiled into `:desktop` as well
 * (`desktop/build.gradle.kts`'s `kotlin.srcDir`), which is what lets the
 * hover cursor and the composition guides be one implementation instead of
 * two that drift.
 *
 * Palette-independent on purpose. A guide line or a cursor ring has to stay
 * legible over whatever the user has painted, so it is dark-under-light
 * rather than a theme role that could land the same colour as the paint.
 */
val PaperSwatchWhite = Color(0xFFFFFFFF)
val PaperSwatchWarm = Color(0xFFF8F1E3)
val PaperSwatchGray = Color(0xFF9E9E9E)
val PaperSwatchBlack = Color(0xFF000000)
