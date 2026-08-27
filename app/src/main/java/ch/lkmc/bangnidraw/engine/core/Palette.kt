package ch.lkmc.bangnidraw.engine.core

import kotlinx.serialization.Serializable

/** A global color palette; built-ins are immutable and never written to disk. */
@Serializable
data class Palette(
    val id: String,
    val name: String,
    val swatches: List<Int>,
    val builtIn: Boolean = false,
) {
    init {
        require(isSafePathSegment(id)) { "palette id must be a safe path segment" }
        require(name.isNotBlank()) { "palette name must not be blank" }
    }
}

/** Persistent wells plus the panel-local interpolation point. */
data class DishState(val a: Int, val b: Int, val t: Float = DEFAULT_T) {
    init {
        require(t in 0f..1f) { "dish t must be 0..1, was $t" }
    }

    companion object {
        const val DEFAULT_T = 0.5f
    }
}

enum class DishWell { A, B }

enum class ColorPickTarget { Current, WellA, WellB }

/** Original values and pure preview/cancel behavior for an eyedropper gesture. */
data class ColorPickSession(
    val target: ColorPickTarget,
    val currentBefore: Int,
    val dishBefore: DishState,
) {
    data class Result(val current: Int, val dish: DishState)

    fun preview(color: Int, current: Int, dish: DishState): Result = when (target) {
        ColorPickTarget.Current -> Result(color, dish)
        ColorPickTarget.WellA -> Result(current, dish.copy(a = color))
        ColorPickTarget.WellB -> Result(current, dish.copy(b = color))
    }

    fun cancel(): Result = Result(currentBefore, dishBefore)

    val changesDish: Boolean get() = target != ColorPickTarget.Current
}

/** Reversible preview state when the eyedropper edits a user swatch. */
data class PaletteSwatchPickSession(
    val paletteId: String,
    val index: Int,
    val colorBefore: Int,
) {
    init {
        require(isSafePathSegment(paletteId)) { "palette id must be a safe path segment" }
        require(index >= 0) { "swatch index must be non-negative" }
    }

    fun preview(palette: Palette, color: Int): Palette {
        if (palette.id != paletteId) return palette
        return PalettePolicy.replace(palette, index, color)
    }

    fun cancel(palette: Palette): Palette = preview(palette, colorBefore)
}

/** The color-panel slice owned by CanvasViewModel. */
data class ColorUiState(
    val current: Int,
    val previous: Int,
    val palettes: List<Palette>,
    val activePaletteId: String,
    val dish: DishState,
    val mixerChoice: MixerChoice,
    val pigmentMixerAvailable: Boolean,
) {
    val mixerIsPigment: Boolean
        get() = mixerChoice == MixerChoice.PIGMENT

    val activePalette: Palette
        get() = palettes.firstOrNull { it.id == activePaletteId }
            ?: palettes.firstOrNull()
            ?: PaletteCatalog.Painters
}

/** Immutable palettes and their documented color tables. */
object PaletteCatalog {
    val Painters = Palette(
        id = PAINTERS_ID,
        name = PAINTERS_NAME,
        swatches = listOf(
            CADMIUM_YELLOW_ARGB.toInt(),
            HANSA_YELLOW_ARGB.toInt(),
            CADMIUM_ORANGE_ARGB.toInt(),
            CADMIUM_RED_ARGB.toInt(),
            QUINACRIDONE_MAGENTA_ARGB.toInt(),
            COBALT_VIOLET_ARGB.toInt(),
            ULTRAMARINE_BLUE_ARGB.toInt(),
            COBALT_BLUE_ARGB.toInt(),
            PHTHALO_BLUE_ARGB.toInt(),
            PHTHALO_GREEN_ARGB.toInt(),
            PERMANENT_GREEN_ARGB.toInt(),
            SAP_GREEN_ARGB.toInt(),
            BURNT_SIENNA_ARGB.toInt(),
            TITANIUM_WHITE_ARGB.toInt(),
            IVORY_BLACK_ARGB.toInt(),
        ),
        builtIn = true,
    )

