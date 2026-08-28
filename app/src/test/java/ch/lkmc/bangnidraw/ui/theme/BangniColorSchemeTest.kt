package ch.lkmc.bangnidraw.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ch.lkmc.bangnidraw.engine.core.AppTheme
import ch.lkmc.bangnidraw.engine.core.ThemeColorPolicy
import ch.lkmc.bangnidraw.engine.core.contrastRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BangniColorSchemeTest {

    @Test
    fun `scheme derives every role from the selected palette`() {
        for (theme in AppTheme.entries) {
            val colors = ThemeColorPolicy.colors(theme)
            val scheme = bangniColorScheme(theme)
            val expectedRoles = listOf(
                "primary" to (Color(colors.primaryArgb) to scheme.primary),
                "onPrimary" to (Color(colors.onPrimaryArgb) to scheme.onPrimary),
                "primaryContainer" to
                    (Color(colors.primaryContainerArgb) to scheme.primaryContainer),
                "onPrimaryContainer" to
                    (Color(colors.onPrimaryContainerArgb) to scheme.onPrimaryContainer),
                "secondary" to (Color(colors.secondaryArgb) to scheme.secondary),
                "onSecondary" to (Color(colors.onSecondaryArgb) to scheme.onSecondary),
                "secondaryContainer" to
                    (Color(colors.secondaryContainerArgb) to scheme.secondaryContainer),
                "onSecondaryContainer" to
                    (Color(colors.onSecondaryContainerArgb) to scheme.onSecondaryContainer),
                "tertiary" to (Color(colors.secondaryArgb) to scheme.tertiary),
                "onTertiary" to (Color(colors.onSecondaryArgb) to scheme.onTertiary),
                "tertiaryContainer" to
                    (Color(colors.secondaryContainerArgb) to scheme.tertiaryContainer),
                "onTertiaryContainer" to
                    (Color(colors.onSecondaryContainerArgb) to scheme.onTertiaryContainer),
                "error" to (expectedError to scheme.error),
                "onError" to (expectedOnError to scheme.onError),
                "errorContainer" to (expectedErrorContainer to scheme.errorContainer),
                "onErrorContainer" to
                    (expectedOnErrorContainer to scheme.onErrorContainer),
                "background" to (Color(colors.backgroundArgb) to scheme.background),
                "onBackground" to (Color(colors.onBackgroundArgb) to scheme.onBackground),
                "surface" to (Color(colors.surfaceArgb) to scheme.surface),
                "onSurface" to (Color(colors.onSurfaceArgb) to scheme.onSurface),
                "surfaceVariant" to
                    (Color(colors.surfaceVariantArgb) to scheme.surfaceVariant),
                "onSurfaceVariant" to
                    (Color(colors.onSurfaceVariantArgb) to scheme.onSurfaceVariant),
                "surfaceDim" to
                    (Color(colors.surfaceContainerHighArgb) to scheme.surfaceDim),
                "surfaceBright" to (Color(colors.surfaceArgb) to scheme.surfaceBright),
                "surfaceContainerLowest" to
                    (Color(colors.surfaceArgb) to scheme.surfaceContainerLowest),
                "surfaceContainerLow" to
                    (Color(colors.backgroundArgb) to scheme.surfaceContainerLow),
                "surfaceContainer" to
                    (Color(colors.surfaceContainerArgb) to scheme.surfaceContainer),
                "surfaceContainerHigh" to
                    (Color(colors.surfaceContainerHighArgb) to scheme.surfaceContainerHigh),
                "surfaceContainerHighest" to
                    (Color(colors.surfaceVariantArgb) to scheme.surfaceContainerHighest),
                "outline" to (Color(colors.outlineArgb) to scheme.outline),
                "outlineVariant" to
                    (Color(colors.outlineVariantArgb) to scheme.outlineVariant),
                "inverseSurface" to (Color(colors.onSurfaceArgb) to scheme.inverseSurface),
                "inverseOnSurface" to
                    (Color(colors.surfaceArgb) to scheme.inverseOnSurface),
                "inversePrimary" to
                    (Color(colors.primaryContainerArgb) to scheme.inversePrimary),
                "surfaceTint" to (Color(colors.primaryArgb) to scheme.surfaceTint),
                "scrim" to (Color.Black to scheme.scrim),
                "primaryFixed" to
                    (Color(colors.primaryContainerArgb) to scheme.primaryFixed),
                "primaryFixedDim" to
                    (Color(colors.primaryContainerArgb) to scheme.primaryFixedDim),
                "onPrimaryFixed" to
                    (Color(colors.onPrimaryContainerArgb) to scheme.onPrimaryFixed),
                "onPrimaryFixedVariant" to
                    (Color(colors.onPrimaryContainerArgb) to scheme.onPrimaryFixedVariant),
                "secondaryFixed" to
                    (Color(colors.secondaryContainerArgb) to scheme.secondaryFixed),
                "secondaryFixedDim" to
                    (Color(colors.secondaryContainerArgb) to scheme.secondaryFixedDim),
                "onSecondaryFixed" to
                    (Color(colors.onSecondaryContainerArgb) to scheme.onSecondaryFixed),
                "onSecondaryFixedVariant" to
                    (Color(colors.onSecondaryContainerArgb) to scheme.onSecondaryFixedVariant),
                "tertiaryFixed" to
                    (Color(colors.secondaryContainerArgb) to scheme.tertiaryFixed),
                "tertiaryFixedDim" to
                    (Color(colors.secondaryContainerArgb) to scheme.tertiaryFixedDim),
                "onTertiaryFixed" to
                    (Color(colors.onSecondaryContainerArgb) to scheme.onTertiaryFixed),
                "onTertiaryFixedVariant" to
                    (Color(colors.onSecondaryContainerArgb) to scheme.onTertiaryFixedVariant),
            )

            for ((role, pair) in expectedRoles) {
                assertEquals(pair.first, pair.second, "$theme $role")
            }
        }
    }

    @Test
    fun `fixed role content meets text contrast`() {
        for (theme in AppTheme.entries) {
            val scheme = bangniColorScheme(theme)
            val families = listOf(
                "primary" to listOf(
                    scheme.primaryFixed,
                    scheme.primaryFixedDim,
                    scheme.onPrimaryFixed,
                    scheme.onPrimaryFixedVariant,
                ),
                "secondary" to listOf(
                    scheme.secondaryFixed,
                    scheme.secondaryFixedDim,
                    scheme.onSecondaryFixed,
                    scheme.onSecondaryFixedVariant,
                ),
                "tertiary" to listOf(
                    scheme.tertiaryFixed,
                    scheme.tertiaryFixedDim,
                    scheme.onTertiaryFixed,
                    scheme.onTertiaryFixedVariant,
                ),
            )

            for ((name, roles) in families) {
                val fills = roles.take(FIXED_FILL_COUNT)
                val content = roles.drop(FIXED_FILL_COUNT)

                for (fill in fills) {
                    for (onFill in content) {
                        val contrast = contrastRatio(fill.toArgb(), onFill.toArgb())
                        assertTrue(
                            contrast >= MIN_TEXT_CONTRAST,
                            "$theme $name fixed-role contrast is $contrast:1",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `shared error roles meet text contrast`() {
        for (theme in AppTheme.entries) {
            val scheme = bangniColorScheme(theme)
            val pairs = listOf(
                "error" to (scheme.error to scheme.onError),
                "errorContainer" to (scheme.errorContainer to scheme.onErrorContainer),
                "error on background" to (scheme.error to scheme.background),
                "error on surface" to (scheme.error to scheme.surface),
                "error on surfaceContainer" to
                    (scheme.error to scheme.surfaceContainer),
                "error on surfaceContainerHigh" to
                    (scheme.error to scheme.surfaceContainerHigh),
            )

            for ((role, pair) in pairs) {
                val contrast = contrastRatio(pair.first.toArgb(), pair.second.toArgb())

                assertTrue(
                    contrast >= MIN_TEXT_CONTRAST,
                    "$theme $role contrast is $contrast:1",
                )
            }
        }
    }

    private companion object {
        val expectedError = Color(0xFFB3261E)
        val expectedOnError = Color(0xFFFFFFFF)
        val expectedErrorContainer = Color(0xFFF9DEDC)
        val expectedOnErrorContainer = Color(0xFF410E0B)
        const val FIXED_FILL_COUNT = 2
        const val MIN_TEXT_CONTRAST = 4.5
    }
}
