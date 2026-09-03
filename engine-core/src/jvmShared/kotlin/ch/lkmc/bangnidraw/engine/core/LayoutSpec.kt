package ch.lkmc.bangnidraw.engine.core

import kotlin.math.ceil

/** Android window width classes without an Android or Compose dependency. */
enum class WidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED;

    companion object {
        fun forWidth(widthDp: Int): WidthClass {
            require(widthDp >= 0) { "widthDp must not be negative" }

            if (widthDp < MEDIUM_MIN_DP) return COMPACT
            if (widthDp < EXPANDED_MIN_DP) return MEDIUM
            return EXPANDED
        }

        const val MEDIUM_MIN_DP = 600
        const val EXPANDED_MIN_DP = 840
    }
}

enum class Hand {
    LEFT,
    RIGHT;

    companion object {
        fun fromStored(value: String?): Hand = entries.firstOrNull { it.name == value } ?: RIGHT
    }
}

enum class RailMode { FULL, GROUPED, SHORT, DOCK }

enum class PanelMode { FULL_HEIGHT_SHEET, SIDE_SHEET, FLOATING }

enum class SliderPlacement { IN_RAIL, LEDGE }

/** A small geometry value used to prove the chrome leaves the work area clear. */
data class LayoutRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left <= right) { "left must not exceed right" }
        require(top <= bottom) { "top must not exceed bottom" }
    }

    fun intersects(other: LayoutRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun mirrorX(widthDp: Int): LayoutRect = copy(
        left = widthDp - right,
        right = widthDp - left,
    )

    companion object {
        fun centralClearZone(widthDp: Int, heightDp: Int): LayoutRect {
            require(widthDp >= 0 && heightDp >= 0) { "window dimensions must not be negative" }

            val horizontalInset = widthDp * CLEAR_ZONE_EDGE_FRACTION
            val verticalInset = heightDp * CLEAR_ZONE_EDGE_FRACTION
            return LayoutRect(
                left = horizontalInset,
                top = verticalInset,
                right = widthDp - horizontalInset,
                bottom = heightDp - verticalInset,
            )
        }

        private const val CLEAR_ZONE_EDGE_FRACTION = 0.2f
    }
}

/** Edge space a panel must leave for persistent canvas chrome. */
data class PanelInsets(
    val leftDp: Int,
    val topDp: Int,
    val rightDp: Int,
    val bottomDp: Int,
)

/**
 * Pure adaptive-layout decision table from `docs/plan/08-ui-and-layout.md` §1.
 *
 * [heightDp] is the rail's available height: the window height after the
 * status-bar inset and 48 dp top strip have been removed.
 */
