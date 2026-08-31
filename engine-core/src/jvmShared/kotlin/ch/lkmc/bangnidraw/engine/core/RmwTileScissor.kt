package ch.lkmc.bangnidraw.engine.core

/** GL scissor for a tile target whose row zero is canvas-top. */
data class RmwTileScissor(val x: Int, val y: Int, val width: Int, val height: Int) {
    companion object {
        fun forRect(grid: TileGrid, key: TileKey, rect: IntRect): RmwTileScissor {
            val tile = grid.tileRect(key)
            val left = maxOf(rect.left, tile.left)
            val top = maxOf(rect.top, tile.top)
            val right = minOf(rect.right, tile.right)
            val bottom = minOf(rect.bottom, tile.bottom)
            require(left < right && top < bottom) { "$rect does not touch $key" }
            return RmwTileScissor(
                x = left - tile.left,
                y = top - tile.top,
                width = right - left,
                height = bottom - top,
            )
        }
    }
}
