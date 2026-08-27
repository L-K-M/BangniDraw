package ch.lkmc.bangnidraw.engine.core

import kotlin.math.exp
import kotlin.math.ln

/** Logarithmic rail-slider mapping from a preset's size range. */
internal object BrushSizeScale {

    fun fraction(size: Float, minimum: Float, maximum: Float): Float {
        require(minimum > 0f && maximum >= minimum)
        if (minimum == maximum) return 0f

        val clamped = size.coerceIn(minimum, maximum)
        return ((ln(clamped) - ln(minimum)) / (ln(maximum) - ln(minimum))).coerceIn(0f, 1f)
    }

    fun size(fraction: Float, minimum: Float, maximum: Float): Float {
        require(minimum > 0f && maximum >= minimum)
        if (minimum == maximum) return minimum

        val t = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        return exp(ln(minimum) + t * (ln(maximum) - ln(minimum)))
            .coerceIn(minimum, maximum)
    }

    fun adjust(
        size: Float,
        minimum: Float,
        maximum: Float,
        adjustment: SizeAdjustment,
    ): Float {
        val direction = when (adjustment) {
            SizeAdjustment.DECREASE -> -1f
            SizeAdjustment.INCREASE -> 1f
        }
        val fraction = fraction(size, minimum, maximum) + direction * KEYBOARD_STEP

        return size(fraction, minimum, maximum)
    }

    private const val KEYBOARD_STEP = 0.05f
}
