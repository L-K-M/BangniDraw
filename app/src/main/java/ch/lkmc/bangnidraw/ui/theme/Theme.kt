package ch.lkmc.bangnidraw.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import ch.lkmc.bangnidraw.engine.core.ThemeTone

// Follows the system: a painter working at night wants dark chrome, one
// under a window wants light. Neither scheme uses dynamic color — the
// accent is the brand's, and wallpaper-derived hues would tint the panels
// around the picture.
private val LightScheme = lightColorScheme(
    primary = SaffronDeep,
    onPrimary = PanelLight,
    primaryContainer = Saffron,
    onPrimaryContainer = OnAccent,
    secondary = Indigo,
    onSecondary = PanelLight,
    secondaryContainer = IndigoSoft,
    onSecondaryContainer = PanelLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PanelLight,
    onSurface = InkLight,
    surfaceVariant = PanelLightVariant,
    onSurfaceVariant = InkLightDim,
    outline = InkLightDim,
)

private val DarkScheme = darkColorScheme(
    primary = Saffron,
    onPrimary = OnAccent,
    primaryContainer = SaffronDeep,
    onPrimaryContainer = PanelLight,
    secondary = IndigoSoft,
    onSecondary = OnAccent,
    secondaryContainer = Indigo,
    onSecondaryContainer = PanelLight,
    background = PaperDark,
    onBackground = InkDark,
    surface = PanelDark,
    onSurface = InkDark,
    surfaceVariant = PanelDarkVariant,
    onSurfaceVariant = InkDarkDim,
    outline = InkDarkDim,
)

internal val LocalThemeTone = staticCompositionLocalOf { ThemeTone.LIGHT }

@Composable
fun BangniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tone = if (darkTheme) ThemeTone.DARK else ThemeTone.LIGHT
    CompositionLocalProvider(LocalThemeTone provides tone) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = BangniTypography,
            content = content,
        )
    }
}
