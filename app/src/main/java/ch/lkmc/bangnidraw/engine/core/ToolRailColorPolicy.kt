package ch.lkmc.bangnidraw.engine.core

internal enum class ToolButtonEmphasis { ACTIVE, INACTIVE }

internal data class ToolRailColors(
    val containerArgb: Int,
    val iconArgb: Int,
)

/** Contrast-safe rail colors kept independent of Compose and Android. */
internal object ToolRailColorPolicy {

    fun colors(theme: AppTheme, emphasis: ToolButtonEmphasis): ToolRailColors {
        val colors = ThemeColorPolicy.colors(theme)

        return when (emphasis) {
            ToolButtonEmphasis.ACTIVE -> ToolRailColors(
                colors.primaryContainerArgb,
                colors.onPrimaryContainerArgb,
            )
            ToolButtonEmphasis.INACTIVE -> ToolRailColors(
                colors.surfaceVariantArgb,
                colors.onSurfaceVariantArgb,
            )
        }
    }
}
