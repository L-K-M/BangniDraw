package ch.lkmc.bangnidraw.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import ch.lkmc.bangnidraw.engine.core.AppTheme
import ch.lkmc.bangnidraw.engine.core.CanvasVoidColorPolicy
import ch.lkmc.bangnidraw.engine.core.ThemeColorPolicy

/**
 * The desktop product's palette. Every window this app opens — the document
 * window and each floating panel — wraps its content in the same
 * [MaterialTheme][androidx.compose.material3.MaterialTheme], so a panel torn
 * off into a window of its own still reads as part of the app rather than as
 * the host desktop's default Material.
 */
internal object DesktopTheme {

    /**
     * The Android app's default palette (SAFFRON, light — see
     * [ThemeColorPolicy]). Reading the ARGB tokens straight from engine-core
     * avoids hand-mirroring hex constants and keeps the desktop moving in
     * lockstep with the Android build's theme.
     */
    val colorScheme: ColorScheme = run {
        val colors = ThemeColorPolicy.colors(AppTheme.SAFFRON)
        val errors = ThemeColorPolicy.errorColors(AppTheme.SAFFRON.tone)
        // Mirrors app/src/main/java/ch/lkmc/bangnidraw/ui/theme/Color.kt's
        // bangniColorScheme — every Material3 role mapped from ThemeColorPolicy
        // with the same derivations (tertiary reuses secondary; surface-container
        // family derives from surfaceContainer/High/Variant). AlertDialog and
        // menus render on surfaceContainerHigh, so a partial mapping shows stock
        // cool greys against the warm SAFFRON palette. When Android's scheme
        // grows a role, mirror it here in the same commit.
        ColorScheme(
            primary = Color(colors.primaryArgb),
            onPrimary = Color(colors.onPrimaryArgb),
            primaryContainer = Color(colors.primaryContainerArgb),
            onPrimaryContainer = Color(colors.onPrimaryContainerArgb),
            secondary = Color(colors.secondaryArgb),
            onSecondary = Color(colors.onSecondaryArgb),
            secondaryContainer = Color(colors.secondaryContainerArgb),
            onSecondaryContainer = Color(colors.onSecondaryContainerArgb),
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

    /**
     * The neutral warm-grey around the paper — same [CanvasVoidColorPolicy]
     * source the Android app uses, so the desktop shell reads as part of the
     * same product family rather than a debug harness on the host OS's grey.
     */
    val viewportVoid = Color(CanvasVoidColorPolicy.argb(AppTheme.SAFFRON))
}
