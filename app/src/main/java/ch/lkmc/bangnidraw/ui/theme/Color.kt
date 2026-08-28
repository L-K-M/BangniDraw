package ch.lkmc.bangnidraw.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import ch.lkmc.bangnidraw.engine.core.AppTheme
import ch.lkmc.bangnidraw.engine.core.CanvasVoidColorPolicy
import ch.lkmc.bangnidraw.engine.core.ThemeColorPolicy
import ch.lkmc.bangnidraw.engine.core.ToolButtonEmphasis
import ch.lkmc.bangnidraw.engine.core.ToolRailColorPolicy

/** Curated app palettes; painting colors remain independent document pixels. */
internal fun bangniColorScheme(theme: AppTheme): ColorScheme {
    val colors = ThemeColorPolicy.colors(theme)
    val errors = ThemeColorPolicy.errorColors(theme.tone)

    return ColorScheme(
        primary = Color(colors.primaryArgb),
        onPrimary = Color(colors.onPrimaryArgb),
        primaryContainer = Color(colors.primaryContainerArgb),
        onPrimaryContainer = Color(colors.onPrimaryContainerArgb),
        secondary = Color(colors.secondaryArgb),
        onSecondary = Color(colors.onSecondaryArgb),
        secondaryContainer = Color(colors.secondaryContainerArgb),
        onSecondaryContainer = Color(colors.onSecondaryContainerArgb),
        // The product has two accent families; tertiary intentionally reuses secondary.
        tertiary = Color(colors.secondaryArgb),
        onTertiary = Color(colors.onSecondaryArgb),
        tertiaryContainer = Color(colors.secondaryContainerArgb),
        onTertiaryContainer = Color(colors.onSecondaryContainerArgb),
        error = Color(errors.errorArgb),
        onError = Color(errors.onErrorArgb),
        errorContainer = Color(errors.errorContainerArgb),
        onErrorContainer = Color(errors.onErrorContainerArgb),
        background = Color(colors.backgroundArgb),
        onBackground = Color(colors.onBackgroundArgb),
        surface = Color(colors.surfaceArgb),
        onSurface = Color(colors.onSurfaceArgb),
        surfaceVariant = Color(colors.surfaceVariantArgb),
        onSurfaceVariant = Color(colors.onSurfaceVariantArgb),
        // Bright and lowest share the base surface; elevation begins at containerLow.
        surfaceDim = Color(colors.surfaceContainerHighArgb),
        surfaceBright = Color(colors.surfaceArgb),
        surfaceContainerLowest = Color(colors.surfaceArgb),
        surfaceContainerLow = Color(colors.backgroundArgb),
        surfaceContainer = Color(colors.surfaceContainerArgb),
        surfaceContainerHigh = Color(colors.surfaceContainerHighArgb),
        surfaceContainerHighest = Color(colors.surfaceVariantArgb),
        outline = Color(colors.outlineArgb),
        outlineVariant = Color(colors.outlineVariantArgb),
        inverseSurface = Color(colors.onSurfaceArgb),
        inverseOnSurface = Color(colors.surfaceArgb),
        inversePrimary = Color(colors.primaryContainerArgb),
        surfaceTint = Color(colors.primaryArgb),
        scrim = Color.Black,
        primaryFixed = Color(colors.primaryContainerArgb),
        primaryFixedDim = Color(colors.primaryContainerArgb),
        onPrimaryFixed = Color(colors.onPrimaryContainerArgb),
        onPrimaryFixedVariant = Color(colors.onPrimaryContainerArgb),
        secondaryFixed = Color(colors.secondaryContainerArgb),
        secondaryFixedDim = Color(colors.secondaryContainerArgb),
        onSecondaryFixed = Color(colors.onSecondaryContainerArgb),
        onSecondaryFixedVariant = Color(colors.onSecondaryContainerArgb),
        tertiaryFixed = Color(colors.secondaryContainerArgb),
        tertiaryFixedDim = Color(colors.secondaryContainerArgb),
        onTertiaryFixed = Color(colors.onSecondaryContainerArgb),
        onTertiaryFixedVariant = Color(colors.onSecondaryContainerArgb),
    )
}

internal data class RailButtonColors(val container: Color, val icon: Color)

internal fun railButtonColors(
    theme: AppTheme,
    emphasis: ToolButtonEmphasis,
): RailButtonColors {
    val colors = ToolRailColorPolicy.colors(theme, emphasis)

    return RailButtonColors(
        container = Color(colors.containerArgb),
        icon = Color(colors.iconArgb),
    )
}

internal fun canvasVoidColor(theme: AppTheme): Color =
    Color(CanvasVoidColorPolicy.argb(theme))

internal fun themePreviewColor(theme: AppTheme): Color =
    Color(ThemeColorPolicy.colors(theme).primaryContainerArgb)

// These swatches become document pixels and never follow the app theme.
val PaperSwatchWhite = Color(0xFFFFFFFF)
val PaperSwatchWarm = Color(0xFFF8F1E3)
val PaperSwatchGray = Color(0xFF9E9E9E)
val PaperSwatchBlack = Color(0xFF000000)

/** The custom-paper swatch's opening tint: a cool paper blue no fixed swatch has. */
val PaperSwatchCustomDefault = Color(0xFFDCE6EE)

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
