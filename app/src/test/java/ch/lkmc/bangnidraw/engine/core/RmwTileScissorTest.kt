package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RmwTileScissorTest {

    @Test
    fun `tile targets keep canvas top at GL row zero`() {
        val grid = TileGrid(512, 512)

        assertEquals(
            RmwTileScissor(0, 0, 20, 10),
            RmwTileScissor.forRect(grid, TileKey(0, 0), IntRect(0, 0, 20, 10)),
        )
        assertEquals(
            RmwTileScissor(4, 44, 16, 16),
            RmwTileScissor.forRect(grid, TileKey(1, 1), IntRect(260, 300, 276, 316)),
        )
    }
}