data class LayoutSpec(
    val widthClass: WidthClass,
    val railMode: RailMode,
    val railSide: Hand,
    val panelSide: Hand,
    val panelMode: PanelMode,
    val sliderPlacement: SliderPlacement,
    val gridMinCellDp: Int,
    val toolSlotDp: Int,
    val railWidthDp: Int,
    val sliderLengthDp: Int,
    val railContentHeightDp: Int,
    /**
     * Paint-preset slots the FULL rail shows; the rest overflow to the
     * settings sheet's chip row. `Int.MAX_VALUE` outside FULL mode, where
     * the rail never lists presets (GROUPED/SHORT/DOCK show the active one).
     */
    val paintSlotBudget: Int,
) {
    /** Persistent chrome only; transient panels and the first-run hint are excluded. */
    fun persistentChrome(windowWidthDp: Int, windowHeightDp: Int): List<LayoutRect> {
        require(windowWidthDp >= 0 && windowHeightDp >= 0) {
            "window dimensions must not be negative"
        }

        val stripBottom = minOf(TOP_STRIP_DP, windowHeightDp).toFloat()
        val chrome = mutableListOf(
            LayoutRect(0f, 0f, windowWidthDp.toFloat(), stripBottom),
        )
        if (railMode == RailMode.DOCK) {
            addDockChrome(chrome, windowWidthDp, windowHeightDp)
            return chrome
        }

        val railWidth = minOf(railWidthDp, windowWidthDp).toFloat()
        val rail = LayoutRect(
            left = windowWidthDp - railWidth,
            top = stripBottom,
            right = windowWidthDp.toFloat(),
            bottom = windowHeightDp.toFloat(),
        ).onSide(railSide, windowWidthDp)
        chrome += rail

        if (railMode == RailMode.SHORT) {
            val ledgeTop = maxOf(stripBottom, windowHeightDp - LEDGE_HEIGHT_DP.toFloat())
            val rightHandLedge = LayoutRect(
                left = 0f,
                top = ledgeTop,
                right = windowWidthDp - railWidth,
                bottom = windowHeightDp.toFloat(),
            )
            chrome += rightHandLedge.onSide(railSide, windowWidthDp)
        }

        return chrome
    }

    /** Keeps panels outside the strip, rail, dock, and slider ledge. */
    fun panelInsets(windowWidthDp: Int, windowHeightDp: Int): PanelInsets {
        require(windowWidthDp >= 0 && windowHeightDp >= 0) {
            "window dimensions must not be negative"
        }

        val width = windowWidthDp.toFloat()
        val height = windowHeightDp.toFloat()
        val chrome = persistentChrome(windowWidthDp, windowHeightDp)
        val top = chrome.asSequence()
            .filter { it.top == 0f && it.left == 0f && it.right == width }
            .maxOfOrNull(LayoutRect::bottom)
            ?: 0f
        val sideWidth = chrome.asSequence()
            .filter { it.top <= top && it.bottom == height }
            .filter {
                if (panelSide == Hand.LEFT) it.left == 0f && it.right < width
                else it.right == width && it.left > 0f
            }
            .maxOfOrNull { it.right - it.left }
            ?: 0f
        val sideGap = if (panelMode == PanelMode.FLOATING && sideWidth > 0f) {
            FLOATING_PANEL_GAP_DP.toFloat()
        } else {
            0f
        }
        val left = if (panelSide == Hand.LEFT) sideWidth + sideGap else 0f
        val right = if (panelSide == Hand.RIGHT) sideWidth + sideGap else 0f
        val laneLeft = left
        val laneRight = width - right
        val lowerChromeTop = chrome.asSequence()
            .filter { it.bottom > top }
            // Full-height rails are already reserved by the side inset.
            .filter { it.top > top || it.bottom < height }
            .filter { it.left < laneRight && it.right > laneLeft }
            .minOfOrNull(LayoutRect::top)
        val bottom = lowerChromeTop?.let { height - it } ?: 0f

        return PanelInsets(
            leftDp = ceil(left).toInt(),
            topDp = ceil(top).toInt(),
            rightDp = ceil(right).toInt(),
            bottomDp = ceil(bottom).toInt(),
        )
    }

    private fun addDockChrome(
        chrome: MutableList<LayoutRect>,
        windowWidthDp: Int,
        windowHeightDp: Int,
    ) {
        val dockTop = maxOf(TOP_STRIP_DP, windowHeightDp - DOCK_HEIGHT_DP).toFloat()
        chrome += LayoutRect(
            left = 0f,
            top = dockTop,
            right = windowWidthDp.toFloat(),
            bottom = windowHeightDp.toFloat(),
        )

        val ledgeBottom = maxOf(TOP_STRIP_DP.toFloat(), dockTop - LEDGE_GAP_DP)
        val ledgeTop = maxOf(TOP_STRIP_DP.toFloat(), ledgeBottom - LEDGE_HEIGHT_DP)
        chrome += LayoutRect(0f, ledgeTop, windowWidthDp.toFloat(), ledgeBottom)
    }

    private fun LayoutRect.onSide(side: Hand, widthDp: Int): LayoutRect {
        if (side == Hand.RIGHT) return this
        return mirrorX(widthDp)
    }

    companion object {
        fun forWindow(
            width: WidthClass,
            heightDp: Int,
            hand: Hand,
            paintCount: Int = DEFAULT_PAINT_COUNT,
        ): LayoutSpec {
            require(heightDp >= 0) { "heightDp must not be negative" }
            require(paintCount >= 1) { "paintCount must be positive" }

            val railMode = railMode(width, heightDp)
            val slot = toolSlot(width, railMode)
            val sliderLength = when (railMode) {
                RailMode.FULL -> FULL_SLIDER_DP
                RailMode.GROUPED -> GROUPED_SLIDER_DP
                RailMode.SHORT, RailMode.DOCK -> 0
            }
            val sliderPlacement = if (railMode == RailMode.FULL || railMode == RailMode.GROUPED) {
                SliderPlacement.IN_RAIL
            } else {
                SliderPlacement.LEDGE
            }
            val paintSlotBudget = paintSlotBudget(railMode, slot, heightDp, paintCount)

            return LayoutSpec(
                widthClass = width,
                railMode = railMode,
                railSide = hand,
                panelSide = hand,
                panelMode = when (railMode) {
                    RailMode.DOCK -> PanelMode.FULL_HEIGHT_SHEET
                    RailMode.SHORT, RailMode.GROUPED -> PanelMode.SIDE_SHEET
                    RailMode.FULL -> PanelMode.FLOATING
                },
                sliderPlacement = sliderPlacement,
                gridMinCellDp = when (width) {
                    WidthClass.COMPACT -> COMPACT_GRID_CELL_DP
                    WidthClass.MEDIUM -> MEDIUM_GRID_CELL_DP
                    WidthClass.EXPANDED -> EXPANDED_GRID_CELL_DP
                },
                toolSlotDp = slot,
                railWidthDp = if (sliderPlacement == SliderPlacement.IN_RAIL) {
                    IN_RAIL_WIDTH_DP
                } else {
                    slot + RAIL_EXTRA_WIDTH_DP
                },
                sliderLengthDp = sliderLength,
                railContentHeightDp = contentHeight(railMode, slot, paintSlotBudget),
                paintSlotBudget = paintSlotBudget,
            )
        }

        fun shortContentHeightDp(): Int = SHORT_TOOL_COUNT * MIN_TARGET_DP

        private fun railMode(width: WidthClass, heightDp: Int): RailMode {
            if (width == WidthClass.COMPACT || heightDp < SHORT_MIN_DP) return RailMode.DOCK

            val groupedMinimum = if (width == WidthClass.EXPANDED) {
                EXPANDED_GROUPED_MIN_DP
            } else {
                MEDIUM_GROUPED_MIN_DP
            }
            if (heightDp < groupedMinimum) return RailMode.SHORT

            val fullMinimum = if (width == WidthClass.EXPANDED) {
                EXPANDED_FULL_MIN_DP
            } else {
                MEDIUM_FULL_MIN_DP
            }
            if (heightDp < fullMinimum) return RailMode.GROUPED
            return RailMode.FULL
        }

        private fun toolSlot(width: WidthClass, mode: RailMode): Int {
            if (mode == RailMode.SHORT || mode == RailMode.DOCK) return MIN_TARGET_DP
            if (width == WidthClass.EXPANDED) return EXPANDED_TARGET_DP
            return MIN_TARGET_DP
        }

        private fun contentHeight(
            mode: RailMode,
            slotDp: Int,
            paintSlotBudget: Int,
        ): Int = when (mode) {
            RailMode.DOCK -> DOCK_HEIGHT_DP
            RailMode.SHORT -> shortContentHeightDp()
            RailMode.GROUPED ->
                GROUPED_TOOL_COUNT * slotDp +
                    GROUPED_GAP_COUNT * TOOL_GAP_DP +
                    DIVIDER_HEIGHT_DP +
                    GROUPED_SLIDER_DP +
                    RAIL_PADDING_DP
            RailMode.FULL -> {
                val toolCount = paintSlotBudget + FULL_NON_PAINT_SLOTS

                toolCount * slotDp +
                    (toolCount - 1) * TOOL_GAP_DP +
                    FULL_DIVIDER_COUNT * DIVIDER_HEIGHT_DP +
                    FULL_SLIDER_DP +
                    RAIL_PADDING_DP
            }
        }

        /**
         * How many paint-preset slots the FULL rail shows before the rest
         * moves to the settings sheet's chip row (`docs/plan/08-ui-and-layout.md`
         * §1). The mode thresholds above are sized for the v1 set of five
         * paints; a larger catalogue must not stretch the rail past the
         * window, so extra presets overflow into the sheet instead. Solves
         * `paints·(slot + gap) + NON_PAINT ≤ heightDp`, where NON_PAINT is
         * the fixed non-paint slots, their gaps, dividers, slider, and padding.
         */
        private fun paintSlotBudget(
            mode: RailMode,
            slotDp: Int,
            heightDp: Int,
            paintCount: Int,
        ): Int {
            if (mode != RailMode.FULL) return Int.MAX_VALUE

            val available = heightDp - FULL_NON_PAINT_SLOTS * slotDp -
                FULL_NON_PAINT_GAPS * TOOL_GAP_DP -
                FULL_DIVIDER_COUNT * DIVIDER_HEIGHT_DP - FULL_SLIDER_DP - RAIL_PADDING_DP
            return (available / (slotDp + TOOL_GAP_DP)).coerceIn(1, paintCount)
        }

        const val MIN_TARGET_DP = 48
        const val EXPANDED_TARGET_DP = 56
        const val TOP_STRIP_DP = 48
        /** The docked rail's height; both rails and the ledge above it read this. */
        const val DOCK_HEIGHT_DP = 56
        /**
         * The gap the slider ledge floats above what it sits on; the docked
         * chrome reserves it here and the shell's ledge reads the same value.
         */
        const val LEDGE_GAP_DP = 8f
        const val COMPACT_GRID_CELL_DP = 150
        const val MEDIUM_GRID_CELL_DP = 180
        const val EXPANDED_GRID_CELL_DP = 220
        const val FLOATING_PANEL_GAP_DP = 8

        private const val SHORT_MIN_DP = 288
        private const val MEDIUM_GROUPED_MIN_DP = 461
        private const val EXPANDED_GROUPED_MIN_DP = 509
        private const val MEDIUM_FULL_MIN_DP = 718
        private const val EXPANDED_FULL_MIN_DP = 798
        private const val SHORT_TOOL_COUNT = 6
        private const val GROUPED_TOOL_COUNT = 6
        private const val DEFAULT_PAINT_COUNT = 5
        // Mirrors ToolRail.fullSlots: one eraser and five secondary tools.
        private const val FULL_NON_PAINT_SLOTS = 6
        private const val FULL_NON_PAINT_GAPS = 5
        private const val GROUPED_GAP_COUNT = 5
        private const val FULL_DIVIDER_COUNT = 2
        private const val TOOL_GAP_DP = 4
        private const val DIVIDER_HEIGHT_DP = 9
        private const val GROUPED_SLIDER_DP = 120
        private const val FULL_SLIDER_DP = 160
        private const val RAIL_PADDING_DP = 24
        private const val RAIL_EXTRA_WIDTH_DP = 8
        private const val SLIDER_COUNT = 2
        private const val SLIDER_TOUCH_SLAB_DP = 48
        private const val RAIL_HORIZONTAL_PADDING_DP = 4
        private const val IN_RAIL_WIDTH_DP =
            SLIDER_COUNT * SLIDER_TOUCH_SLAB_DP + 2 * RAIL_HORIZONTAL_PADDING_DP
        private const val LEDGE_HEIGHT_DP = 48
    }
}
