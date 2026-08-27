package ch.lkmc.bangnidraw.engine.core

internal enum class ThemeTone { LIGHT, DARK }

internal enum class ToolButtonEmphasis { ACTIVE, INACTIVE }

internal data class ToolRailColors(
    val containerArgb: Int,
    val iconArgb: Int,
)

/** Contrast-safe rail colors kept independent of Compose and Android. */
internal object ToolRailColorPolicy {

    fun colors(tone: ThemeTone, emphasis: ToolButtonEmphasis): ToolRailColors = when (tone) {
        ThemeTone.LIGHT -> when (emphasis) {
            ToolButtonEmphasis.ACTIVE -> ToolRailColors(SAFFRON, ON_ACCENT)
            ToolButtonEmphasis.INACTIVE -> ToolRailColors(PANEL_LIGHT_VARIANT, INK_LIGHT)
        }
        ThemeTone.DARK -> when (emphasis) {
            ToolButtonEmphasis.ACTIVE -> ToolRailColors(SAFFRON_DEEP, ON_ACCENT)
            ToolButtonEmphasis.INACTIVE -> ToolRailColors(PANEL_DARK_VARIANT, INK_DARK)
        }
    }

    private val PANEL_LIGHT_VARIANT = 0xFFECE8DF.toInt()
    private val INK_LIGHT = 0xFF1E1B16.toInt()
    private val PANEL_DARK_VARIANT = 0xFF2C2F36.toInt()
    private val INK_DARK = 0xFFECE8DF.toInt()
    private val SAFFRON = 0xFFFFB300.toInt()
    private val SAFFRON_DEEP = 0xFFCC8A00.toInt()
    private val ON_ACCENT = 0xFF1E1B16.toInt()
}
