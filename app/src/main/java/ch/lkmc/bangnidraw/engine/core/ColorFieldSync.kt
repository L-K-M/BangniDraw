package ch.lkmc.bangnidraw.engine.core

/** Editable hex text and the color selected by that exact text. */
internal data class ColorFieldDrafts(
    val hex: String,
    val selectedColor: Int?,
)

/** Keeps a parent's color echo from replacing an in-progress hex draft. */
internal object ColorFieldSync {
    fun fromColor(color: Int): ColorFieldDrafts = ColorFieldDrafts(
        hex = ColorText.hex(color),
        selectedColor = color,
    )

    fun editHex(current: ColorFieldDrafts, hex: String): ColorFieldDrafts = current.copy(
        hex = hex,
        selectedColor = ColorText.parseHex(hex),
    )

    fun syncParent(current: ColorFieldDrafts, color: Int): ColorFieldDrafts {
        if (current.selectedColor == color) return current

        return fromColor(color)
    }
}
