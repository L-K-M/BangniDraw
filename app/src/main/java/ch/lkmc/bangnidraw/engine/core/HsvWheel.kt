package ch.lkmc.bangnidraw.engine.core

import kotlin.math.atan2
import kotlin.math.hypot

/** Pure pointer-to-HSV mapping for the color wheel. */
object HsvWheel {
    fun select(x: Float, y: Float, width: Float, height: Float, value: Float): HsvColor {
        require(width > 0f && height > 0f) { "wheel dimensions must be positive" }
        val centerX = width / 2f
        val centerY = height / 2f
        val dx = x - centerX
        val dy = y - centerY
        val radius = minOf(width, height) / 2f
        val hue = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat().let {
            if (it < 0f) it + FULL_HUE else it
        }
        val saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        val sanitizedValue = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        return HsvColor(hue, saturation, sanitizedValue)
    }

    private const val FULL_HUE = 360f
}
