package ch.lkmc.bangnidraw.engine.core

/** App-owned palettes. System and wallpaper colours never alter them. */
internal enum class AppTheme {
    SAFFRON,
    CORAL,
    VIOLET,
    TEAL;

    companion object {
        val DEFAULT = SAFFRON

        fun fromStored(value: String?): AppTheme =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/** Material roles kept pure so every palette can be contrast-tested on the JVM. */
internal data class ThemeColors(
    val primaryArgb: Int,
    val onPrimaryArgb: Int,
    val primaryContainerArgb: Int,
    val onPrimaryContainerArgb: Int,
    val secondaryArgb: Int,
    val onSecondaryArgb: Int,
    val secondaryContainerArgb: Int,
    val onSecondaryContainerArgb: Int,
    val backgroundArgb: Int,
    val onBackgroundArgb: Int,
    val surfaceArgb: Int,
    val onSurfaceArgb: Int,
    val surfaceVariantArgb: Int,
    val onSurfaceVariantArgb: Int,
    val surfaceContainerArgb: Int,
    val surfaceContainerHighArgb: Int,
    val outlineArgb: Int,
    val outlineVariantArgb: Int,
    val canvasVoidArgb: Int,
)

internal object ThemeColorPolicy {

    fun colors(theme: AppTheme): ThemeColors = when (theme) {
        AppTheme.SAFFRON -> SAFFRON_COLORS
        AppTheme.CORAL -> CORAL_COLORS
        AppTheme.VIOLET -> VIOLET_COLORS
        AppTheme.TEAL -> TEAL_COLORS
    }

    private val ON_STRONG_ACCENT = 0xFFFFFFFF.toInt()
    private val NEUTRAL_CANVAS_VOID = 0xFFB8B2AA.toInt()
    private val SAFFRON_ON_SURFACE = 0xFF241F18.toInt()
    private val SAFFRON_SURFACE_VARIANT = 0xFFF1E6D4.toInt()
    private val CORAL_ON_SURFACE = 0xFF26191B.toInt()
    private val CORAL_SURFACE_VARIANT = 0xFFF4E1E3.toInt()
    private val VIOLET_ON_SURFACE = 0xFF211A29.toInt()
    private val VIOLET_SURFACE_VARIANT = 0xFFEDE5F3.toInt()
    private val TEAL_ON_SURFACE = 0xFF17211F.toInt()
    private val TEAL_SURFACE_VARIANT = 0xFFDAECE7.toInt()

    private val SAFFRON_COLORS = ThemeColors(
        primaryArgb = 0xFF925B00.toInt(),
        onPrimaryArgb = ON_STRONG_ACCENT,
        primaryContainerArgb = 0xFFFFB300.toInt(),
        onPrimaryContainerArgb = 0xFF241A00.toInt(),
        secondaryArgb = 0xFF5C50A4.toInt(),
        onSecondaryArgb = ON_STRONG_ACCENT,
        secondaryContainerArgb = 0xFFE6DFFF.toInt(),
        onSecondaryContainerArgb = 0xFF1A1048.toInt(),
        backgroundArgb = 0xFFFFF8EC.toInt(),
        onBackgroundArgb = SAFFRON_ON_SURFACE,
        surfaceArgb = 0xFFFFFBF5.toInt(),
        onSurfaceArgb = SAFFRON_ON_SURFACE,
        surfaceVariantArgb = SAFFRON_SURFACE_VARIANT,
        onSurfaceVariantArgb = 0xFF554D42.toInt(),
        surfaceContainerArgb = 0xFFF8EFE1.toInt(),
        surfaceContainerHighArgb = SAFFRON_SURFACE_VARIANT,
        outlineArgb = 0xFF756D61.toInt(),
        outlineVariantArgb = 0xFFCEC4B3.toInt(),
        canvasVoidArgb = NEUTRAL_CANVAS_VOID,
    )

    private val CORAL_COLORS = ThemeColors(
        primaryArgb = 0xFFA03045.toInt(),
        onPrimaryArgb = ON_STRONG_ACCENT,
        primaryContainerArgb = 0xFFFF8F9E.toInt(),
        onPrimaryContainerArgb = 0xFF31000C.toInt(),
        secondaryArgb = 0xFF326A78.toInt(),
        onSecondaryArgb = ON_STRONG_ACCENT,
        secondaryContainerArgb = 0xFFBCEAF5.toInt(),
        onSecondaryContainerArgb = 0xFF002027.toInt(),
        backgroundArgb = 0xFFFFF5F5.toInt(),
        onBackgroundArgb = CORAL_ON_SURFACE,
        surfaceArgb = 0xFFFFF9F9.toInt(),
        onSurfaceArgb = CORAL_ON_SURFACE,
        surfaceVariantArgb = CORAL_SURFACE_VARIANT,
        onSurfaceVariantArgb = 0xFF59474A.toInt(),
        surfaceContainerArgb = 0xFFF9EAEB.toInt(),
        surfaceContainerHighArgb = CORAL_SURFACE_VARIANT,
        outlineArgb = 0xFF79676A.toInt(),
        outlineVariantArgb = 0xFFD3C1C3.toInt(),
        canvasVoidArgb = NEUTRAL_CANVAS_VOID,
    )

    private val VIOLET_COLORS = ThemeColors(
        primaryArgb = 0xFF6D45B5.toInt(),
        onPrimaryArgb = ON_STRONG_ACCENT,
        primaryContainerArgb = 0xFFC6A6FF.toInt(),
        onPrimaryContainerArgb = 0xFF21004A.toInt(),
        secondaryArgb = 0xFFA13A72.toInt(),
        onSecondaryArgb = ON_STRONG_ACCENT,
        secondaryContainerArgb = 0xFFFFD7E8.toInt(),
        onSecondaryContainerArgb = 0xFF3D0024.toInt(),
        backgroundArgb = 0xFFFAF6FF.toInt(),
        onBackgroundArgb = VIOLET_ON_SURFACE,
        surfaceArgb = 0xFFFDF9FF.toInt(),
        onSurfaceArgb = VIOLET_ON_SURFACE,
        surfaceVariantArgb = VIOLET_SURFACE_VARIANT,
        onSurfaceVariantArgb = 0xFF504858.toInt(),
        surfaceContainerArgb = 0xFFF5EDF9.toInt(),
        surfaceContainerHighArgb = VIOLET_SURFACE_VARIANT,
        outlineArgb = 0xFF716878.toInt(),
        outlineVariantArgb = 0xFFCBC2D1.toInt(),
        canvasVoidArgb = NEUTRAL_CANVAS_VOID,
    )

    private val TEAL_COLORS = ThemeColors(
        primaryArgb = 0xFF006B60.toInt(),
        onPrimaryArgb = ON_STRONG_ACCENT,
        primaryContainerArgb = 0xFF58D7C3.toInt(),
        onPrimaryContainerArgb = 0xFF00201C.toInt(),
        secondaryArgb = 0xFF765A00.toInt(),
        onSecondaryArgb = ON_STRONG_ACCENT,
        secondaryContainerArgb = 0xFFFFE083.toInt(),
        onSecondaryContainerArgb = 0xFF241A00.toInt(),
        backgroundArgb = 0xFFF2FBF8.toInt(),
        onBackgroundArgb = TEAL_ON_SURFACE,
        surfaceArgb = 0xFFF7FCFA.toInt(),
        onSurfaceArgb = TEAL_ON_SURFACE,
        surfaceVariantArgb = TEAL_SURFACE_VARIANT,
        onSurfaceVariantArgb = 0xFF41514D.toInt(),
        surfaceContainerArgb = 0xFFE7F3EF.toInt(),
        surfaceContainerHighArgb = TEAL_SURFACE_VARIANT,
        outlineArgb = 0xFF62716D.toInt(),
        outlineVariantArgb = 0xFFBCCBC7.toInt(),
        canvasVoidArgb = NEUTRAL_CANVAS_VOID,
    )
}
