package ch.lkmc.bangnidraw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import ch.lkmc.bangnidraw.engine.core.AppTheme

internal val LocalAppTheme = staticCompositionLocalOf { AppTheme.DEFAULT }

@Composable
internal fun BangniTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(appTheme) { bangniColorScheme(appTheme) }

    CompositionLocalProvider(LocalAppTheme provides appTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BangniTypography,
            content = content,
        )
    }
}
