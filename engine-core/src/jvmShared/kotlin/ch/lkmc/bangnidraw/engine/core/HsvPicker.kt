package ch.lkmc.bangnidraw.engine.core

import kotlin.math.atan2
import kotlin.math.hypot

/** Retains hue while ARGB is greyscale and cannot encode it. */
class HsvSelection private constructor(
    val hsv: HsvColor,
    private val syncedArgb: Int,
) {
    val argb: Int get() = hsv.toArgb()

    fun preview(next: HsvColor): HsvSelection = HsvSelection(next, syncedArgb)

    fun commit(next: HsvColor): HsvSelection = HsvSelection(next, next.toArgb())

    fun commit(argb: Int): HsvSelection = fromArgb(argb)

    fun sync(argb: Int): HsvSelection {
        if (argb == syncedArgb) return this

        return fromArgb(argb)
    }

    companion object {
        fun fromArgb(argb: Int): HsvSelection {
            val hsv = HsvColor.fromArgb(argb)
            return HsvSelection(hsv, hsv.toArgb())
        }
    }
}

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
