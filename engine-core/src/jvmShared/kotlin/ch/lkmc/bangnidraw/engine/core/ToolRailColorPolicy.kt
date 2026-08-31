package ch.lkmc.bangnidraw.engine.core

enum class ToolButtonEmphasis { ACTIVE, INACTIVE }

data class ToolRailColors(
    val containerArgb: Int,
    val iconArgb: Int,
)

/** Contrast-safe rail colors kept independent of Compose and Android. */
object ToolRailColorPolicy {

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
