package ch.lkmc.bangnidraw.engine.core

internal enum class RgbFieldArrangement { ROW, COLUMN }

internal data class RgbFieldLayout(
    val arrangement: RgbFieldArrangement,
    val fieldWidthDp: Float,
    val gapDp: Float,
    val fieldCount: Int,
) {
    val occupiedWidthDp: Float
        get() = when (arrangement) {
            RgbFieldArrangement.ROW ->
                fieldCount * fieldWidthDp + (fieldCount - 1) * gapDp
            RgbFieldArrangement.COLUMN -> fieldWidthDp
        }
}

/** Keeps channel values readable as panel width and system text scale change. */
internal object RgbFieldLayoutPolicy {

    fun forContentWidth(contentWidthDp: Float, fontScale: Float): RgbFieldLayout {
        require(contentWidthDp.isFinite() && contentWidthDp > 0f) {
            "contentWidthDp must be finite and positive"
        }
        require(fontScale.isFinite() && fontScale > 0f) {
            "fontScale must be finite and positive"
        }

        val totalGap = GAP_DP * (FIELD_COUNT - 1)
        val rowFieldWidth = maxOf(0f, contentWidthDp - totalGap) / FIELD_COUNT
        val minimumFieldWidth = BASE_MIN_FIELD_WIDTH_DP * maxOf(1f, fontScale)
        if (rowFieldWidth >= minimumFieldWidth) {
            return RgbFieldLayout(
                arrangement = RgbFieldArrangement.ROW,
                fieldWidthDp = rowFieldWidth,
                gapDp = GAP_DP,
                fieldCount = FIELD_COUNT,
            )
        }

        return RgbFieldLayout(
            arrangement = RgbFieldArrangement.COLUMN,
            fieldWidthDp = contentWidthDp,
            gapDp = GAP_DP,
            fieldCount = FIELD_COUNT,
        )
    }

    private const val FIELD_COUNT = 3
    private const val GAP_DP = 6f

    /** Holds a three-digit channel at default text scale; larger text scales this floor. */
    private const val BASE_MIN_FIELD_WIDTH_DP = 64f
}
