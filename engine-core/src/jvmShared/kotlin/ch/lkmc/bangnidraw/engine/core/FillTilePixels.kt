package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE

/** Pixel source backed by full RGBA8 tiles captured from the current GPU state. */
class TiledPixelSource(
    private val grid: TileGrid,
    private val tiles: Map<TileKey, ByteArray>,
) : PixelSource {
    init {
        require(tiles.values.all { it.size == TILE_BYTES }) { "reference tiles must be RGBA8" }
    }

    override fun pixel(x: Int, y: Int): Int {
        require(x in 0 until grid.width && y in 0 until grid.height) {
            "pixel must be inside the canvas"
        }
        val pixels = tiles[grid.keyAt(x, y)] ?: return Composite.TRANSPARENT
        val offset = ((y % TILE_SIZE) * TILE_SIZE + x % TILE_SIZE) * CHANNELS
        return Composite.argb(
            pixels[offset + ALPHA].toInt() and CHANNEL_MASK,
            pixels[offset + RED].toInt() and CHANNEL_MASK,
            pixels[offset + GREEN].toInt() and CHANNEL_MASK,
            pixels[offset + BLUE].toInt() and CHANNEL_MASK,
        )
    }

    private companion object {
        const val CHANNELS = 4
        const val RED = 0
        const val GREEN = 1
        const val BLUE = 2
        const val ALPHA = 3
        const val CHANNEL_MASK = 0xFF
    }
}

/** Converts one cropped coverage mask tile to premultiplied RGBA8. */
object FillTilePixels {
    fun write(
        grid: TileGrid,
        key: TileKey,
        coverage: Coverage,
        color: Int,
        opacity: Float,
        output: ByteArray,
    ): Boolean {
        require(output.size == TILE_BYTES) { "fill upload tile must be RGBA8" }
        require(opacity.isFinite() && opacity in 0f..1f) { "fill opacity must be 0..1" }
        output.fill(0)

        val tile = grid.tileRect(key)
        val left = maxOf(tile.left, coverage.bounds.left)
        val top = maxOf(tile.top, coverage.bounds.top)
        val right = minOf(tile.right, coverage.bounds.right)
        val bottom = minOf(tile.bottom, coverage.bounds.bottom)
        if (left >= right || top >= bottom) return false

        val red = Composite.red(color)
        val green = Composite.green(color)
        val blue = Composite.blue(color)
        var hasCoverage = false
        for (y in top until bottom) {
            for (x in left until right) {
                val alpha = (coverage[x, y] * opacity + HALF).toInt().coerceIn(0, CHANNEL_MASK)
                if (alpha == 0) continue

                val offset = ((y - tile.top) * TILE_SIZE + x - tile.left) * CHANNELS
                output[offset + RED] = premultiply(red, alpha).toByte()
                output[offset + GREEN] = premultiply(green, alpha).toByte()
                output[offset + BLUE] = premultiply(blue, alpha).toByte()
                output[offset + ALPHA] = alpha.toByte()
                hasCoverage = true
            }
        }
        return hasCoverage
    }

    private fun premultiply(channel: Int, alpha: Int): Int =
        (channel * alpha + ROUNDING_BIAS) / CHANNEL_MASK

    private const val CHANNELS = 4
    private const val RED = 0
    private const val GREEN = 1
    private const val BLUE = 2
    private const val ALPHA = 3
    private const val CHANNEL_MASK = 0xFF
    private const val ROUNDING_BIAS = CHANNEL_MASK / 2
    private const val HALF = 0.5f
}