    val Basic = Palette(
        id = BASIC_ID,
        name = BASIC_NAME,
        swatches = listOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
            0xFFFF0000.toInt(), 0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(), 0xFF00FFFF.toInt(),
            0xFF0000FF.toInt(), 0xFFFF00FF.toInt(),
            0xFF800000.toInt(), 0xFF808000.toInt(),
            0xFF008000.toInt(), 0xFF008080.toInt(),
            0xFF000080.toInt(), 0xFF800080.toInt(),
            0xFFFF8000.toInt(), 0xFF804000.toInt(),
            0xFF202020.toInt(), 0xFF404040.toInt(),
            0xFF606060.toInt(), 0xFF808080.toInt(),
            0xFFC0C0C0.toInt(), 0xFFE0E0E0.toInt(),
        ),
        builtIn = true,
    )

    fun recent(swatches: List<Int>): Palette = Palette(
        id = RECENT_ID,
        name = RECENT_NAME,
        swatches = swatches,
        builtIn = true,
    )

    const val PAINTERS_ID = "builtin.painters"
    const val BASIC_ID = "builtin.basic"
    const val RECENT_ID = "builtin.recent"
    const val PAINTERS_NAME = "@string/palette_painters"
    const val BASIC_NAME = "@string/palette_basic"
    const val RECENT_NAME = "@string/palette_recent"
    const val MY_PALETTE_NAME = "@string/palette_my"

    const val CADMIUM_YELLOW_ARGB = 0xFFFEEC00L
    const val HANSA_YELLOW_ARGB = 0xFFFCD300L
    const val CADMIUM_ORANGE_ARGB = 0xFFFF6900L
    const val CADMIUM_RED_ARGB = 0xFFFF2702L
    const val QUINACRIDONE_MAGENTA_ARGB = 0xFF80022EL
    const val COBALT_VIOLET_ARGB = 0xFF4E0042L
    const val ULTRAMARINE_BLUE_ARGB = 0xFF190059L
    const val COBALT_BLUE_ARGB = 0xFF002185L
    const val PHTHALO_BLUE_ARGB = 0xFF0D1B44L
    const val PHTHALO_GREEN_ARGB = 0xFF003C32L
    const val PERMANENT_GREEN_ARGB = 0xFF076D16L
    const val SAP_GREEN_ARGB = 0xFF6B9404L
    const val BURNT_SIENNA_ARGB = 0xFF7B4800L
    const val TITANIUM_WHITE_ARGB = 0xFFFFFFFFL
    const val IVORY_BLACK_ARGB = 0xFF141414L
}

/** Pure palette editing and recent-color rules. */
object PalettePolicy {
    fun noteRecent(colors: List<Int>, painted: Int): List<Int> =
        buildList(minOf(colors.size + 1, RECENT_LIMIT)) {
            add(painted)
            colors.asSequence().filter { it != painted }.take(RECENT_LIMIT - 1).forEach(::add)
        }

    fun append(palette: Palette, color: Int): Palette {
        require(!palette.builtIn) { "built-in palettes are immutable" }
        if (color in palette.swatches) return palette
        return palette.copy(swatches = palette.swatches + color)
    }

    fun replace(palette: Palette, index: Int, color: Int): Palette {
        require(!palette.builtIn) { "built-in palettes are immutable" }
        if (index !in palette.swatches.indices) return palette
        return palette.copy(swatches = palette.swatches.toMutableList().also { it[index] = color })
    }

    fun remove(palette: Palette, index: Int): Palette {
        require(!palette.builtIn) { "built-in palettes are immutable" }
        if (index !in palette.swatches.indices) return palette
        return palette.copy(swatches = palette.swatches.filterIndexed { i, _ -> i != index })
    }

    fun move(palette: Palette, from: Int, to: Int): Palette {
        require(!palette.builtIn) { "built-in palettes are immutable" }
        if (from !in palette.swatches.indices || to !in palette.swatches.indices || from == to) {
            return palette
        }
        val moved = palette.swatches.toMutableList()
        val color = moved.removeAt(from)
        moved.add(to, color)
        return palette.copy(swatches = moved)
    }

    fun upsert(palettes: List<Palette>, palette: Palette): List<Palette> {
        val index = palettes.indexOfFirst { it.id == palette.id }
        if (index < 0) return palettes + palette
        return palettes.toMutableList().also { it[index] = palette }
    }

    const val RECENT_LIMIT = 16
}

/** Stable DataStore encoding for signed ARGB integers. */
object StoredColors {
    fun encode(colors: List<Int>): String = colors.joinToString(SEPARATOR)

    fun decode(stored: String?): List<Int> = stored
        ?.split(SEPARATOR)
        ?.mapNotNull(String::toIntOrNull)
        .orEmpty()

    private const val SEPARATOR = ","
}

/** Validated hex/RGB field parsing for the color panel. */
object ColorText {
    fun parseHex(text: String): Int? {
        val raw = text.trim().removePrefix("#")
        val expanded = when (raw.length) {
            HEX_SHORT_LENGTH -> raw.flatMap { listOf(it, it) }.joinToString("")
            HEX_LONG_LENGTH -> raw
            else -> return null
        }
        val rgb = expanded.toIntOrNull(HEX_RADIX) ?: return null
        return rgb or OPAQUE_ALPHA
    }

    fun parseChannel(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..CHANNEL_MAX }

    fun hex(argb: Int): String = "#%06X".format(argb and RGB_MASK)

    private const val HEX_SHORT_LENGTH = 3
    private const val HEX_LONG_LENGTH = 6
    private const val HEX_RADIX = 16
    private const val CHANNEL_MAX = 255
    private const val OPAQUE_ALPHA = 0xFF000000.toInt()
    private const val RGB_MASK = 0x00FFFFFF
}
