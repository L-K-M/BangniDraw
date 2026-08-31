package ch.lkmc.bangnidraw.engine.core

/** Palette tone; drives system-bar icon appearance and the neutral canvas void. */
internal enum class ThemeTone { LIGHT, DARK }

/**
 * App-owned palettes, light and dark. System and wallpaper colours never
 * alter them; Android's dark mode is neither an option nor an input.
 */
internal enum class AppTheme(val tone: ThemeTone) {
    SAFFRON(ThemeTone.LIGHT),
    CORAL(ThemeTone.LIGHT),
    VIOLET(ThemeTone.LIGHT),
    TEAL(ThemeTone.LIGHT),
    NINETIES(ThemeTone.LIGHT),
    SYNTHWAVE(ThemeTone.DARK),
    MIDNIGHT(ThemeTone.DARK),
    FOREST(ThemeTone.DARK);

    companion object {
        val DEFAULT = SAFFRON

        fun fromStored(value: String?): AppTheme =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/** Base palette tokens; Compose expands them into every Material role. */
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

/** Tone-level error roles; dark surfaces need a lighter error than light ones. */
internal data class ErrorColors(
    val errorArgb: Int,
    val onErrorArgb: Int,
    val errorContainerArgb: Int,
    val onErrorContainerArgb: Int,
)

internal object ThemeColorPolicy {

    fun colors(theme: AppTheme): ThemeColors = when (theme) {
        AppTheme.SAFFRON -> SAFFRON_COLORS
        AppTheme.CORAL -> CORAL_COLORS
        AppTheme.VIOLET -> VIOLET_COLORS
        AppTheme.TEAL -> TEAL_COLORS
        AppTheme.NINETIES -> NINETIES_COLORS
        AppTheme.SYNTHWAVE -> SYNTHWAVE_COLORS
        AppTheme.MIDNIGHT -> MIDNIGHT_COLORS
        AppTheme.FOREST -> FOREST_COLORS
    }

    /** Shared error roles per tone: Material's baseline, contrast-verified per palette. */
    fun errorColors(tone: ThemeTone): ErrorColors = when (tone) {
        ThemeTone.LIGHT -> LIGHT_ERROR_COLORS
        ThemeTone.DARK -> DARK_ERROR_COLORS
    }

    private val ON_STRONG_ACCENT = 0xFFFFFFFF.toInt()
    private val NEUTRAL_CANVAS_VOID = 0xFFB8B2AA.toInt()
    private val NEUTRAL_CANVAS_VOID_DARK = 0xFF171717.toInt()
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

    private val NINETIES_COLORS = ThemeColors(
        primaryArgb = 0xFFC1276B.toInt(),
        onPrimaryArgb = ON_STRONG_ACCENT,
        primaryContainerArgb = 0xFFFFD23F.toInt(),
        onPrimaryContainerArgb = 0xFF3D2C00.toInt(),
        secondaryArgb = 0xFF00727B.toInt(),
        onSecondaryArgb = ON_STRONG_ACCENT,
        secondaryContainerArgb = 0xFF9EE8E0.toInt(),
        onSecondaryContainerArgb = 0xFF002F33.toInt(),
        backgroundArgb = 0xFFFFF8EC.toInt(),
        onBackgroundArgb = 0xFF2A1E33.toInt(),
        surfaceArgb = 0xFFFFFCF5.toInt(),
        onSurfaceArgb = 0xFF2A1E33.toInt(),
        surfaceVariantArgb = 0xFFF3E3F0.toInt(),
        onSurfaceVariantArgb = 0xFF584463.toInt(),
        surfaceContainerArgb = 0xFFF7EBD8.toInt(),
        surfaceContainerHighArgb = 0xFFF3E3F0.toInt(),
        outlineArgb = 0xFF78657F.toInt(),
        outlineVariantArgb = 0xFFD3C3DA.toInt(),
        canvasVoidArgb = NEUTRAL_CANVAS_VOID,
    )

    private val SYNTHWAVE_COLORS = ThemeColors(
        primaryArgb = 0xFFFF71CE.toInt(),
        onPrimaryArgb = 0xFF3A0026.toInt(),
        primaryContainerArgb = 0xFF6E1E5C.toInt(),
        onPrimaryContainerArgb = 0xFFFFD6EF.toInt(),
        secondaryArgb = 0xFF01CDFE.toInt(),
        onSecondaryArgb = 0xFF00303D.toInt(),
        secondaryContainerArgb = 0xFF0C4356.toInt(),
        onSecondaryContainerArgb = 0xFFBAEFFF.toInt(),
        backgroundArgb = 0xFF120A2E.toInt(),
        onBackgroundArgb = 0xFFEDE6FF.toInt(),
        surfaceArgb = 0xFF1B0F3B.toInt(),
        onSurfaceArgb = 0xFFEDE6FF.toInt(),
        surfaceVariantArgb = 0xFF33205C.toInt(),
        onSurfaceVariantArgb = 0xFFC9B4EC.toInt(),
        surfaceContainerArgb = 0xFF251548.toInt(),
        surfaceContainerHighArgb = 0xFF33205C.toInt(),
        outlineArgb = 0xFF9A86CC.toInt(),
        outlineVariantArgb = 0xFF4A3575.toInt(),
        canvasVoidArgb = NEUTRAL_CANVAS_VOID_DARK,
    )

    private val MIDNIGHT_COLORS = ThemeColors(
        primaryArgb = 0xFFA3BFFA.toInt(),
        onPrimaryArgb = 0xFF0F1D3D.toInt(),
        primaryContainerArgb = 0xFF324A75.toInt(),
        onPrimaryContainerArgb = 0xFFDEE6FF.toInt(),
        secondaryArgb = 0xFF8FD4B4.toInt(),
        onSecondaryArgb = 0xFF0E2F23.toInt(),
        secondaryContainerArgb = 0xFF234A3B.toInt(),
        onSecondaryContainerArgb = 0xFFD2F1DF.toInt(),
        backgroundArgb = 0xFF0F141D.toInt(),
        onBackgroundArgb = 0xFFDFE4F2.toInt(),
        surfaceArgb = 0xFF151B27.toInt(),
        onSurfaceArgb = 0xFFDFE4F2.toInt(),
        surfaceVariantArgb = 0xFF273142.toInt(),
        onSurfaceVariantArgb = 0xFFB3BDD4.toInt(),
        surfaceContainerArgb = 0xFF1C2331.toInt(),
        surfaceContainerHighArgb = 0xFF273142.toInt(),
        outlineArgb = 0xFF8490A8.toInt(),
        outlineVariantArgb = 0xFF3A4459.toInt(),
        canvasVoidArgb = NEUTRAL_CANVAS_VOID_DARK,
    )

    private val FOREST_COLORS = ThemeColors(
        primaryArgb = 0xFFA2D47A.toInt(),
        onPrimaryArgb = 0xFF17300B.toInt(),
        primaryContainerArgb = 0xFF33511F.toInt(),
        onPrimaryContainerArgb = 0xFFDFF0C6.toInt(),
        secondaryArgb = 0xFFE0B45E.toInt(),
        onSecondaryArgb = 0xFF3A2705.toInt(),
        secondaryContainerArgb = 0xFF59431C.toInt(),
        onSecondaryContainerArgb = 0xFFF7E3B5.toInt(),
        backgroundArgb = 0xFF0F1710.toInt(),
        onBackgroundArgb = 0xFFDEE8DC.toInt(),
        surfaceArgb = 0xFF16201A.toInt(),
        onSurfaceArgb = 0xFFDEE8DC.toInt(),
        surfaceVariantArgb = 0xFF28362B.toInt(),
        onSurfaceVariantArgb = 0xFFB6C7B4.toInt(),
        surfaceContainerArgb = 0xFF1D2A22.toInt(),
        surfaceContainerHighArgb = 0xFF28362B.toInt(),
        outlineArgb = 0xFF8A9C88.toInt(),
        outlineVariantArgb = 0xFF3D4C3F.toInt(),
        canvasVoidArgb = NEUTRAL_CANVAS_VOID_DARK,
    )

    private val LIGHT_ERROR_COLORS = ErrorColors(
        errorArgb = 0xFFB3261E.toInt(),
        onErrorArgb = 0xFFFFFFFF.toInt(),
        errorContainerArgb = 0xFFF9DEDC.toInt(),
        onErrorContainerArgb = 0xFF410E0B.toInt(),
    )

    private val DARK_ERROR_COLORS = ErrorColors(
        errorArgb = 0xFFF2B8B5.toInt(),
        onErrorArgb = 0xFF601410.toInt(),
        errorContainerArgb = 0xFF8C1D18.toInt(),
        onErrorContainerArgb = 0xFFF9DEDC.toInt(),
    )
}
