package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FillTilePixelsTest {

    @Test
    fun `tiled source reads RGBA and treats a missing tile as transparent`() {
        val grid = TileGrid(512, 512)
        val key = grid.keyAt(0, 0)
        val pixels = ByteArray(TILE_BYTES)
        val offset = (1 * TILE_SIZE + 2) * CHANNELS
        pixels[offset] = 10
        pixels[offset + 1] = 20
        pixels[offset + 2] = 30
        pixels[offset + 3] = 40
        val source = TiledPixelSource(grid, mapOf(key to pixels))

        assertEquals(Composite.argb(40, 10, 20, 30), source.pixel(2, 1))
        assertEquals(Composite.TRANSPARENT, source.pixel(300, 1))
    }

    @Test
    fun `coverage writes premultiplied RGBA across a tile boundary`() {
        val grid = TileGrid(512, 512)
        val coverage = Coverage(IntRect(255, 0, 257, 1), byteArrayOf(128.toByte(), 255.toByte()))
        val left = ByteArray(TILE_BYTES)
        val right = ByteArray(TILE_BYTES)
        val color = 0xFF804020.toInt()

        assertTrue(FillTilePixels.write(grid, grid.keyAt(255, 0), coverage, color, 1f, left))
        assertTrue(FillTilePixels.write(grid, grid.keyAt(256, 0), coverage, color, 1f, right))

        val leftOffset = 255 * CHANNELS
        assertEquals(listOf(64, 32, 16, 128), rgba(left, leftOffset))
        assertEquals(listOf(128, 64, 32, 255), rgba(right, 0))

        val untouched = ByteArray(TILE_BYTES) { 1 }
        assertFalse(FillTilePixels.write(grid, grid.keyAt(0, 300), coverage, color, 1f, untouched))
        assertTrue(untouched.all { it == 0.toByte() })
    }

    @Test
    fun `fill opacity multiplies anti-aliased coverage`() {
        val grid = TileGrid(256, 256)
        val coverage = Coverage(IntRect(0, 0, 1, 1), byteArrayOf(128.toByte()))
        val pixels = ByteArray(TILE_BYTES)

        FillTilePixels.write(grid, TileKey(0, 0), coverage, 0xFFFFFFFF.toInt(), 0.5f, pixels)

        assertEquals(listOf(64, 64, 64, 64), rgba(pixels, 0))
    }

    private fun rgba(bytes: ByteArray, offset: Int): List<Int> =
        (0 until CHANNELS).map { bytes[offset + it].toInt() and CHANNEL_MASK }

    private companion object {
        const val CHANNELS = 4
        const val CHANNEL_MASK = 0xFF
    }
}
