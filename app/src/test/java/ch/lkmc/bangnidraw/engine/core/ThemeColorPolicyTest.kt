package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeColorPolicyTest {

    @Test
    fun `every theme declares its tone`() {
        val expected = mapOf(
            AppTheme.SAFFRON to ThemeTone.LIGHT,
            AppTheme.CORAL to ThemeTone.LIGHT,
            AppTheme.VIOLET to ThemeTone.LIGHT,
            AppTheme.TEAL to ThemeTone.LIGHT,
            AppTheme.NINETIES to ThemeTone.LIGHT,
            AppTheme.SYNTHWAVE to ThemeTone.DARK,
            AppTheme.MIDNIGHT to ThemeTone.DARK,
            AppTheme.FOREST to ThemeTone.DARK,
        )

        assertEquals(AppTheme.entries.toSet(), expected.keys)
        for ((theme, tone) in expected) {
            assertEquals(tone, theme.tone, "$theme declares the wrong tone")
        }
    }

    @Test
    fun `every theme color is opaque`() {
        for (theme in AppTheme.entries) {
            val colors = ThemeColorPolicy.colors(theme)
            val argbValues = listOf(
                colors.primaryArgb,
                colors.onPrimaryArgb,
                colors.primaryContainerArgb,
                colors.onPrimaryContainerArgb,
                colors.secondaryArgb,
                colors.onSecondaryArgb,
                colors.secondaryContainerArgb,
                colors.onSecondaryContainerArgb,
                colors.backgroundArgb,
                colors.onBackgroundArgb,
                colors.surfaceArgb,
                colors.onSurfaceArgb,
                colors.surfaceVariantArgb,
                colors.onSurfaceVariantArgb,
                colors.surfaceContainerArgb,
                colors.surfaceContainerHighArgb,
                colors.outlineArgb,
                colors.outlineVariantArgb,
                colors.canvasVoidArgb,
            )

            for (argb in argbValues) {
                assertEquals(
                    OPAQUE_ALPHA,
                    argb ushr ALPHA_SHIFT,
                    "$theme has a translucent token",
                )
            }
        }
    }

    @Test
    fun `theme content colors meet text contrast`() {
        for (theme in AppTheme.entries) {
            val colors = ThemeColorPolicy.colors(theme)
            val pairs = listOf(
                "primary" to (colors.primaryArgb to colors.onPrimaryArgb),
                "primaryContainer" to
                    (colors.primaryContainerArgb to colors.onPrimaryContainerArgb),
                "secondary" to (colors.secondaryArgb to colors.onSecondaryArgb),
                "secondaryContainer" to
                    (colors.secondaryContainerArgb to colors.onSecondaryContainerArgb),
                "background" to (colors.backgroundArgb to colors.onBackgroundArgb),
                "surface" to (colors.surfaceArgb to colors.onSurfaceArgb),
                "surfaceVariant" to
                    (colors.surfaceVariantArgb to colors.onSurfaceVariantArgb),
                "surfaceContainer" to
                    (colors.surfaceContainerArgb to colors.onSurfaceArgb),
                "surfaceContainerHigh" to
                    (colors.surfaceContainerHighArgb to colors.onSurfaceArgb),
                "inversePrimary on inverseSurface" to
                    (colors.primaryContainerArgb to colors.onSurfaceArgb),
            )

            for ((role, pair) in pairs) {
                val contrast = contrastRatio(pair.first, pair.second)
                assertTrue(
                    contrast >= MIN_TEXT_CONTRAST,
                    "$theme $role content contrast is $contrast:1",
                )
            }
        }
    }

    @Test
    fun `theme outlines meet non text contrast`() {
        for (theme in AppTheme.entries) {
            val colors = ThemeColorPolicy.colors(theme)
            val contrast = contrastRatio(colors.outlineArgb, colors.surfaceArgb)

            assertTrue(
                contrast >= MIN_ICON_CONTRAST,
                "$theme outline contrast is $contrast:1",
            )
        }
    }

    @Test
    fun `theme previews and chrome palettes are distinct`() {
        val colors = AppTheme.entries.map(ThemeColorPolicy::colors)

        assertEquals(
            AppTheme.entries.size,
            colors.map { it.primaryContainerArgb }.distinct().size,
            "every named choice needs a distinct preview swatch",
        )
        assertEquals(AppTheme.entries.size, colors.distinct().size)
    }

    @Test
    fun `accent text meets contrast on chrome surfaces`() {
        for (theme in AppTheme.entries) {
            val colors = ThemeColorPolicy.colors(theme)
            val pairs = listOf(
                "primary on background" to (colors.primaryArgb to colors.backgroundArgb),
                "primary on surface" to (colors.primaryArgb to colors.surfaceArgb),
                "primary on surfaceContainer" to
                    (colors.primaryArgb to colors.surfaceContainerArgb),
                "secondary on surface" to (colors.secondaryArgb to colors.surfaceArgb),
                "secondary on surfaceContainer" to
                    (colors.secondaryArgb to colors.surfaceContainerArgb),
            )

            for ((role, pair) in pairs) {
                val contrast = contrastRatio(pair.first, pair.second)

                assertTrue(
                    contrast >= MIN_TEXT_CONTRAST,
                    "$theme $role contrast is $contrast:1",
                )
            }
        }
    }

    @Test
    fun `accent boundaries meet non text contrast`() {
        for (theme in AppTheme.entries) {
            val colors = ThemeColorPolicy.colors(theme)
            val pairs = listOf(
                "selected layer marker" to
                    (colors.secondaryArgb to colors.secondaryContainerArgb),
                "active tool border" to
                    (colors.primaryArgb to colors.primaryContainerArgb),
            )

            for ((role, pair) in pairs) {
                val contrast = contrastRatio(pair.first, pair.second)

                assertTrue(
                    contrast >= MIN_ICON_CONTRAST,
                    "$theme $role contrast is $contrast:1",
                )
            }
        }
    }

    @Test
    fun `error roles pin the tone baselines`() {
        val light = ThemeColorPolicy.errorColors(ThemeTone.LIGHT)
        val dark = ThemeColorPolicy.errorColors(ThemeTone.DARK)

        assertEquals(0xFFB3261E.toInt(), light.errorArgb)
        assertEquals(0xFFFFFFFF.toInt(), light.onErrorArgb)
        assertEquals(0xFFF9DEDC.toInt(), light.errorContainerArgb)
        assertEquals(0xFF410E0B.toInt(), light.onErrorContainerArgb)
        assertEquals(0xFFF2B8B5.toInt(), dark.errorArgb)
        assertEquals(0xFF601410.toInt(), dark.onErrorArgb)
        assertEquals(0xFF8C1D18.toInt(), dark.errorContainerArgb)
        assertEquals(0xFFF9DEDC.toInt(), dark.onErrorContainerArgb)
    }

    @Test
    fun `system bar surfaces support the tone's icons`() {
        for (theme in AppTheme.entries) {
            val colors = ThemeColorPolicy.colors(theme)
            val iconArgb = when (theme.tone) {
                ThemeTone.LIGHT -> DARK_SYSTEM_ICON_ARGB
                ThemeTone.DARK -> LIGHT_SYSTEM_ICON_ARGB
            }
            val surfaces = listOf(
                "background" to colors.backgroundArgb,
                "surface" to colors.surfaceArgb,
                "surfaceContainer" to colors.surfaceContainerArgb,
                "surfaceContainerHigh" to colors.surfaceContainerHighArgb,
            )

            for ((role, surface) in surfaces) {
                val contrast = contrastRatio(iconArgb, surface)

                assertTrue(
                    contrast >= MIN_ICON_CONTRAST,
                    "$theme $role cannot support ${theme.tone} system icons: $contrast:1",
                )
            }
        }
    }

    @Test
    fun `error roles meet text contrast in every theme`() {
        for (theme in AppTheme.entries) {
            val colors = ThemeColorPolicy.colors(theme)
            val errors = ThemeColorPolicy.errorColors(theme.tone)
            val pairs = listOf(
                "error/onError" to (errors.errorArgb to errors.onErrorArgb),
                "errorContainer/onErrorContainer" to
                    (errors.errorContainerArgb to errors.onErrorContainerArgb),
                "error text/surface" to (errors.errorArgb to colors.surfaceArgb),
                "error text/background" to (errors.errorArgb to colors.backgroundArgb),
                "error text/surfaceContainerHigh" to
                    (errors.errorArgb to colors.surfaceContainerHighArgb),
            )

            for ((role, pair) in pairs) {
                val contrast = contrastRatio(pair.first, pair.second)
                assertTrue(
                    contrast >= MIN_TEXT_CONTRAST,
                    "$theme $role contrast is $contrast:1",
                )
            }
        }
    }

    private companion object {
        val DARK_SYSTEM_ICON_ARGB = 0xFF000000.toInt()
        val LIGHT_SYSTEM_ICON_ARGB = 0xFFFFFFFF.toInt()
        const val MIN_TEXT_CONTRAST = 4.5
        const val MIN_ICON_CONTRAST = 3.0
        const val ALPHA_SHIFT = 24
        const val OPAQUE_ALPHA = 0xFF
    }
}
