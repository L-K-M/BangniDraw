package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import java.nio.ByteBuffer

/** Semantic tile occupancy independent of a resident GPU slice. */
internal class TileContentIndex(private val grid: TileGrid) {

    private val states = ByteArray(grid.tileCount)
    private val paintedBlocks = LongArray(grid.tileCount * WORDS_PER_TILE)

    /** A fresh GPU destination is unknown until readback classifies it. */
    fun allocated(key: TileKey) {
        states[grid.index(key)] = UNKNOWN
    }

    /** A GPU write invalidates an EMPTY result until the next readback. */
    fun written(key: TileKey) {
        states[grid.index(key)] = UNKNOWN
    }

    fun record(key: TileKey, presence: TilePresence) {
        val tileIndex = grid.index(key)
        clearBlocks(tileIndex)
        states[tileIndex] = when (presence) {
            TilePresence.EMPTY -> EMPTY
            TilePresence.PAINTED -> UNKNOWN
        }
    }

    /** Captures 4 px alpha occupancy without advancing the shared buffer. */
    fun record(key: TileKey, pixels: ByteBuffer) {
        require(pixels.remaining() == TILE_BYTES) {
            "tile occupancy needs $TILE_BYTES bytes, got ${pixels.remaining()}"
        }

        val tileIndex = grid.index(key)
        clearBlocks(tileIndex)
        val start = pixels.position()
        val wordBase = tileIndex * WORDS_PER_TILE
        var painted = false
        for (blockY in 0 until BLOCKS_PER_AXIS) {
            for (blockX in 0 until BLOCKS_PER_AXIS) {
                if (!blockPainted(pixels, start, blockX, blockY)) continue

                val blockIndex = blockY * BLOCKS_PER_AXIS + blockX
                val word = blockIndex / WORD_BITS
                val bit = blockIndex % WORD_BITS
                paintedBlocks[wordBase + word] =
                    paintedBlocks[wordBase + word] or (1L shl bit)
                painted = true
            }
        }
        states[tileIndex] = if (painted) KNOWN else EMPTY
    }

    fun removed(key: TileKey) {
        val tileIndex = grid.index(key)
        clearBlocks(tileIndex)
        states[tileIndex] = EMPTY
    }

    fun mayContainColor(key: TileKey): Boolean {
        val tileIndex = grid.index(key)
        if (states[tileIndex] == UNKNOWN) return true
        if (states[tileIndex] == EMPTY) return false

        val wordBase = tileIndex * WORDS_PER_TILE
        for (word in 0 until WORDS_PER_TILE) {
            if (paintedBlocks[wordBase + word] != 0L) return true
        }

        return false
    }

    /** Queries only blocks intersecting rect, not the whole resident tile. */
    fun mayContainColor(key: TileKey, rect: IntRect): Boolean =
        mayContainColor(key, rect.left, rect.top, rect.right, rect.bottom)

    /** Primitive form for allocation-free dab loops. */
    fun mayContainColor(
        key: TileKey,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        val tileIndex = grid.index(key)
        if (states[tileIndex] == UNKNOWN) return true
        if (states[tileIndex] == EMPTY) return false

        val tileLeft = key.tx * TILE_SIZE
        val tileTop = key.ty * TILE_SIZE
        val tileRight = minOf(tileLeft + TILE_SIZE, grid.width)
        val tileBottom = minOf(tileTop + TILE_SIZE, grid.height)
        val intersectionLeft = maxOf(left, tileLeft)
        val intersectionTop = maxOf(top, tileTop)
        val intersectionRight = minOf(right, tileRight)
        val intersectionBottom = minOf(bottom, tileBottom)
        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return false
        }

        val blockLeft = (intersectionLeft - tileLeft) / BLOCK_SIZE
        val blockTop = (intersectionTop - tileTop) / BLOCK_SIZE
        val blockRight = ceilDiv(intersectionRight - tileLeft, BLOCK_SIZE)
        val blockBottom = ceilDiv(intersectionBottom - tileTop, BLOCK_SIZE)
        val wordBase = tileIndex * WORDS_PER_TILE
        for (blockY in blockTop until blockBottom) {
            for (blockX in blockLeft until blockRight) {
                val blockIndex = blockY * BLOCKS_PER_AXIS + blockX
                val word = blockIndex / WORD_BITS
                val bit = blockIndex % WORD_BITS
                if (paintedBlocks[wordBase + word] and (1L shl bit) != 0L) return true
            }
        }

        return false
    }

    fun clear() {
        states.fill(EMPTY)
        paintedBlocks.fill(0L)
    }

    private fun blockPainted(
        pixels: ByteBuffer,
        start: Int,
        blockX: Int,
        blockY: Int,
    ): Boolean {
        val firstX = blockX * BLOCK_SIZE
        val firstY = blockY * BLOCK_SIZE
        for (pixelY in firstY until firstY + BLOCK_SIZE) {
            for (pixelX in firstX until firstX + BLOCK_SIZE) {
                val alphaIndex = start +
                    (pixelY * TILE_SIZE + pixelX) * CHANNEL_COUNT +
                    ALPHA_CHANNEL
                if (pixels.get(alphaIndex).toInt() and BYTE_MASK != 0) return true
            }
        }

        return false
    }

    private fun clearBlocks(tileIndex: Int) {
        val from = tileIndex * WORDS_PER_TILE
        paintedBlocks.fill(0L, from, from + WORDS_PER_TILE)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    private companion object {
        const val EMPTY: Byte = 0
        const val UNKNOWN: Byte = 1
        const val KNOWN: Byte = 2

        const val BLOCK_SIZE = WatercolorKernel.CELL_SIZE
        const val BLOCKS_PER_AXIS = TILE_SIZE / BLOCK_SIZE
        const val BLOCKS_PER_TILE = BLOCKS_PER_AXIS * BLOCKS_PER_AXIS
        const val WORD_BITS = Long.SIZE_BITS
        const val WORDS_PER_TILE = (BLOCKS_PER_TILE + WORD_BITS - 1) / WORD_BITS
        const val CHANNEL_COUNT = 4
        const val ALPHA_CHANNEL = 3
        const val BYTE_MASK = 0xff
    }
}
