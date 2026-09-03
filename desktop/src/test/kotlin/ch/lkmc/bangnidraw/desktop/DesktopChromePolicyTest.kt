package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.PaintSlotAssignments
import ch.lkmc.bangnidraw.engine.core.RailMode
import ch.lkmc.bangnidraw.engine.core.RailSlotPolicy
import ch.lkmc.bangnidraw.engine.core.SliderPlacement
import ch.lkmc.bangnidraw.engine.core.WidthClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopChromePolicyTest {

    private val presets = DesktopBrushes.loadAll()

    @Test
    fun `the window's layout is the shared adaptive table, minus the top strip`() {
        val layout = DesktopChromeLayout.forWindow(widthDp = 1280, heightDp = 900)

        assertEquals(
            LayoutSpec.forWindow(
                width = WidthClass.EXPANDED,
                heightDp = 900 - LayoutSpec.TOP_STRIP_DP,
                hand = Hand.RIGHT,
            ),
            layout,
        )
    }

    @Test
    fun `the minimum window gets a short rail with a slider ledge`() {
        // WINDOW_MIN_W x WINDOW_MIN_H from the shell.
        val layout = DesktopChromeLayout.forWindow(widthDp = 640, heightDp = 480)

        assertEquals(RailMode.SHORT, layout.railMode)
        assertEquals(SliderPlacement.LEDGE, layout.sliderPlacement)
        assertEquals(0, layout.sliderLengthDp)
        assertTrue(layout.railWidthDp > 0)
    }

    @Test
    fun `a comfortable window keeps the sliders inside the rail`() {
        val layout = DesktopChromeLayout.forWindow(widthDp = 960, heightDp = 600)

        assertEquals(RailMode.GROUPED, layout.railMode)
        assertEquals(SliderPlacement.IN_RAIL, layout.sliderPlacement)
        assertTrue(layout.sliderLengthDp > 0)
    }

    @Test
    fun `a tall window promotes the rail and lengthens its sliders`() {
        val short = DesktopChromeLayout.forWindow(widthDp = 1280, heightDp = 700)
        val tall = DesktopChromeLayout.forWindow(widthDp = 1280, heightDp = 1000)

        assertEquals(RailMode.GROUPED, short.railMode)
        assertEquals(RailMode.FULL, tall.railMode)
        assertTrue(tall.sliderLengthDp > short.sliderLengthDp)
    }

    @Test
    fun `a narrow window docks the rail and moves the sliders to the ledge`() {
        val layout = DesktopChromeLayout.forWindow(widthDp = 500, heightDp = 800)

        assertEquals(RailMode.DOCK, layout.railMode)
        assertEquals(SliderPlacement.LEDGE, layout.sliderPlacement)
        // The ledge only draws when the rail carries no sliders; the shell
        // keys that on the length, so DOCK must report zero.
        assertEquals(0, layout.sliderLengthDp)
    }

    @Test
    fun `a degenerate window size never throws`() {
        val layout = DesktopChromeLayout.forWindow(widthDp = 0, heightDp = 0)

        assertEquals(RailMode.DOCK, layout.railMode)
    }

    @Test
    fun `the rail opens on the ink pen with the hard eraser in its slot`() {
        val rail = DesktopRailPolicy.initial(presets)

        assertEquals(BrushPresets.INK_PEN_ID, rail.selectedId)
        assertEquals(BrushPresets.HARD_ERASER_ID, rail.eraserId)
        assertTrue(rail.paintSelected())
    }

    @Test
    fun `selecting a paint leaves the eraser slot alone`() {
        val rail = DesktopRailPolicy.select(
            DesktopRailPolicy.initial(presets),
            BrushPresets.MARKER_ID,
            presets,
        )

        assertEquals(BrushPresets.MARKER_ID, rail.selectedId)
        assertEquals(BrushPresets.HARD_ERASER_ID, rail.eraserId)
    }

    @Test
    fun `restoring a stored eraser fills the eraser slot with it`() {
        val rail = DesktopRailPolicy.select(
            DesktopRailPolicy.initial(presets),
            BrushPresets.SOFT_ERASER_ID,
            presets,
        )

        assertEquals(BrushPresets.SOFT_ERASER_ID, rail.selectedId)
        assertEquals(BrushPresets.SOFT_ERASER_ID, rail.eraserId)
        assertFalse(rail.paintSelected())
    }

    @Test
    fun `an unknown stored preset id changes nothing`() {
        val initial = DesktopRailPolicy.initial(presets)

        assertSame(initial, DesktopRailPolicy.select(initial, "builtin.not_shipped", presets))
    }

    @Test
    fun `the eraser slot selects first and only then cycles erasers`() {
        val paint = DesktopRailPolicy.initial(presets)

        val selected = DesktopRailPolicy.eraserTap(paint, presets)
        assertEquals(BrushPresets.HARD_ERASER_ID, selected.selectedId)
        assertEquals(BrushPresets.HARD_ERASER_ID, selected.eraserId)

        val toggled = DesktopRailPolicy.eraserTap(selected, presets)
        assertEquals(BrushPresets.SOFT_ERASER_ID, toggled.selectedId)
        assertEquals(BrushPresets.SOFT_ERASER_ID, toggled.eraserId)

        // Cycling returns; the slot never lands on a preset that is not an eraser.
        val cycled = DesktopRailPolicy.eraserTap(toggled, presets)
        assertEquals(BrushPresets.HARD_ERASER_ID, cycled.selectedId)
        assertTrue(presets.first { it.id == cycled.eraserId }.eraseMode)
    }

    @Test
    fun `a single-eraser catalogue has nothing to toggle to`() {
        val single = presets.filterNot { it.id == BrushPresets.SOFT_ERASER_ID }
        val selected = DesktopRailPolicy.eraserTap(DesktopRailPolicy.initial(single), single)

        assertSame(selected, DesktopRailPolicy.eraserTap(selected, single))
    }

    @Test
    fun `the active preset is one derivation, shared by the rail and the ledge`() {
        val rail = DesktopRailPolicy.initial(presets)

        assertEquals(BrushPresets.INK_PEN_ID, DesktopRailPolicy.activePreset(presets, rail)?.id)

        val erasing = DesktopRailPolicy.eraserTap(rail, presets)
        assertEquals(erasing.selectedId, DesktopRailPolicy.activePreset(presets, erasing)?.id)

        // A selection the catalogue no longer ships falls back to a preset
        // that exists, rather than leaving the sliders tuning nothing.
        val stale = DesktopRailState(selectedId = "builtin.not_shipped", eraserId = rail.eraserId)
        assertNotNull(DesktopRailPolicy.activePreset(presets, stale))
        assertNull(DesktopRailPolicy.activePreset(emptyList(), rail))
    }

    @Test
    fun `the paint budget keeps the rail inside the window it was measured for`() {
        val paints = DesktopRailPolicy.paints(presets).size

        for (height in listOf(600, 700, 800, 900, 1000, 1200)) {
            val layout = DesktopChromeLayout.forWindow(widthDp = 1280, heightDp = height)
            val available = height - LayoutSpec.TOP_STRIP_DP
            val budget = DesktopRailPolicy.paintBudget(layout, available, paints)
            val overflows = budget < paints
            val slots = budget + (if (overflows) 1 else 0) + 1 // overflow, eraser

            val used = slots * layout.toolSlotDp + (slots - 1) * TOOL_GAP_DP +
                DIVIDER_HEIGHT_DP + layout.sliderLengthDp + RAIL_PADDING_DP

            assertTrue(budget in 1..paints, "budget $budget out of range at ${height}dp")
            assertTrue(used <= available, "rail needs ${used}dp of ${available}dp at ${height}dp")
        }
    }

    @Test
    fun `a tall enough rail shows every paint with no overflow slot`() {
        val paints = DesktopRailPolicy.paints(presets).size
        val layout = DesktopChromeLayout.forWindow(widthDp = 1280, heightDp = 1200)

        assertEquals(paints, DesktopRailPolicy.paintBudget(layout, 1200 - LayoutSpec.TOP_STRIP_DP, paints))
    }

    @Test
    fun `a rail with room for nothing still offers one paint`() {
        val layout = DesktopChromeLayout.forWindow(widthDp = 1280, heightDp = 900)

        assertEquals(1, DesktopRailPolicy.paintBudget(layout, availableDp = 0, paintCount = 5))
        assertEquals(0, DesktopRailPolicy.paintBudget(layout, availableDp = 900, paintCount = 0))
    }

    @Test
    fun `the active paint stays visible when it sits past the budget`() {
        val paints = DesktopRailPolicy.paints(presets).map(BrushPreset::id)
        val assignments = PaintSlotAssignments.restore(paints).activate(paints.lastIndex)

        val visible = RailSlotPolicy.visibleIndices(assignments, budget = 3)

        assertEquals(3, visible.size)
        assertTrue(paints.lastIndex in visible)
    }

    @Test
    fun `the rail lists every shipped paint and no eraser among them`() {
        val paints = DesktopRailPolicy.paints(presets)

        assertEquals(presets.count { !it.eraseMode }, paints.size)
        assertTrue(paints.none(BrushPreset::eraseMode))
        // Same order the Android rail uses, so the two look alike.
        assertEquals(BrushPresets.paintRailOrder(presets).map(BrushPreset::id), paints.map(BrushPreset::id))
        assertNotNull(DesktopRailPolicy.eraserOrNull(presets))
    }

    @Test
    fun `a catalogue with no eraser still yields a selectable paint`() {
        val paintsOnly = presets.filterNot(BrushPreset::eraseMode)
        val rail = DesktopRailPolicy.initial(paintsOnly)

        assertEquals(BrushPresets.INK_PEN_ID, rail.selectedId)
        assertTrue(rail.paintSelected())
        assertSame(rail, DesktopRailPolicy.eraserTap(rail, paintsOnly))
    }

    private companion object {
        // DesktopRailPolicy's own spacing, restated so a change to either
        // side has to be a deliberate one.
        const val TOOL_GAP_DP = 4
        const val DIVIDER_HEIGHT_DP = 9
        const val RAIL_PADDING_DP = 24
    }
}
