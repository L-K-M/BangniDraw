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
    fun `the eraser slot selects, and a second click leaves it alone`() {
        val paint = DesktopRailPolicy.initial(presets)

        val selected = DesktopRailPolicy.eraserTap(paint, presets)
        assertEquals(BrushPresets.HARD_ERASER_ID, selected.selectedId)
        assertEquals(BrushPresets.HARD_ERASER_ID, selected.eraserId)
        assertTrue(presets.first { it.id == selected.eraserId }.eraseMode)

        // The second click is the settings door for every slot, so the policy
        // must not also spend it on the other eraser.
        assertSame(selected, DesktopRailPolicy.eraserTap(selected, presets))
    }

    @Test
    fun `the other eraser is reached by selecting it, as the settings panel does`() {
        val selected = DesktopRailPolicy.eraserTap(DesktopRailPolicy.initial(presets), presets)

        val soft = DesktopRailPolicy.select(selected, BrushPresets.SOFT_ERASER_ID, presets)

        assertEquals(BrushPresets.SOFT_ERASER_ID, soft.selectedId)
        assertEquals(BrushPresets.SOFT_ERASER_ID, soft.eraserId)
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
            val secondary = DesktopRailPolicy.secondarySlotCount(layout) +
                if (DesktopRailPolicy.hiddenSecondary(layout).isEmpty()) 0 else 1
            val slots = budget + (if (overflows) 1 else 0) + 1 + secondary

            val used = slots * layout.toolSlotDp + (slots - 1) * TOOL_GAP_DP +
                DIVIDER_HEIGHT_DP * 2 + layout.sliderLengthDp + RAIL_PADDING_DP

            assertTrue(budget in 1..paints, "budget $budget out of range at ${height}dp")
            // The budget floors at one paint — a rail with no paint at all
            // could not draw — so the only permitted overrun is that floor,
            // which the rail's own scroll absorbs.
            assertTrue(
                used <= available || budget == 1,
                "rail needs ${used}dp of ${available}dp at ${height}dp",
            )
        }
    }

    @Test
    fun `a tall enough rail shows every paint with no overflow slot`() {
        val paints = DesktopRailPolicy.paints(presets).size
        // Tall enough for every paint *and* the eraser and five secondary
        // tools the FULL rail also carries.
        val heightDp = 2000
        val layout = DesktopChromeLayout.forWindow(widthDp = 1280, heightDp = heightDp)

        assertEquals(
            paints,
            DesktopRailPolicy.paintBudget(layout, heightDp - LayoutSpec.TOP_STRIP_DP, paints),
        )
    }

    @Test
    fun `the FULL rail gives every secondary tool a slot, shorter modes group them`() {
        val full = DesktopChromeLayout.forWindow(widthDp = 1280, heightDp = 1000)
        val grouped = DesktopChromeLayout.forWindow(widthDp = 960, heightDp = 600)

        assertEquals(RailMode.FULL, full.railMode)
        assertEquals(DesktopSecondaryTool.entries, DesktopRailPolicy.visibleSecondary(full))
        assertTrue(DesktopRailPolicy.hiddenSecondary(full).isEmpty())

        // Same three `:app`'s grouped rail keeps, and the same two behind its
        // menu — so a tool is in the same place on both products.
        assertEquals(
            listOf(
                DesktopSecondaryTool.SMUDGE,
                DesktopSecondaryTool.WATER,
                DesktopSecondaryTool.FILL,
            ),
            DesktopRailPolicy.visibleSecondary(grouped),
        )
        assertEquals(
            listOf(DesktopSecondaryTool.BLUR, DesktopSecondaryTool.EYEDROPPER),
            DesktopRailPolicy.hiddenSecondary(grouped),
        )
    }

    @Test
    fun `selecting a secondary tool leaves the remembered brush alone`() {
        val rail = DesktopRailPolicy.initial(presets)

        val smudging = DesktopRailPolicy.selectSecondary(rail, DesktopSecondaryTool.SMUDGE)

        assertEquals(DesktopSecondaryTool.SMUDGE, smudging.secondary)
        assertEquals(rail.selectedId, smudging.selectedId)
        assertFalse(smudging.brushSelected())
        assertFalse(smudging.paintSelected())
        // Re-selecting the same tool is not a change, so the rail's second
        // click can be the settings door.
        assertSame(smudging, DesktopRailPolicy.selectSecondary(smudging, DesktopSecondaryTool.SMUDGE))

        // Any brush click puts the rail back on brushes: one lit slot.
        val painting = DesktopRailPolicy.select(smudging, BrushPresets.MARKER_ID, presets)
        assertNull(painting.secondary)
        assertTrue(painting.paintSelected())
        assertNull(DesktopRailPolicy.eraserTap(smudging, presets).secondary)
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
        // The rail's own spacing: the test proves the budget's arithmetic
        // fits the rail, not that the numbers have particular values, so it
        // reads the same source both of them do.
        const val TOOL_GAP_DP = DesktopRailGeometry.TOOL_GAP_DP
        const val DIVIDER_HEIGHT_DP = DesktopRailGeometry.DIVIDER_HEIGHT_DP
        const val RAIL_PADDING_DP = DesktopRailGeometry.RAIL_PADDING_DP
    }
}
