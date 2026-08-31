package ch.lkmc.bangnidraw.engine.core

/** Reusable full- and coarse-resolution bounds for one watercolor dab. */
class WatercolorDabBounds(private val grid: TileGrid) {

    var outputLeft = 0
        private set
    var outputTop = 0
        private set
    var outputRight = 0
        private set
    var outputBottom = 0
        private set

    var sourceLeft = 0
        private set
    var sourceTop = 0
        private set
    var sourceRight = 0
        private set
    var sourceBottom = 0
        private set

    var wetOutputLeft = 0
        private set
    var wetOutputTop = 0
        private set
    var wetOutputRight = 0
        private set
    var wetOutputBottom = 0
        private set

    var wetSourceLeft = 0
        private set
    var wetSourceTop = 0
        private set
    var wetSourceRight = 0
        private set
    var wetSourceBottom = 0
        private set

    private val wetWidth = ceilDiv(grid.width, WatercolorKernel.CELL_SIZE)
    private val wetHeight = ceilDiv(grid.height, WatercolorKernel.CELL_SIZE)

    /** Updates every edge without allocating. False means the dab misses the canvas. */
    fun set(x: Float, y: Float, radius: Float, spread: Float): Boolean {
        require(spread.isFinite() && spread in 0f..1f) {
            "watercolor spread must be 0..1, was $spread"
        }

        val diameter = radius * 2f
        require(radius.isFinite() && radius >= 0f && diameter <= WatercolorDabPlan.MAX_DIAMETER_PX) {
            "watercolor diameter exceeds the GLES scratch bound"
        }
        DabBounds.requireValid(x, y, radius)

        val spreadPx = WatercolorDabPlan.spreadPx(radius, spread)
        outputLeft = clip(DabBounds.left(x, radius).toLong() - spreadPx, grid.width)
        outputTop = clip(DabBounds.top(y, radius).toLong() - spreadPx, grid.height)
        outputRight = clip(DabBounds.right(x, radius).toLong() + spreadPx, grid.width)
        outputBottom = clip(DabBounds.bottom(y, radius).toLong() + spreadPx, grid.height)
        if (outputLeft >= outputRight || outputTop >= outputBottom) {
            clear()
            return false
        }

        val scale = WatercolorKernel.CELL_SIZE
        wetOutputLeft = outputLeft / scale
        wetOutputTop = outputTop / scale
        wetOutputRight = ceilDiv(outputRight, scale)
        wetOutputBottom = ceilDiv(outputBottom, scale)
        wetSourceLeft = (wetOutputLeft - WET_SOURCE_HALO).coerceAtLeast(0)
        wetSourceTop = (wetOutputTop - WET_SOURCE_HALO).coerceAtLeast(0)
        wetSourceRight = (wetOutputRight + WET_SOURCE_HALO).coerceAtMost(wetWidth)
        wetSourceBottom = (wetOutputBottom + WET_SOURCE_HALO).coerceAtMost(wetHeight)
        sourceLeft = (wetSourceLeft * scale).coerceAtMost(grid.width)
        sourceTop = (wetSourceTop * scale).coerceAtMost(grid.height)
        sourceRight = (wetSourceRight * scale).coerceAtMost(grid.width)
        sourceBottom = (wetSourceBottom * scale).coerceAtMost(grid.height)
        return true
    }

    private fun clear() {
        outputLeft = 0
        outputTop = 0
        outputRight = 0
        outputBottom = 0
        sourceLeft = 0
        sourceTop = 0
        sourceRight = 0
        sourceBottom = 0
        wetOutputLeft = 0
        wetOutputTop = 0
        wetOutputRight = 0
        wetOutputBottom = 0
        wetSourceLeft = 0
        wetSourceTop = 0
        wetSourceRight = 0
        wetSourceBottom = 0
    }

    private companion object {
        const val WET_SOURCE_HALO = 1

        fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

        fun clip(value: Long, edge: Int): Int = value.coerceIn(0L, edge.toLong()).toInt()
    }
}
