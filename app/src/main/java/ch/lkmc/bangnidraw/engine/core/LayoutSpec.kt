package ch.lkmc.bangnidraw.engine.core

/** Android window width classes without an Android or Compose dependency. */
internal enum class WidthClass {
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

internal enum class Hand {
    LEFT,
    RIGHT;

    companion object {
        fun fromStored(value: String?): Hand = entries.firstOrNull { it.name == value } ?: RIGHT
    }
}

internal enum class RailMode { FULL, GROUPED, SHORT, DOCK }

internal enum class PanelMode { FULL_HEIGHT_SHEET, SIDE_SHEET, FLOATING }

internal enum class SliderPlacement { IN_RAIL, LEDGE }

/** A small geometry value used to prove the chrome leaves the work area clear. */
internal data class LayoutRect(
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

/**
 * Pure adaptive-layout decision table from `docs/plan/08-ui-and-layout.md` §1.
 *
 * [heightDp] is the rail's available height: the window height after the
 * status-bar inset and 48 dp top strip have been removed.
 */
internal data class LayoutSpec(
    val widthClass: WidthClass,
    val railMode: RailMode,
    val railSide: Hand,
    val panelSide: Hand,
    val panelMode: PanelMode,
    val sliderPlacement: SliderPlacement,
    val gridMinCellDp: Int,
    val toolSlotDp: Int,
    val sliderLengthDp: Int,
    val railContentHeightDp: Int,
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

        val railWidth = minOf(toolSlotDp + RAIL_EXTRA_WIDTH_DP, windowWidthDp).toFloat()
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
        fun forWindow(width: WidthClass, heightDp: Int, hand: Hand): LayoutSpec {
            require(heightDp >= 0) { "heightDp must not be negative" }

            val railMode = railMode(width, heightDp)
            val slot = toolSlot(width, railMode)
            val sliderLength = when (railMode) {
                RailMode.FULL -> FULL_SLIDER_DP
                RailMode.GROUPED -> GROUPED_SLIDER_DP
                RailMode.SHORT, RailMode.DOCK -> 0
            }
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
                sliderPlacement = if (railMode == RailMode.FULL || railMode == RailMode.GROUPED) {
                    SliderPlacement.IN_RAIL
                } else {
                    SliderPlacement.LEDGE
                },
                gridMinCellDp = when (width) {
                    WidthClass.COMPACT -> COMPACT_GRID_CELL_DP
                    WidthClass.MEDIUM -> MEDIUM_GRID_CELL_DP
                    WidthClass.EXPANDED -> EXPANDED_GRID_CELL_DP
                },
                toolSlotDp = slot,
                sliderLengthDp = sliderLength,
                railContentHeightDp = contentHeight(railMode, slot),
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
            if (mode == RailMode.SHORT) return MIN_TARGET_DP
            if (mode == RailMode.DOCK) return DOCK_HEIGHT_DP
            if (width == WidthClass.EXPANDED) return EXPANDED_TARGET_DP
            return MIN_TARGET_DP
        }

        private fun contentHeight(mode: RailMode, slotDp: Int): Int = when (mode) {
            RailMode.DOCK -> DOCK_HEIGHT_DP
            RailMode.SHORT -> shortContentHeightDp()
            RailMode.GROUPED ->
                GROUPED_TOOL_COUNT * slotDp +
                    GROUPED_GAP_COUNT * TOOL_GAP_DP +
                    DIVIDER_HEIGHT_DP +
                    GROUPED_SLIDER_DP +
                    RAIL_PADDING_DP
            RailMode.FULL ->
                FULL_TOOL_COUNT * slotDp +
                    FULL_GAP_COUNT * TOOL_GAP_DP +
                    FULL_DIVIDER_COUNT * DIVIDER_HEIGHT_DP +
                    FULL_SLIDER_DP +
                    RAIL_PADDING_DP
        }

        const val MIN_TARGET_DP = 48
        const val EXPANDED_TARGET_DP = 56
        const val TOP_STRIP_DP = 48
        const val COMPACT_GRID_CELL_DP = 150
        const val MEDIUM_GRID_CELL_DP = 180
        const val EXPANDED_GRID_CELL_DP = 220

        private const val SHORT_MIN_DP = 288
        private const val MEDIUM_GROUPED_MIN_DP = 461
        private const val EXPANDED_GROUPED_MIN_DP = 509
        private const val MEDIUM_FULL_MIN_DP = 718
        private const val EXPANDED_FULL_MIN_DP = 798
        private const val SHORT_TOOL_COUNT = 6
        private const val GROUPED_TOOL_COUNT = 6
        private const val FULL_TOOL_COUNT = 10
        private const val GROUPED_GAP_COUNT = 5
        private const val FULL_GAP_COUNT = 9
        private const val FULL_DIVIDER_COUNT = 2
        private const val TOOL_GAP_DP = 4
        private const val DIVIDER_HEIGHT_DP = 9
        private const val GROUPED_SLIDER_DP = 120
        private const val FULL_SLIDER_DP = 160
        private const val RAIL_PADDING_DP = 24
        private const val RAIL_EXTRA_WIDTH_DP = 8
        private const val DOCK_HEIGHT_DP = 56
        private const val LEDGE_HEIGHT_DP = 48
        private const val LEDGE_GAP_DP = 8f
    }
}
