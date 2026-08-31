package ch.lkmc.bangnidraw.engine.core

enum class CanvasOrientation {
    LANDSCAPE,
    PORTRAIT;

    companion object {
        internal fun forWindow(widthPx: Int, heightPx: Int): CanvasOrientation {
            require(widthPx >= 0 && heightPx >= 0) { "window dimensions must not be negative" }

            return if (widthPx > heightPx) LANDSCAPE else PORTRAIT
        }
    }
}

data class NewCanvasDefaults(
    val presetIndex: Int,
    val orientation: CanvasOrientation,
)

/** Chooses the largest enabled preset that the current window can show at 1:1. */
object NewCanvasDefaultsPolicy {

    fun forWindow(
        presets: List<CanvasPreset>,
        windowWidthPx: Int,
        windowHeightPx: Int,
    ): NewCanvasDefaults {
        require(presets.isNotEmpty()) { "New Canvas needs at least one preset" }

        val orientation = CanvasOrientation.forWindow(windowWidthPx, windowHeightPx)
        val indexed = presets.withIndex()
        val enabled = indexed.filter { it.value.enabled }
        val fitting = enabled.filter { (_, preset) ->
            val size = preset.oriented(orientation)
            size.width <= windowWidthPx && size.height <= windowHeightPx
        }
        val selected = fitting.maxByOrNull { it.value.size.tilesPerLayer }
            // A tiny or inset window may fit no preset at 1:1. Keep Create
            // usable by falling back to the least expensive enabled row.
            ?: enabled.minByOrNull { it.value.size.tilesPerLayer }
            ?: indexed.first()

        return NewCanvasDefaults(selected.index, orientation)
    }
}

enum class CustomSizeFieldArrangement { ROW, COLUMN }

private const val CUSTOM_SIZE_FIELD_COUNT = 2
private const val CUSTOM_SIZE_GAP_COUNT = 2

data class CustomSizeFieldLayout(
    val arrangement: CustomSizeFieldArrangement,
    val fieldWidthDp: Float,
    val gapDp: Float,
    val separatorWidthDp: Float,
) {
    val hasUsableWidth: Boolean get() = fieldWidthDp > 0f

    val occupiedWidthDp: Float
        get() = when (arrangement) {
            CustomSizeFieldArrangement.ROW ->
                CUSTOM_SIZE_FIELD_COUNT * fieldWidthDp +
                    separatorWidthDp +
                    CUSTOM_SIZE_GAP_COUNT * gapDp
            CustomSizeFieldArrangement.COLUMN -> fieldWidthDp
        }
}

/** Pure compact-layout decisions for the New Canvas dialog. */
object NewCanvasLayoutPolicy {

    const val PAPER_TARGET_DP = 48f
    const val PAPER_VISUAL_DP = 28f

    fun customSizeFields(contentWidthDp: Float, fontScale: Float): CustomSizeFieldLayout {
        require(fontScale.isFinite() && fontScale > 0f) {
            "fontScale must be finite and positive"
        }

        val scale = maxOf(1f, fontScale)
        val minimumFieldWidth = BASE_MIN_FIELD_WIDTH_DP * scale
        val separatorWidth = BASE_SEPARATOR_WIDTH_DP * scale
        // BoxWithConstraints reports an unbounded width as Dp.Unspecified (NaN).
        if (contentWidthDp.isNaN() || contentWidthDp.isInfinite()) {
            return layout(CustomSizeFieldArrangement.ROW, minimumFieldWidth, separatorWidth)
        }
        require(contentWidthDp >= 0f) { "contentWidthDp must be non-negative" }

        val fixedWidth = separatorWidth + GAP_DP * CUSTOM_SIZE_GAP_COUNT
        val rowFieldWidth = maxOf(0f, contentWidthDp - fixedWidth) / CUSTOM_SIZE_FIELD_COUNT
        if (rowFieldWidth >= minimumFieldWidth) {
            return layout(CustomSizeFieldArrangement.ROW, rowFieldWidth, separatorWidth)
        }

        return layout(CustomSizeFieldArrangement.COLUMN, contentWidthDp, separatorWidth)
    }

    private fun layout(
        arrangement: CustomSizeFieldArrangement,
        fieldWidthDp: Float,
        separatorWidthDp: Float,
    ): CustomSizeFieldLayout = CustomSizeFieldLayout(
        arrangement = arrangement,
        fieldWidthDp = fieldWidthDp,
        gapDp = GAP_DP,
        separatorWidthDp = separatorWidthDp,
    )

    private const val GAP_DP = 8f
    private const val BASE_SEPARATOR_WIDTH_DP = 24f

    /** Keeps the value and floating label readable at the current text scale. */
    private const val BASE_MIN_FIELD_WIDTH_DP = 88f
}
