package ch.lkmc.bangnidraw.engine.core

enum class RgbFieldArrangement { ROW, COLUMN }

data class RgbFieldLayout(
    val arrangement: RgbFieldArrangement,
    val fieldWidthDp: Float,
    val gapDp: Float,
    val fieldCount: Int,
) {
    val hasUsableWidth: Boolean get() = fieldWidthDp > 0f

    val occupiedWidthDp: Float
        get() = when (arrangement) {
            RgbFieldArrangement.ROW ->
                fieldCount * fieldWidthDp + (fieldCount - 1) * gapDp
            RgbFieldArrangement.COLUMN -> fieldWidthDp
        }
}

/** Keeps channel values readable as panel width and system text scale change. */
object RgbFieldLayoutPolicy {

    fun forContentWidth(contentWidthDp: Float, fontScale: Float): RgbFieldLayout {
        require(!contentWidthDp.isNaN() && contentWidthDp >= 0f) {
            "contentWidthDp must be non-negative"
        }
        require(fontScale.isFinite() && fontScale > 0f) {
            "fontScale must be finite and positive"
        }

        val minimumFieldWidth = BASE_MIN_FIELD_WIDTH_DP * maxOf(1f, fontScale)
        if (!contentWidthDp.isFinite()) {
            return layout(RgbFieldArrangement.ROW, minimumFieldWidth)
        }

        val totalGap = GAP_DP * (FIELD_COUNT - 1)
        val rowFieldWidth = maxOf(0f, contentWidthDp - totalGap) / FIELD_COUNT
        if (rowFieldWidth >= minimumFieldWidth) {
            return layout(RgbFieldArrangement.ROW, rowFieldWidth)
        }

        return layout(RgbFieldArrangement.COLUMN, contentWidthDp)
    }

    private fun layout(
        arrangement: RgbFieldArrangement,
        fieldWidthDp: Float,
    ): RgbFieldLayout = RgbFieldLayout(
        arrangement = arrangement,
        fieldWidthDp = fieldWidthDp,
        gapDp = GAP_DP,
        fieldCount = FIELD_COUNT,
    )

    private const val FIELD_COUNT = 3
    private const val GAP_DP = 6f

    /** Holds a three-digit channel at default text scale; larger text scales this floor. */
    private const val BASE_MIN_FIELD_WIDTH_DP = 64f
}
