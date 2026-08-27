package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RgbFieldLayoutTest {

    @Test
    fun `compact panels divide RGB fields evenly without overflow`() {
        val expectedWidths = listOf(
            300f to 82.666_664f,
            320f to 89.333_336f,
        )

        for ((panelWidth, expectedFieldWidth) in expectedWidths) {
            val contentWidth = panelWidth - 2 * PANEL_HORIZONTAL_PADDING_DP
            val layout = RgbFieldLayoutPolicy.forContentWidth(
                contentWidthDp = contentWidth,
                fontScale = 1f,
            )

            assertEquals(RgbFieldArrangement.ROW, layout.arrangement)
            assertEquals(expectedFieldWidth, layout.fieldWidthDp, FLOAT_TOLERANCE)
            assertTrue(
                layout.occupiedWidthDp <= contentWidth + FLOAT_TOLERANCE,
                "$panelWidth dp panel uses ${layout.occupiedWidthDp} dp",
            )
        }
    }

    @Test
    fun `two hundred percent text stacks full width RGB fields`() {
        for (panelWidth in listOf(300f, 320f)) {
            val contentWidth = panelWidth - 2 * PANEL_HORIZONTAL_PADDING_DP
            val layout = RgbFieldLayoutPolicy.forContentWidth(
                contentWidthDp = contentWidth,
                fontScale = 2f,
            )

            assertEquals(RgbFieldArrangement.COLUMN, layout.arrangement)
            assertEquals(contentWidth, layout.fieldWidthDp, FLOAT_TOLERANCE)
        }
    }

    @Test
    fun `row survives at its exact field width floor`() {
        val contentWidth = 3 * BASE_FIELD_WIDTH_DP + 2 * FIELD_GAP_DP

        val layout = RgbFieldLayoutPolicy.forContentWidth(contentWidth, fontScale = 1f)

        assertEquals(RgbFieldArrangement.ROW, layout.arrangement)
        assertEquals(BASE_FIELD_WIDTH_DP, layout.fieldWidthDp, FLOAT_TOLERANCE)
    }

    @Test
    fun `width below the row floor stacks fields`() {
        val contentWidth = 3 * BASE_FIELD_WIDTH_DP + 2 * FIELD_GAP_DP - 0.5f

        val layout = RgbFieldLayoutPolicy.forContentWidth(contentWidth, fontScale = 1f)

        assertEquals(RgbFieldArrangement.COLUMN, layout.arrangement)
        assertEquals(contentWidth, layout.fieldWidthDp, FLOAT_TOLERANCE)
    }

    @Test
    fun `content narrower than the gaps stacks full width fields`() {
        val contentWidth = 5f

        val layout = RgbFieldLayoutPolicy.forContentWidth(contentWidth, fontScale = 1f)

        assertEquals(RgbFieldArrangement.COLUMN, layout.arrangement)
        assertEquals(contentWidth, layout.fieldWidthDp, FLOAT_TOLERANCE)
    }

    @Test
    fun `unbounded content keeps a finite readable row`() {
        val layout = RgbFieldLayoutPolicy.forContentWidth(
            contentWidthDp = Float.POSITIVE_INFINITY,
            fontScale = 2f,
        )

        assertEquals(RgbFieldArrangement.ROW, layout.arrangement)
        assertEquals(2 * BASE_FIELD_WIDTH_DP, layout.fieldWidthDp, FLOAT_TOLERANCE)
        assertTrue(layout.occupiedWidthDp.isFinite())
    }

    private companion object {
        // ColorPanel reserves 20 dp on each horizontal edge.
        const val PANEL_HORIZONTAL_PADDING_DP = 20f
        const val BASE_FIELD_WIDTH_DP = 64f
        const val FIELD_GAP_DP = 6f
        const val FLOAT_TOLERANCE = 0.001f
    }
}
