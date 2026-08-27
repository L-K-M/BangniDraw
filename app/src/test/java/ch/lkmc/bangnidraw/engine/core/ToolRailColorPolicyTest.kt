package ch.lkmc.bangnidraw.engine.core

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRailColorPolicyTest {

    @Test
    fun `dark rail icons meet non text contrast`() {
        for (emphasis in ToolButtonEmphasis.entries) {
            val colors = ToolRailColorPolicy.colors(ThemeTone.DARK, emphasis)
            val contrast = contrastRatio(colors.iconArgb, colors.containerArgb)

            assertEquals(OPAQUE_ALPHA, colors.iconArgb ushr ALPHA_SHIFT)
            assertEquals(OPAQUE_ALPHA, colors.containerArgb ushr ALPHA_SHIFT)
            assertTrue(
                contrast >= MIN_ICON_CONTRAST,
                "$emphasis icon contrast is $contrast:1",
            )
        }
    }

    private fun contrastRatio(firstArgb: Int, secondArgb: Int): Double {
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

    private companion object {
        const val MIN_ICON_CONTRAST = 3.0
        const val WCAG_LUMINANCE_OFFSET = 0.05
        const val RED_WEIGHT = 0.2126
        const val GREEN_WEIGHT = 0.7152
        const val BLUE_WEIGHT = 0.0722
        const val CHANNEL_MAX = 255.0
        const val SRGB_LINEAR_LIMIT = 0.04045
        const val SRGB_LINEAR_DIVISOR = 12.92
        const val SRGB_OFFSET = 0.055
        const val SRGB_SCALE = 1.055
        const val SRGB_GAMMA = 2.4
        const val ALPHA_SHIFT = 24
        const val OPAQUE_ALPHA = 0xFF
    }
}
