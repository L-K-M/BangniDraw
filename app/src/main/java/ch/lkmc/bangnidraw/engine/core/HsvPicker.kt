package ch.lkmc.bangnidraw.engine.core

import kotlin.math.atan2
import kotlin.math.hypot

/** Pointer math for the hue ring around an independent SV square. */
object HsvPicker {
    fun select(x: Float, y: Float, size: Float, current: HsvColor): HsvColor {
        require(size > 0f) { "picker size must be positive" }
        val center = size / 2f
        val dx = x - center
        val dy = y - center
        val radius = hypot(dx, dy)
        if (radius >= size * RING_INNER_RADIUS) {
            val degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            val hue = if (degrees < 0f) degrees + FULL_HUE else degrees
            return current.copy(h = hue)
        }

        val squareHalf = size * SQUARE_HALF_EDGE
        val saturation = ((x - (center - squareHalf)) / (squareHalf * 2f)).coerceIn(0f, 1f)
        val value = (1f - (y - (center - squareHalf)) / (squareHalf * 2f)).coerceIn(0f, 1f)
        return current.copy(s = saturation, v = value)
    }

    const val RING_INNER_RADIUS = 0.38f
    const val SQUARE_HALF_EDGE = 0.25f
    private const val FULL_HUE = 360f
}
