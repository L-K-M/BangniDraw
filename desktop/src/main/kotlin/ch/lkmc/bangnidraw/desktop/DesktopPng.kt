package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey

internal class DesktopExportSnapshot(
    val width: Int,
    val height: Int,
    val paperArgb: Int,
    val tiles: Map<TileKey, ByteArray>,
)

internal sealed interface DesktopSaveResult {
    data class Saved(val path: String) : DesktopSaveResult
    data class Failed(val message: String) : DesktopSaveResult
}

/** Pixel math for exporting premultiplied engine tiles over opaque paper. */
internal object DesktopPng {

    /**
     * Freezes the GL-thread mirror's selected tile versions without copying
     * their immutable byte arrays. Mirror updates replace arrays rather than
     * mutating them, so the worker can safely encode these retained versions.
     */
    fun snapshot(
        width: Int,
        height: Int,
        paperArgb: Int,
        tiles: Map<TileKey, ByteArray>,
    ): DesktopExportSnapshot = DesktopExportSnapshot(
        width = width,
        height = height,
        paperArgb = paperArgb,
        tiles = HashMap(tiles),
    )

    /** Composes and writes entirely on the export worker, returning failures. */
    fun export(
        snapshot: DesktopExportSnapshot,
        file: java.io.File,
    ): DesktopSaveResult = try {
        write(compose(snapshot), file)
    } catch (failure: Exception) {
        failureResult(failure)
    }

    fun failureResult(failure: Exception): DesktopSaveResult.Failed {
        val detail = failure.message ?: failure::class.simpleName ?: "unknown error"
        return DesktopSaveResult.Failed(detail)
    }

    fun compose(snapshot: DesktopExportSnapshot): java.awt.image.BufferedImage {
        require(snapshot.width > 0 && snapshot.height > 0) { "export dimensions must be positive" }

        val grid = TileGrid(snapshot.width, snapshot.height)
        val output = IntArray(snapshot.width * snapshot.height) { snapshot.paperArgb }
        for ((key, bytes) in snapshot.tiles) {
            if (bytes.size != TILE_BYTES) continue
            val rect = grid.tileRect(key)
            if (rect.isEmpty) continue

            for (row in 0 until rect.height) {
                for (column in 0 until rect.width) {
                    val offset = (row * TILE_EDGE + column) * RGBA_CHANNELS
                    val red = bytes[offset].toInt() and CHANNEL_MASK
                    val green = bytes[offset + 1].toInt() and CHANNEL_MASK
                    val blue = bytes[offset + 2].toInt() and CHANNEL_MASK
                    val alpha = bytes[offset + 3].toInt() and CHANNEL_MASK
                    val premultiplied =
                        (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or
                            (green shl GREEN_SHIFT) or blue
                    val x = rect.left + column
                    val y = rect.top + row
                    output[y * snapshot.width + x] = sourceOver(premultiplied, snapshot.paperArgb)
                }
            }
        }

        return java.awt.image.BufferedImage(
            snapshot.width, snapshot.height, java.awt.image.BufferedImage.TYPE_INT_ARGB,
        ).also { image ->
            image.setRGB(0, 0, snapshot.width, snapshot.height, output, 0, snapshot.width)
        }
    }

    fun write(image: java.awt.image.BufferedImage, file: java.io.File): DesktopSaveResult {
        if (file.exists() && !file.isFile) {
            return DesktopSaveResult.Failed("${file.absolutePath} is not a file")
        }

        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            return DesktopSaveResult.Failed("could not create ${parent.absolutePath}")
        }

        return try {
            if (!javax.imageio.ImageIO.write(image, PNG_FORMAT, file)) {
                DesktopSaveResult.Failed("no PNG writer is installed")
            } else {
                DesktopSaveResult.Saved(file.absolutePath)
            }
        } catch (failure: Exception) {
            failureResult(failure)
        }
    }

    fun sourceOver(premultipliedTile: Int, opaquePaper: Int): Int {
        val alpha = (premultipliedTile ushr ALPHA_SHIFT) and CHANNEL_MASK
        if (alpha == CHANNEL_MASK) return premultipliedTile
        if (alpha == 0) return opaquePaper

        val inverseAlpha = CHANNEL_MASK - alpha
        val red = channel(premultipliedTile, RED_SHIFT) +
            channel(opaquePaper, RED_SHIFT) * inverseAlpha / CHANNEL_MASK
        val green = channel(premultipliedTile, GREEN_SHIFT) +
            channel(opaquePaper, GREEN_SHIFT) * inverseAlpha / CHANNEL_MASK
        val blue = channel(premultipliedTile, 0) +
            channel(opaquePaper, 0) * inverseAlpha / CHANNEL_MASK

        // Paper is opaque, therefore its source-over result is opaque too.
        return OPAQUE_ALPHA or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
    }

    private fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and CHANNEL_MASK

    private const val CHANNEL_MASK = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val OPAQUE_ALPHA = 0xFF000000.toInt()
    private const val PNG_FORMAT = "png"
    private const val RGBA_CHANNELS = 4
    private const val TILE_EDGE = 256
    private const val TILE_BYTES = TILE_EDGE * TILE_EDGE * RGBA_CHANNELS
}
