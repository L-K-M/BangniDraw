package ch.lkmc.bangnidraw.engine.core

import kotlin.math.pow

fun contrastRatio(firstArgb: Int, secondArgb: Int): Double {
    val first = relativeLuminance(Argb(firstArgb))
    val second = relativeLuminance(Argb(secondArgb))
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)

    return (lighter + WCAG_LUMINANCE_OFFSET) / (darker + WCAG_LUMINANCE_OFFSET)
}

private fun relativeLuminance(color: Argb): Double =
    RED_WEIGHT * linearChannel(color.red) +
        GREEN_WEIGHT * linearChannel(color.green) +
        BLUE_WEIGHT * linearChannel(color.blue)

private fun linearChannel(channel: Int): Double {
    val srgb = channel / CHANNEL_MAX
    if (srgb <= SRGB_LINEAR_LIMIT) return srgb / SRGB_LINEAR_DIVISOR

    return ((srgb + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_GAMMA)
}

private const val WCAG_LUMINANCE_OFFSET = 0.05
private const val RED_WEIGHT = 0.2126
private const val GREEN_WEIGHT = 0.7152
private const val BLUE_WEIGHT = 0.0722
private const val CHANNEL_MAX = 255.0
private const val SRGB_LINEAR_LIMIT = 0.04045
private const val SRGB_LINEAR_DIVISOR = 12.92
private const val SRGB_OFFSET = 0.055
private const val SRGB_SCALE = 1.055
private const val SRGB_GAMMA = 2.4
