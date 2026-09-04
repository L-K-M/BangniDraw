package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.Composite
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.File

/** One decoded picture: straight ARGB, row-major, top-left origin. */
internal class DesktopImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "image dimensions must be positive" }
        require(argb.size == width * height) { "pixel count does not match the dimensions" }
    }
}

internal sealed interface DesktopOpenResult {
    data class Opened(val image: DesktopImage) : DesktopOpenResult
    data class Failed(val message: String) : DesktopOpenResult
}

/**
 * Reading a picture off the file system into the engine's tiles.
 *
 * The desktop shell is document-based rather than gallery-based: a painting
 * is a file the user opened, and PNG is the format they open and save. That
 * is a real limitation — a PNG has one layer, so saving flattens — and it is
 * the shape the product was asked for. A layered format would be a separate
 * on-disk design (`06-document-and-persistence.md`'s project folder), not a
 * variation on this one.
 */
internal object DesktopImageIo {

    /** The extensions the open dialog accepts and `save` writes. */
    const val EXTENSION = "png"

    fun read(file: File): DesktopOpenResult = try {
        val decoded = javax.imageio.ImageIO.read(file)
        when {
            decoded == null -> DesktopOpenResult.Failed("${file.name} is not an image this app can read")
            decoded.width !in Document.MIN_EDGE..Document.MAX_EDGE ||
                decoded.height !in Document.MIN_EDGE..Document.MAX_EDGE ->
                DesktopOpenResult.Failed(
                    "${decoded.width}×${decoded.height} is outside " +
                        "${Document.MIN_EDGE}–${Document.MAX_EDGE} px per side",
                )
            else -> {
                val argb = IntArray(decoded.width * decoded.height)
                decoded.getRGB(0, 0, decoded.width, decoded.height, argb, 0, decoded.width)
                DesktopOpenResult.Opened(DesktopImage(decoded.width, decoded.height, argb))
            }
        }
    } catch (failure: java.io.IOException) {
        DesktopOpenResult.Failed(failure.message ?: "the file could not be read")
    } catch (failure: RuntimeException) {
        // ImageIO's readers throw unchecked on malformed data as readily as
        // they throw IOException, and an open dialog must never take the app
        // down with it.
        DesktopOpenResult.Failed(failure.message ?: "the file could not be decoded")
    }

    /**
     * [image] as engine tiles: premultiplied RGBA bytes, 256², keyed by grid
     * position. Every tile the grid covers is produced, including the partial
     * ones at the right and bottom edges — their pixels outside the canvas
     * stay zero, which is what the renderer's own tiles hold there.
     *
     * A fully transparent tile is omitted: an empty key would cost a GPU
     * slice and a mirror entry for nothing, and `Composite` treats a missing
     * tile and a transparent one identically.
     */
    fun tiles(image: DesktopImage): Map<TileKey, ByteArray> {
        val grid = TileGrid(image.width, image.height)
        val out = LinkedHashMap<TileKey, ByteArray>()
        for (ty in 0 until grid.tilesY) {
            for (tx in 0 until grid.tilesX) {
                val key = TileKey(tx, ty)
                val rect = grid.tileRect(key)
                if (rect.isEmpty) continue

                val bytes = ByteArray(TILE_SIZE * TILE_SIZE * RGBA_CHANNELS)
                var opaque = false
                for (row in 0 until rect.height) {
                    var src = (rect.top + row) * image.width + rect.left
                    var dst = row * TILE_SIZE * RGBA_CHANNELS
                    for (column in 0 until rect.width) {
                        // A file holds straight ARGB; every tile in this
                        // engine is premultiplied (`03` §2.4).
                        val pixel = Composite.premultiply(image.argb[src])
                        bytes[dst] = ((pixel ushr RED_SHIFT) and CHANNEL_MASK).toByte()
                        bytes[dst + 1] = ((pixel ushr GREEN_SHIFT) and CHANNEL_MASK).toByte()
                        bytes[dst + 2] = (pixel and CHANNEL_MASK).toByte()
                        bytes[dst + 3] = (pixel ushr ALPHA_SHIFT).toByte()
                        if (pixel != 0) opaque = true
                        src += 1
                        dst += RGBA_CHANNELS
                    }
                }
                if (opaque) out[key] = bytes
            }
        }
        return out
    }

    private const val RGBA_CHANNELS = 4
    private const val CHANNEL_MASK = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
