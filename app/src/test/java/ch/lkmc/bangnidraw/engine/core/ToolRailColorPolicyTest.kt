package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRailColorPolicyTest {

    // contrastRatio() comes from the shared WCAG helper in this package's
    // test sources (ColorContrast.kt); keep new contrast tests on it.
    @Test
    fun `rail icons meet non text contrast in every theme`() {
        for (theme in AppTheme.entries) {
            for (emphasis in ToolButtonEmphasis.entries) {
                val colors = ToolRailColorPolicy.colors(theme, emphasis)
                val contrast = contrastRatio(colors.iconArgb, colors.containerArgb)

                assertEquals(OPAQUE_ALPHA, colors.iconArgb ushr ALPHA_SHIFT)
                assertEquals(OPAQUE_ALPHA, colors.containerArgb ushr ALPHA_SHIFT)
                assertTrue(
                    contrast >= MIN_ICON_CONTRAST,
                    "$theme $emphasis icon contrast is $contrast:1",
                )
            }
        }
    }

    private companion object {
        const val MIN_ICON_CONTRAST = 3.0
        const val ALPHA_SHIFT = 24
        const val OPAQUE_ALPHA = 0xFF
    }
}
