package ch.lkmc.bangnidraw.engine.core

/**
 * Keeps bottom-center overlays above persistent Canvas chrome. Alerts,
 * progress, and navigation controls use this one clearance rule.
 */
internal object CanvasOverlayClearance {
    fun bottomPaddingDp(railMode: RailMode): Int = when (railMode) {
        RailMode.DOCK -> DOCK_BOTTOM_PADDING_DP
        RailMode.SHORT -> LEDGE_BOTTOM_PADDING_DP
        RailMode.GROUPED, RailMode.FULL -> EDGE_BOTTOM_PADDING_DP
    }

    private const val DOCK_BOTTOM_PADDING_DP = 120
    private const val LEDGE_BOTTOM_PADDING_DP = 64
    private const val EDGE_BOTTOM_PADDING_DP = 16
}
