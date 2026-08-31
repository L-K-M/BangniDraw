package ch.lkmc.bangnidraw.engine.core

import kotlin.math.roundToInt

/** Straight-alpha CPU color used by picker and mixer decisions. */
@JvmInline
value class Argb(val value: Int) {
    val alpha: Int get() = Composite.alpha(value)
    val red: Int get() = Composite.red(value)
    val green: Int get() = Composite.green(value)
    val blue: Int get() = Composite.blue(value)
}

/** HSV color for the wheel and tolerant mixer assertions. */
data class HsvColor(val h: Float, val s: Float, val v: Float) {

    fun toArgb(): Int {
        val hue = ((h % FULL_HUE) + FULL_HUE) % FULL_HUE
        val saturation = sanitizeUnit(s)
        val value = sanitizeUnit(v)
        val chroma = value * saturation
        val section = hue / HUE_SECTION
        val secondary = chroma * (1f - kotlin.math.abs(section % 2f - 1f))
        val offset = value - chroma
        val (red, green, blue) = when (section.toInt()) {
            0 -> Triple(chroma, secondary, 0f)
            1 -> Triple(secondary, chroma, 0f)
            2 -> Triple(0f, chroma, secondary)
            3 -> Triple(0f, secondary, chroma)
            4 -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }

        return Composite.argb(
            CHANNEL_MAX,
            ((red + offset) * CHANNEL_MAX).roundToInt(),
            ((green + offset) * CHANNEL_MAX).roundToInt(),
            ((blue + offset) * CHANNEL_MAX).roundToInt(),
        )
    }

    companion object {
        fun fromArgb(argb: Int): HsvColor {
            val red = Composite.red(argb) / CHANNEL_MAX_F
            val green = Composite.green(argb) / CHANNEL_MAX_F
            val blue = Composite.blue(argb) / CHANNEL_MAX_F
            val max = maxOf(red, green, blue)
            val min = minOf(red, green, blue)
            val delta = max - min
            val hue = when {
                delta == 0f -> 0f
                max == red -> HUE_SECTION * (((green - blue) / delta) % 6f)
                max == green -> HUE_SECTION * ((blue - red) / delta + 2f)
                else -> HUE_SECTION * ((red - green) / delta + 4f)
            }
            val wrappedHue = if (hue < 0f) hue + FULL_HUE else hue
            val saturation = if (max == 0f) 0f else delta / max

            return HsvColor(wrappedHue, saturation, max)
        }

        private fun sanitizeUnit(value: Float): Float =
            if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

        private const val CHANNEL_MAX = 255
        private const val CHANNEL_MAX_F = CHANNEL_MAX.toFloat()
        private const val HUE_SECTION = 60f
        private const val FULL_HUE = 360f
    }
}
