package ch.lkmc.bangnidraw.engine.core

/** Canvas-clipped output and source footprints for one ordered RMW dab. */
data class RmwDabPlan(val output: IntRect, val source: IntRect) {

    companion object {
        fun forDab(
            grid: TileGrid,
            x: Float,
            y: Float,
            radius: Float,
            blurRadius: Int,
        ): RmwDabPlan {
            require(blurRadius in 0..BlurKernel.MAX_RADIUS) {
                "RMW blur radius must be 0..${BlurKernel.MAX_RADIUS}, was $blurRadius"
            }
            val output = IntRect.forDab(x, y, radius).clipTo(grid)
            if (output.isEmpty || blurRadius == 0) return RmwDabPlan(output, output)

            val source = IntRect(
                left = output.left - blurRadius,
                top = output.top - blurRadius,
                right = output.right + blurRadius,
                bottom = output.bottom + blurRadius,
            ).clipTo(grid)
            return RmwDabPlan(output, source)
        }
    }
}

private fun IntRect.clipTo(grid: TileGrid): IntRect = IntRect(
    left = left.coerceIn(0, grid.width),
    top = top.coerceIn(0, grid.height),
    right = right.coerceIn(0, grid.width),
    bottom = bottom.coerceIn(0, grid.height),
)
