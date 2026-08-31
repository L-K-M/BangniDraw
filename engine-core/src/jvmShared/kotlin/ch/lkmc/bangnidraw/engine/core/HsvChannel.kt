package ch.lkmc.bangnidraw.engine.core

/** One independently adjustable HSV component. */
enum class HsvChannel(
    val range: ClosedFloatingPointRange<Float>,
    val steps: Int,
) {
    HUE(0f..HUE_MAX, HUE_STEPS),
    SATURATION(0f..PERCENT_MAX, PERCENT_STEPS),
    VALUE(0f..PERCENT_MAX, PERCENT_STEPS),
    ;

    fun read(color: HsvColor): Float = when (this) {
        HUE -> color.h
        SATURATION -> color.s * PERCENT_MAX
        VALUE -> color.v * PERCENT_MAX
    }

    fun replace(color: HsvColor, value: Float): HsvColor {
        val adjusted = value.coerceIn(range)
        return when (this) {
            HUE -> color.copy(h = adjusted)
            SATURATION -> color.copy(s = adjusted / PERCENT_MAX)
            VALUE -> color.copy(v = adjusted / PERCENT_MAX)
        }
    }
}

private const val HUE_MAX = 360f
private const val HUE_STEPS = 359
private const val PERCENT_MAX = 100f
private const val PERCENT_STEPS = 99
