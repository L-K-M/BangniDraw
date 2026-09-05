package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
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
 * The five tools that are not a brush preset (`04-tools.md` §1). Kept as an
 * enum rather than a [ToolKind]: the rail only needs to know *which* slot is
 * lit, while the parameters each one is tuned with live in the shell state,
 * so switching away and back does not reset them.
 */
internal enum class DesktopSecondaryTool {
    SMUDGE,
    WATER,
    BLUR,
    FILL,
    EYEDROPPER,
}

/**
 * Which tool each rail slot selects, and what a click does to that choice.
 *
 * [selectedId] is the brush preset the rail remembers even while a secondary
 * tool is active, so clicking back to the paints returns to the brush the
 * user last painted with rather than to the catalogue's first.
 *
 * The rail's own budget ([DesktopRailPolicy.paintBudget]) counts the slots
 * this rail actually shows: the paints that fit, the overflow menu, one
 * eraser, and the secondary tools the current rail mode carries.
 */
internal data class DesktopRailState(
    /** The preset a paint or eraser slot selects; a paint id or [eraserId]. */
    val selectedId: String,
    /** Which of the shipped erasers the single eraser slot currently offers. */
    val eraserId: String,
    /** The lit secondary slot, or null while a brush preset is active. */
    val secondary: DesktopSecondaryTool? = null,
) {
    fun paintSelected(): Boolean = secondary == null && selectedId != eraserId

    fun brushSelected(): Boolean = secondary == null
}

internal object DesktopRailPolicy {

    fun initial(presets: List<BrushPreset>): DesktopRailState {
        val railPaints = paints(presets)
        val paint = railPaints.firstOrNull { it.id == BrushPresets.INK_PEN_ID }
            ?: railPaints.firstOrNull()
        val eraser = eraserOrNull(presets)

        return DesktopRailState(
            selectedId = paint?.id ?: eraser?.id ?: BrushPresets.INK_PEN_ID,
            eraserId = eraser?.id ?: BrushPresets.HARD_ERASER_ID,
        )
    }

    /**
     * A restored preference or a rail click selects that preset; an unknown id
     * changes nothing. Selecting a brush always leaves the secondary tools,
     * because the rail shows exactly one lit slot.
     */
    fun select(state: DesktopRailState, presetId: String, presets: List<BrushPreset>): DesktopRailState {
        val preset = presets.firstOrNull { it.id == presetId } ?: return state
        if (!preset.eraseMode) return state.copy(selectedId = preset.id, secondary = null)

        return state.copy(selectedId = preset.id, eraserId = preset.id, secondary = null)
    }

    /** Lights one secondary slot; the remembered brush preset stays put. */
    fun selectSecondary(state: DesktopRailState, tool: DesktopSecondaryTool): DesktopRailState =
        if (state.secondary == tool) state else state.copy(secondary = tool)

    /**
     * The eraser slot's click: select whichever eraser the slot holds.
     *
     * Android reaches the *other* eraser through a long-press. A long-press
     * is not a mouse gesture, and the second click is already spoken for —
     * every rail slot opens its settings when clicked while active — so the
     * hard/soft choice lives in the brush settings panel instead. One rule
     * for every slot beats two rules that collide on the eraser.
     */
    fun eraserTap(state: DesktopRailState, presets: List<BrushPreset>): DesktopRailState {
        // The slot can only ever offer an eraser this catalogue actually
        // ships; a build with none has nothing for the click to select.
        val slot = presets.firstOrNull { it.id == state.eraserId && it.eraseMode }
            ?: eraserOrNull(presets)
            ?: return state
        if (state.selectedId == slot.id && state.secondary == null) return state

        return DesktopRailState(selectedId = slot.id, eraserId = slot.id)
    }

    /**
     * How many secondary slots the rail shows for [layout]. FULL has room for
     * all five; every shorter mode shows three and hides blur and the
     * eyedropper behind a menu, exactly as `:app`'s `groupedSecondarySlots`
     * does — so the same tools are one click away on both products.
     */
    fun secondarySlotCount(layout: LayoutSpec): Int = visibleSecondary(layout).size

