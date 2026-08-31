package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES

/** Worst-case grow-only RGBA8 targets owned outside the shared tile pool. */
internal object WatercolorScratchBudget {

    private const val BYTES_PER_PIXEL = 4
    private const val COLOR_EDGE_PX = WatercolorDabPlan.MIN_GL_TEXTURE_SIZE
    private const val WET_EDGE_PX = COLOR_EDGE_PX / WatercolorKernel.CELL_SIZE
    private const val WET_TARGET_COUNT = 2
    private const val COLOR_BYTES = COLOR_EDGE_PX * COLOR_EDGE_PX * BYTES_PER_PIXEL
    private const val WET_BYTES = WET_EDGE_PX * WET_EDGE_PX * BYTES_PER_PIXEL

    val MAX_BYTES: Long = COLOR_BYTES.toLong() + WET_BYTES * WET_TARGET_COUNT + TILE_BYTES
}
