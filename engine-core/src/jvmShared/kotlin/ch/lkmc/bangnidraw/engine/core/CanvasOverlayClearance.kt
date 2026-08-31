package ch.lkmc.bangnidraw.engine.core

/**
 * Canvas chrome geometry shared by its layout and bottom overlays.
 * Dock clearance combines its rail and slider ledge; short mode has only
 * the ledge, while edge modes retain a touch-safe inset.
 */
object CanvasOverlayClearance {
    const val DOCK_HEIGHT_DP = 56

    fun bottomPaddingDp(railMode: RailMode): Int = when (railMode) {
        RailMode.DOCK -> DOCK_HEIGHT_DP + SLIDER_LEDGE_HEIGHT_DP
        RailMode.SHORT -> SLIDER_LEDGE_HEIGHT_DP
        RailMode.GROUPED, RailMode.FULL -> EDGE_BOTTOM_PADDING_DP
    }

    private const val SLIDER_LEDGE_HEIGHT_DP = 64
    private const val EDGE_BOTTOM_PADDING_DP = 16
}