    /** Which secondary tools [layout] gives a slot of their own. */
    fun visibleSecondary(layout: LayoutSpec): List<DesktopSecondaryTool> =
        if (layout.railMode == RailMode.FULL) {
            DesktopSecondaryTool.entries
        } else {
            listOf(
                DesktopSecondaryTool.SMUDGE,
                DesktopSecondaryTool.WATER,
                DesktopSecondaryTool.FILL,
            )
        }

    /** The rest, which that mode puts behind the secondary overflow menu. */
    fun hiddenSecondary(layout: LayoutSpec): List<DesktopSecondaryTool> =
        DesktopSecondaryTool.entries - visibleSecondary(layout).toSet()

    /**
     * How many paint slots the rail shows before the rest move into its
     * overflow menu — `LayoutSpec.paintSlotBudget`'s arithmetic against the
     * slots *this* rail has. The Android budget cannot be reused: it stops
     * computing at all outside FULL mode, and its fixed reservation does not
     * match this rail's (one eraser, one paint overflow, and however many
     * secondary slots [layout] carries plus their own menu).
     *
     * The paint overflow button only costs a slot when something overflows,
     * so the fit is tried without it first.
     */
    fun paintBudget(layout: LayoutSpec, availableDp: Int, paintCount: Int): Int {
        require(paintCount >= 0) { "paintCount must not be negative" }
        if (paintCount == 0) return 0

        val step = layout.toolSlotDp + toolGapDp(layout)
        val fixedSlots = ERASER_SLOTS + secondarySlotCount(layout) +
            if (hiddenSecondary(layout).isEmpty()) 0 else SECONDARY_OVERFLOW_SLOTS
        fun fits(nonPaintSlots: Int): Int {
            val fixed = nonPaintSlots * step + DesktopRailGeometry.DIVIDER_HEIGHT_DP * DIVIDERS +
                layout.sliderLengthDp + railPaddingDp(layout)
            return (availableDp - fixed) / step
        }

        if (fits(fixedSlots) >= paintCount) return paintCount
        return fits(fixedSlots + OVERFLOW_SLOTS).coerceIn(1, paintCount)
    }

    /**
     * The preset the rail highlights and every slider surface tunes. One
     * function so the rail's foot and the ledge cannot end up tuning
     * different brushes — they are separate composables reading the same
     * state, which is exactly how that divergence starts.
     */
    fun activePreset(presets: List<BrushPreset>, rail: DesktopRailState): BrushPreset? =
        presets.firstOrNull { it.id == rail.selectedId } ?: presets.firstOrNull()

    fun paints(presets: List<BrushPreset>): List<BrushPreset> =
        BrushPresets.paintRailOrder(presets)

    fun eraserOrNull(presets: List<BrushPreset>): BrushPreset? =
        presets.firstOrNull { it.id == BrushPresets.HARD_ERASER_ID && it.eraseMode }
            ?: presets.firstOrNull(BrushPreset::eraseMode)

    // A SHORT rail packs its slots; the numbers themselves belong to
    // DesktopRailGeometry, because a budget computed from a stale copy of
    // them lays out paints the rail cannot fit.
    private fun toolGapDp(layout: LayoutSpec): Int =
        if (layout.railMode == RailMode.SHORT) 0 else DesktopRailGeometry.TOOL_GAP_DP

    private fun railPaddingDp(layout: LayoutSpec): Int =
        if (layout.railMode == RailMode.SHORT) 0 else DesktopRailGeometry.RAIL_PADDING_DP

    private const val ERASER_SLOTS = 1
    private const val OVERFLOW_SLOTS = 1
    private const val SECONDARY_OVERFLOW_SLOTS = 1
    /** Paints | eraser | secondary tools: two rules, as `:app`'s rail draws. */
    private const val DIVIDERS = 2
}

/**
 * The rail's spacing, in one place. [DesktopRailPolicy.paintBudget] solves
 * for how many slots fit using exactly the numbers [DesktopToolRail] lays
 * them out with, so the two cannot be separate constants: tuning one alone
 * would either clip a slot or overflow a paint that fits, silently.
 */
internal object DesktopRailGeometry {
    const val TOOL_GAP_DP = 4
    const val DIVIDER_HEIGHT_DP = 9
    const val RAIL_PADDING_DP = 24
}
