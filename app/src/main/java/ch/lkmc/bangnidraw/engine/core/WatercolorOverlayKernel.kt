package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.StrokeMerge.Rgba

/** Pure visibility policy for the transient wet-state overlay. */
object WatercolorOverlayKernel {

    enum class Refresh {
        IDLE,
        REDRAW,
        REDRAW_AND_CONTINUE,
    }

    data class RefreshResult(
        val action: Refresh,
        val dirty: IntRect,
    )

    /** A cool, low-opacity sheen keeps clear Water distinct from document pixels. */
    const val CUE_RED = 0.25f
    const val CUE_GREEN = 0.65f
    const val CUE_BLUE = 1f
    const val MAX_ALPHA = 0.18f

    /** Presentation follows the same ten-hertz clock as wet-state aging. */
    const val REFRESH_MILLIS = WatercolorKernel.TICK_NANOS / 1_000_000L

    fun cue(cell: WatercolorWetKernel.StoredCell, nowTick: Int): Rgba {
        require(nowTick in 0 until WatercolorKernel.TICK_MODULUS) {
            "nowTick must fit two bytes, was $nowTick"
        }

        val updatedTick = WatercolorWetKernel.decodeTick(cell.tickHigh, cell.tickLow)
        val ageTicks = WatercolorKernel.ageTicks(nowTick, updatedTick)
        val retention = (
            1f - ageTicks.toFloat() / WatercolorKernel.DRY_TICKS
        ).coerceIn(0f, 1f)
        val water = cell.surfaceWater + cell.saturation * (1f - cell.surfaceWater)
        val alpha = water * retention * MAX_ALPHA

        return Rgba(
            r = CUE_RED * alpha,
            g = CUE_GREEN * alpha,
            b = CUE_BLUE * alpha,
            a = alpha,
        )
    }

    /** The final expired tile still needs one redraw to remove its old cue. */
    fun refresh(beforeTiles: Int, afterTiles: Int): Refresh {
        require(beforeTiles >= 0) { "beforeTiles must not be negative, was $beforeTiles" }
        require(afterTiles >= 0) { "afterTiles must not be negative, was $afterTiles" }
        if (afterTiles > 0) return Refresh.REDRAW_AND_CONTINUE
        if (beforeTiles > 0) return Refresh.REDRAW

        return Refresh.IDLE
    }
}
