package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutSpecTest {

    @Test
    fun `width boundaries match Android window size classes`() {
        assertEquals(WidthClass.COMPACT, WidthClass.forWidth(599))
        assertEquals(WidthClass.MEDIUM, WidthClass.forWidth(600))
        assertEquals(WidthClass.MEDIUM, WidthClass.forWidth(839))
        assertEquals(WidthClass.EXPANDED, WidthClass.forWidth(840))
    }

    @Test
    fun `compact windows always use the dock`() {
        for (height in listOf(200, 288, 900)) {
            val spec = LayoutSpec.forWindow(WidthClass.COMPACT, height, Hand.RIGHT)

            assertEquals(RailMode.DOCK, spec.railMode)
            assertEquals(PanelMode.FULL_HEIGHT_SHEET, spec.panelMode)
            assertEquals(SliderPlacement.LEDGE, spec.sliderPlacement)
            assertEquals(LayoutSpec.COMPACT_GRID_CELL_DP, spec.gridMinCellDp)
        }
    }

    @Test
    fun `medium rail modes change at their exact budgets`() {
        assertMode(WidthClass.MEDIUM, 287, RailMode.DOCK)
        assertMode(WidthClass.MEDIUM, 288, RailMode.SHORT)
        assertMode(WidthClass.MEDIUM, 460, RailMode.SHORT)
        assertMode(WidthClass.MEDIUM, 461, RailMode.GROUPED)
        assertMode(WidthClass.MEDIUM, 717, RailMode.GROUPED)
        assertMode(WidthClass.MEDIUM, 718, RailMode.FULL)
    }

    @Test
    fun `expanded rail modes change at their exact budgets`() {
        assertMode(WidthClass.EXPANDED, 287, RailMode.DOCK)
        assertMode(WidthClass.EXPANDED, 288, RailMode.SHORT)
        assertMode(WidthClass.EXPANDED, 508, RailMode.SHORT)
        assertMode(WidthClass.EXPANDED, 509, RailMode.GROUPED)
        assertMode(WidthClass.EXPANDED, 797, RailMode.GROUPED)
        assertMode(WidthClass.EXPANDED, 798, RailMode.FULL)
    }

    @Test
    fun `each rail content height equals its documented sum`() {
        val mediumGrouped = LayoutSpec.forWindow(WidthClass.MEDIUM, 461, Hand.RIGHT)
        val expandedGrouped = LayoutSpec.forWindow(WidthClass.EXPANDED, 509, Hand.RIGHT)
        val mediumFull = LayoutSpec.forWindow(WidthClass.MEDIUM, 718, Hand.RIGHT)
        val expandedFull = LayoutSpec.forWindow(WidthClass.EXPANDED, 798, Hand.RIGHT)

        assertEquals(461, mediumGrouped.railContentHeightDp)
        assertEquals(509, expandedGrouped.railContentHeightDp)
        assertEquals(718, mediumFull.railContentHeightDp)
        assertEquals(798, expandedFull.railContentHeightDp)
        assertEquals(288, LayoutSpec.shortContentHeightDp())
    }

    @Test
    fun `every tool target stays at least 48 dp`() {
        for (width in WidthClass.entries) {
            for (height in listOf(200, 288, 461, 509, 718, 798)) {
                val spec = LayoutSpec.forWindow(width, height, Hand.RIGHT)

                assertTrue(spec.toolSlotDp >= LayoutSpec.MIN_TARGET_DP)
            }
        }
    }

    @Test
    fun `in rail sliders have separate touch slabs`() {
        val windows = listOf(
            Window(WidthClass.MEDIUM, 600, 509),
            Window(WidthClass.MEDIUM, 600, 766),
            Window(WidthClass.EXPANDED, 840, 557),
            Window(WidthClass.EXPANDED, 840, 846),
        )

        for (window in windows) {
            val railHeight = window.height - LayoutSpec.TOP_STRIP_DP
            val spec = LayoutSpec.forWindow(window.widthClass, railHeight, Hand.RIGHT)
            val rail = spec.persistentChrome(window.width, window.height).last()
            val railWidth = rail.right - rail.left

            assertEquals(SliderPlacement.IN_RAIL, spec.sliderPlacement)
            assertTrue(
                railWidth >= MIN_SLIDER_RAIL_WIDTH_DP,
                "$railWidth dp cannot hold both slider touch slabs",
            )
        }
    }

    @Test
    fun `compact dock fits six targets on a 320 dp window`() {
        val spec = LayoutSpec.forWindow(WidthClass.COMPACT, 480, Hand.RIGHT)

        assertEquals(LayoutSpec.MIN_TARGET_DP, spec.toolSlotDp)
        assertTrue(spec.toolSlotDp * 6 <= 320)
    }

    @Test
    fun `persistent chrome leaves the central 60 percent clear`() {
        val windows = listOf(
            Window(WidthClass.COMPACT, 360, 800),
            Window(WidthClass.MEDIUM, 800, 480),
            Window(WidthClass.MEDIUM, 700, 700),
            Window(WidthClass.EXPANDED, 1200, 900),
        )

        for (window in windows) {
            val railHeight = window.height - LayoutSpec.TOP_STRIP_DP
            val spec = LayoutSpec.forWindow(window.widthClass, railHeight, Hand.RIGHT)
            val clear = LayoutRect.centralClearZone(window.width, window.height)

            for (chrome in spec.persistentChrome(window.width, window.height)) {
                assertFalse(
                    chrome.intersects(clear),
                    "$chrome enters $clear for $window and ${spec.railMode}",
                )
            }
        }
    }

    @Test
    fun `left handed chrome mirrors right handed chrome`() {
        val width = 1200
        val height = 900
        val right = LayoutSpec.forWindow(WidthClass.EXPANDED, 852, Hand.RIGHT)
        val left = LayoutSpec.forWindow(WidthClass.EXPANDED, 852, Hand.LEFT)

        assertEquals(Hand.RIGHT, right.railSide)
        assertEquals(Hand.LEFT, left.railSide)
        assertEquals(Hand.RIGHT, right.panelSide)
        assertEquals(Hand.LEFT, left.panelSide)
        assertEquals(
            right.persistentChrome(width, height).map { it.mirrorX(width) },
            left.persistentChrome(width, height),
        )
    }

    @Test
    fun `panel lane clears persistent chrome in every rail mode`() {
        val windows = listOf(
            Window(WidthClass.COMPACT, 360, 800),
            Window(WidthClass.MEDIUM, 700, 400),
            Window(WidthClass.MEDIUM, 700, 600),
            Window(WidthClass.EXPANDED, 1200, 900),
        )

        for (window in windows) {
            for (hand in Hand.entries) {
                val railHeight = window.height - LayoutSpec.TOP_STRIP_DP
                val spec = LayoutSpec.forWindow(window.widthClass, railHeight, hand)
                val insets = spec.panelInsets(window.width, window.height)
                val panel = panelRect(spec, window, insets)

                for (chrome in spec.persistentChrome(window.width, window.height)) {
                    assertFalse(
                        panel.intersects(chrome),
                        "$panel intersects $chrome for $window, $hand, and ${spec.railMode}",
                    )
                }
            }
        }
    }

    @Test
    fun `panel insets reserve the dock ledge and short ledge`() {
        val dock = LayoutSpec.forWindow(WidthClass.COMPACT, 752, Hand.RIGHT)
            .panelInsets(windowWidthDp = 360, windowHeightDp = 800)
        val shortSpec = LayoutSpec.forWindow(WidthClass.MEDIUM, 352, Hand.RIGHT)
        val short = shortSpec.panelInsets(windowWidthDp = 700, windowHeightDp = 400)

        assertEquals(LayoutSpec.TOP_STRIP_DP, dock.topDp)
        assertEquals(DOCK_AND_LEDGE_DP, dock.bottomDp)
        assertEquals(SHORT_LEDGE_DP, short.bottomDp)
        assertEquals(shortSpec.railWidthDp, short.rightDp)
        assertEquals(SHORT_RAIL_WIDTH_DP, shortSpec.railWidthDp)
    }

    private fun panelRect(
        spec: LayoutSpec,
        window: Window,
        insets: PanelInsets,
    ): LayoutRect {
        val availableWidth = window.width - insets.leftDp - insets.rightDp
        val width = minOf(PANEL_TEST_WIDTH_DP, availableWidth).toFloat()
        val left = if (spec.panelSide == Hand.LEFT) {
            insets.leftDp.toFloat()
        } else {
            window.width - insets.rightDp - width
        }
        return LayoutRect(
            left = left,
            top = insets.topDp.toFloat(),
            right = left + width,
            bottom = (window.height - insets.bottomDp).toFloat(),
        )
    }

    private fun assertMode(width: WidthClass, height: Int, expected: RailMode) {
        assertEquals(expected, LayoutSpec.forWindow(width, height, Hand.RIGHT).railMode)
    }

    private data class Window(
        val widthClass: WidthClass,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val SLIDER_COUNT = 2
        const val SLIDER_TOUCH_SLAB_DP = 48
        const val RAIL_HORIZONTAL_PADDING_DP = 4
        const val MIN_SLIDER_RAIL_WIDTH_DP =
            SLIDER_COUNT * SLIDER_TOUCH_SLAB_DP + 2 * RAIL_HORIZONTAL_PADDING_DP
        const val PANEL_TEST_WIDTH_DP = 300
        const val DOCK_AND_LEDGE_DP = 112
        const val SHORT_LEDGE_DP = 48
        const val SHORT_RAIL_WIDTH_DP = 56
    }
}
