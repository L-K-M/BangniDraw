package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PanelInteractiveBoundsTest {

    @Test
    fun `side panel controls clear the live rail`() {
        val widthDp = 800
        val heightDp = 600

        for (hand in Hand.entries) {
            val spec = LayoutSpec.forWindow(
                WidthClass.MEDIUM,
                heightDp - LayoutSpec.TOP_STRIP_DP,
                hand,
            )
            assertEquals(PanelMode.SIDE_SHEET, spec.panelMode)

            val panel = spec.panelInteractiveBounds(widthDp, heightDp)
            val rail = spec.persistentChrome(widthDp, heightDp)[1]

            assertFalse(panel.intersects(rail), "$hand panel $panel intersects rail $rail")
        }
    }

    @Test
    fun `compact panel controls clear the live dock`() {
        val widthDp = 360
        val heightDp = 800
        val spec = LayoutSpec.forWindow(
            WidthClass.COMPACT,
            heightDp - LayoutSpec.TOP_STRIP_DP,
            Hand.RIGHT,
        )
        assertEquals(PanelMode.FULL_HEIGHT_SHEET, spec.panelMode)

        val panel = spec.panelInteractiveBounds(widthDp, heightDp)
        val dock = spec.persistentChrome(widthDp, heightDp)[1]

        assertFalse(panel.intersects(dock), "panel $panel intersects dock $dock")
    }

    @Test
    fun `short side panel controls clear the slider ledge`() {
        val widthDp = 800
        val heightDp = 400
        val spec = LayoutSpec.forWindow(
            WidthClass.MEDIUM,
            heightDp - LayoutSpec.TOP_STRIP_DP,
            Hand.RIGHT,
        )
        assertEquals(RailMode.SHORT, spec.railMode)

        val panel = spec.panelInteractiveBounds(widthDp, heightDp)
        val ledge = spec.persistentChrome(widthDp, heightDp).last()

        assertFalse(panel.intersects(ledge), "panel $panel intersects ledge $ledge")
    }

    @Test
    fun `floating panel controls clear the top strip`() {
        val widthDp = 1_200
        val heightDp = 846
        val spec = LayoutSpec.forWindow(
            WidthClass.EXPANDED,
            heightDp - LayoutSpec.TOP_STRIP_DP,
            Hand.RIGHT,
        )
        assertEquals(PanelMode.FLOATING, spec.panelMode)

        val panel = spec.panelInteractiveBounds(widthDp, heightDp)
        val topStrip = spec.persistentChrome(widthDp, heightDp).first()

        assertFalse(panel.intersects(topStrip), "panel $panel intersects strip $topStrip")
    }
}
