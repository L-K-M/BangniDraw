package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileContentIndexTest {

    private val grid = TileGrid(512, 256)
    private val first = TileKey(0)
    private val second = TileKey(1)

    @Test
    fun `fresh and painted tiles may contain color`() {
        val index = TileContentIndex(grid)

        assertFalse(index.mayContainColor(first))
        index.allocated(first)
        assertTrue(index.mayContainColor(first))
        index.record(first, TilePresence.PAINTED)
        assertTrue(index.mayContainColor(first))
    }

    @Test
    fun `empty readback overrides resident allocation`() {
        val index = TileContentIndex(grid)

        index.allocated(first)
        index.record(first, TilePresence.EMPTY)

        assertFalse(index.mayContainColor(first))
    }

    @Test
    fun `writing an empty resident tile makes its content unknown`() {
        val index = TileContentIndex(grid)
        index.record(first, TilePresence.EMPTY)

        index.written(first)

        assertTrue(index.mayContainColor(first))
    }

    @Test
    fun `readback occupancy excludes distant blank regions in one tile`() {
        val index = TileContentIndex(grid)
        val pixels = ByteBuffer.allocate(TILE_BYTES)
        val paintedX = 240
        val paintedY = 240
        val alpha = ((paintedY * PerfConstants.TILE_SIZE + paintedX) * 4) + 3
        pixels.put(alpha, 0xff.toByte())

        index.record(first, pixels)

        assertFalse(index.mayContainColor(first, IntRect(0, 0, 32, 32)))
        assertTrue(index.mayContainColor(first, IntRect(239, 239, 242, 242)))
        assertEquals(0, pixels.position())
    }

    @Test
    fun `remove and clear forget semantic content`() {
        val index = TileContentIndex(grid)
        index.record(first, TilePresence.PAINTED)
        index.record(second, TilePresence.PAINTED)

        index.removed(first)
        assertFalse(index.mayContainColor(first))
        assertTrue(index.mayContainColor(second))

        index.clear()
        assertFalse(index.mayContainColor(second))
    }
}
