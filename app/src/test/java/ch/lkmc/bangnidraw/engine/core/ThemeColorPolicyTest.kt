package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeColorPolicyTest {

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
    fun `accent boundaries meet non text contrast`() {
        for (theme in AppTheme.entries) {
            val colors = ThemeColorPolicy.colors(theme)
            val pairs = listOf(
                "primary on surface" to (colors.primaryArgb to colors.surfaceArgb),
                "secondary on surface" to (colors.secondaryArgb to colors.surfaceArgb),
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

    private companion object {
        const val MIN_TEXT_CONTRAST = 4.5
        const val MIN_ICON_CONTRAST = 3.0
        const val ALPHA_SHIFT = 24
        const val OPAQUE_ALPHA = 0xFF
    }
}
