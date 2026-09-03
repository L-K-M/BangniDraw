package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.EraserTogglePolicy
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.RailMode
import ch.lkmc.bangnidraw.engine.core.WidthClass

/**
 * The desktop window's share of the Android adaptive-layout table
 * (`docs/plan/08-ui-and-layout.md` §1). The shell supplies a window size and
 * a hand; every rail/strip/panel dimension comes back from the one shared
 * [LayoutSpec], so the two products cannot drift on geometry.
 */
internal object DesktopChromeLayout {

    fun forWindow(widthDp: Int, heightDp: Int, hand: Hand = Hand.RIGHT): LayoutSpec =
        LayoutSpec.forWindow(
            width = WidthClass.forWidth(widthDp.coerceAtLeast(0)),
            // LayoutSpec's height is the rail's own space: the window minus
            // the top strip. Desktop has no status bar to subtract as well.
            heightDp = (heightDp - LayoutSpec.TOP_STRIP_DP).coerceAtLeast(0),
            hand = hand,
        )
}

/**
 * Which preset each rail slot selects, and what a tap does to that choice.
 *
 * The Android rail carries five secondary tools (smudge, water, blur, fill,
 * eyedropper) this shell has no engine path for, so its own budget
 * ([DesktopRailPolicy.paintBudget]) counts the slots this rail actually
 * shows: the paints that fit, the overflow menu, and one eraser.
 */
internal data class DesktopRailState(
    /** The preset the next stroke uses; a paint id or [eraserId]. */
    val selectedId: String,
    /** Which of the shipped erasers the single eraser slot currently offers. */
    val eraserId: String,
) {
    fun paintSelected(): Boolean = selectedId != eraserId
}

internal object DesktopRailPolicy {

    fun initial(presets: List<BrushPreset>): DesktopRailState {
        val paint = BrushPresets.paintRailOrder(presets)
            .firstOrNull { it.id == BrushPresets.INK_PEN_ID }
            ?: BrushPresets.paintRailOrder(presets).firstOrNull()
        val eraser = eraserOrNull(presets)

        return DesktopRailState(
            selectedId = paint?.id ?: eraser?.id ?: BrushPresets.INK_PEN_ID,
            eraserId = eraser?.id ?: BrushPresets.HARD_ERASER_ID,
        )
    }

    /** A restored preference selects that preset; an unknown id changes nothing. */
    fun select(state: DesktopRailState, presetId: String, presets: List<BrushPreset>): DesktopRailState {
        val preset = presets.firstOrNull { it.id == presetId } ?: return state
        if (!preset.eraseMode) return state.copy(selectedId = preset.id)

        return state.copy(selectedId = preset.id, eraserId = preset.id)
    }

    /**
     * The eraser slot's tap. Android's rail reaches the second eraser through
     * a long-press; a long-press is not a mouse gesture, so tapping the slot
     * that is *already* active cycles to the next eraser instead — the same
     * "second tap does the other thing" rule the paint slots use on Android.
     */
    fun eraserTap(state: DesktopRailState, presets: List<BrushPreset>): DesktopRailState {
        // The slot can only ever offer an eraser this catalogue actually
        // ships; a build with none has nothing for the tap to select.
        val slot = presets.firstOrNull { it.id == state.eraserId && it.eraseMode }
            ?: eraserOrNull(presets)
            ?: return state
        if (state.selectedId != slot.id) {
            return DesktopRailState(selectedId = slot.id, eraserId = slot.id)
        }

        val next = EraserTogglePolicy.next(slot.id, presets) ?: return state
        return DesktopRailState(selectedId = next, eraserId = next)
    }

    /**
     * How many paint slots the rail shows before the rest move into its
     * overflow menu — `LayoutSpec.paintSlotBudget`'s arithmetic against the
     * slots *this* rail has. The Android budget cannot be reused: it
     * reserves six non-paint slots for tools the desktop shell does not
     * carry, and it stops computing at all outside FULL mode.
     *
     * The overflow button only costs a slot when something overflows, so the
     * fit is tried without it first.
     */
    fun paintBudget(layout: LayoutSpec, availableDp: Int, paintCount: Int): Int {
        require(paintCount >= 0) { "paintCount must not be negative" }
        if (paintCount == 0) return 0

        val step = layout.toolSlotDp + toolGapDp(layout)
        fun fits(nonPaintSlots: Int): Int {
            val fixed = nonPaintSlots * step + DIVIDER_HEIGHT_DP +
                layout.sliderLengthDp + railPaddingDp(layout)
            return (availableDp - fixed) / step
        }

        // Only the eraser is fixed while everything fits; once it does not,
        // the overflow button takes a slot of its own.
        if (fits(ERASER_SLOTS) >= paintCount) return paintCount
        return fits(ERASER_SLOTS + OVERFLOW_SLOTS).coerceIn(1, paintCount)
    }

    fun paints(presets: List<BrushPreset>): List<BrushPreset> =
        BrushPresets.paintRailOrder(presets)

    fun eraserOrNull(presets: List<BrushPreset>): BrushPreset? =
        presets.firstOrNull { it.id == BrushPresets.HARD_ERASER_ID && it.eraseMode }
            ?: presets.firstOrNull(BrushPreset::eraseMode)

    // Mirrors DesktopToolRail's own spacing; a SHORT rail packs its slots.
    private fun toolGapDp(layout: LayoutSpec): Int =
        if (layout.railMode == RailMode.SHORT) 0 else TOOL_GAP_DP

    private fun railPaddingDp(layout: LayoutSpec): Int =
        if (layout.railMode == RailMode.SHORT) 0 else RAIL_PADDING_DP

    private const val ERASER_SLOTS = 1
    private const val OVERFLOW_SLOTS = 1
    private const val TOOL_GAP_DP = 4
    private const val DIVIDER_HEIGHT_DP = 9
    private const val RAIL_PADDING_DP = 24
}
