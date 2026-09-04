package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.Composite
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TileReader

/**
 * The pixels one export composes, frozen away from the GL thread.
 *
 * [layers] are the **visible** layers, bottom to top — the caller's filter,
 * because [Composite.tile] deliberately composites whatever list it is given
 * (merge down relies on that).
 */
internal class DesktopExportSnapshot(
    val width: Int,
    val height: Int,
    val paperArgb: Int,
    val layers: List<Layer>,
    val tiles: Map<LayerId, Map<TileKey, ByteArray>>,
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
        stack: LayerStack,
        mirror: Map<LayerId, Map<TileKey, ByteArray>>,
    ): DesktopExportSnapshot {
        // Hidden layers never reach the file (`06` §9.1 exports what the user
        // sees), and a layer at zero opacity is skipped by Composite itself.
        val visible = stack.layers.filter { it.props.visible }
        return DesktopExportSnapshot(
            width = width,
            height = height,
            paperArgb = paperArgb,
            layers = visible,
            tiles = visible.associate { layer ->
                layer.id to HashMap(mirror[layer.id].orEmpty())
            },
        )
    }

    /** Composes and writes entirely on the export worker, returning failures. */
    fun export(
        snapshot: DesktopExportSnapshot,
        file: java.io.File,
    ): DesktopSaveResult = try {
        write(compose(snapshot), file)
    } catch (failure: Exception) {
        failureResult(failure)
    }

    fun failureResult(failure: Throwable): DesktopSaveResult.Failed {
        val detail = failure.message ?: failure::class.simpleName ?: "unknown error"
        return DesktopSaveResult.Failed(detail)
    }

    /**
     * The whole visible stack over the paper, through [Composite] — the same
     * CPU reference the shaders are pinned against, so an export and the
     * on-screen composite agree on blend modes and per-layer opacity.
     */
    fun compose(snapshot: DesktopExportSnapshot): java.awt.image.BufferedImage {
        require(snapshot.width > 0 && snapshot.height > 0) { "export dimensions must be positive" }

        val grid = TileGrid(snapshot.width, snapshot.height)
        // Transparent paper exports a transparent PNG, exactly as the Android
        // flatten does — a user who chose it wants the alpha, not white.
        val ground = Composite.premultiply(snapshot.paperArgb)
        val output = IntArray(snapshot.width * snapshot.height) { straight(ground) }
        // One tile of one layer at a time: the reader is called once per
        // layer per tile, and nothing but the output buffer outlives a tile.
        val reader = TileReader { layer, key -> tileArgb(snapshot.tiles[layer]?.get(key)) }
        for (ty in 0 until grid.tilesY) {
            for (tx in 0 until grid.tilesX) {
                val key = TileKey(tx, ty)
                val rect = grid.tileRect(key)
                if (rect.isEmpty) continue

                val pixels = Composite.tile(snapshot.layers, key, snapshot.paperArgb, reader)
                for (row in 0 until rect.height) {
                    var src = row * TILE_EDGE
                    var dst = (rect.top + row) * snapshot.width + rect.left
                    for (column in 0 until rect.width) {
                        // Composite works premultiplied; TYPE_INT_ARGB is
                        // straight, and PNG stores straight alpha (06 §9.1).
                        output[dst] = straight(pixels[src])
                        src += 1
                        dst += 1
                    }
                }
            }
        }

        return java.awt.image.BufferedImage(
            snapshot.width, snapshot.height, java.awt.image.BufferedImage.TYPE_INT_ARGB,
        ).also { image ->
            image.setRGB(0, 0, snapshot.width, snapshot.height, output, 0, snapshot.width)
        }
    }

    /** Premultiplied ARGB back to the straight ARGB an image file stores. */
    private fun straight(premultiplied: Int): Int {
        val alpha = (premultiplied ushr ALPHA_SHIFT) and CHANNEL_MASK
        if (alpha == CHANNEL_MASK) return premultiplied
        if (alpha == 0) return 0

        val red = unpremultiply((premultiplied ushr RED_SHIFT) and CHANNEL_MASK, alpha)
        val green = unpremultiply((premultiplied ushr GREEN_SHIFT) and CHANNEL_MASK, alpha)
        val blue = unpremultiply(premultiplied and CHANNEL_MASK, alpha)
        return (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
    }

    /** Round-half-up, clamped — the same recovery [LayerThumbnail] uses. */
    private fun unpremultiply(channel: Int, alpha: Int): Int =
        ((channel * CHANNEL_MASK + alpha / 2) / alpha).coerceAtMost(CHANNEL_MASK)

    /**
     * One mirror tile as the premultiplied ARGB ints [Composite] reads.
     * A tile of the wrong size is dropped rather than raised on: the mirror is
     * filled by GPU readback, and a truncated one must not fail a save.
     */
    private fun tileArgb(bytes: ByteArray?): IntArray? {
        if (bytes == null || bytes.size != TILE_BYTES) return null

        val out = IntArray(TILE_EDGE * TILE_EDGE)
        var offset = 0
        for (i in out.indices) {
            val red = bytes[offset].toInt() and CHANNEL_MASK
            val green = bytes[offset + 1].toInt() and CHANNEL_MASK
            val blue = bytes[offset + 2].toInt() and CHANNEL_MASK
            val alpha = bytes[offset + 3].toInt() and CHANNEL_MASK
            out[i] = (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
            offset += RGBA_CHANNELS
        }
        return out
    }

    fun write(image: java.awt.image.BufferedImage, file: java.io.File): DesktopSaveResult {
        if (Thread.currentThread().isInterrupted) {
            return DesktopSaveResult.Failed(EXPORT_INTERRUPTED_MESSAGE)
        }

        val target = file.absoluteFile
        if (target.exists() && !target.isFile) {
            return DesktopSaveResult.Failed("${target.absolutePath} is not a file")
        }

        val parent = checkNotNull(target.parentFile) { "export path has no parent" }
        if (!parent.isDirectory && !parent.mkdirs()) {
            return DesktopSaveResult.Failed("could not create ${parent.absolutePath}")
        }

        var partial: java.io.File? = null
        return try {
            partial = java.nio.file.Files.createTempFile(
                parent.toPath(), PARTIAL_PREFIX, PARTIAL_SUFFIX,
            ).toFile()
            if (!javax.imageio.ImageIO.write(image, PNG_FORMAT, partial)) {
                return DesktopSaveResult.Failed("no PNG writer is installed")
            }
            if (Thread.currentThread().isInterrupted) {
                return DesktopSaveResult.Failed(EXPORT_INTERRUPTED_MESSAGE)
            }

            publish(partial, target)
            DesktopSaveResult.Saved(target.absolutePath)
        } catch (failure: Exception) {
            failureResult(failure)
        } finally {
            partial?.let { scratch ->
                if (scratch.exists() && !scratch.delete()) scratch.deleteOnExit()
            }
        }
    }

    /** Publishes only a fully encoded PNG; interruption can leave no partial target. */
    private fun publish(partial: java.io.File, target: java.io.File) {
        try {
            java.nio.file.Files.move(
                partial.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                partial.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private const val CHANNEL_MASK = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val PNG_FORMAT = "png"
    private const val EXPORT_INTERRUPTED_MESSAGE = "export interrupted"
    private const val PARTIAL_PREFIX = ".bangnidraw-export-"
    private const val PARTIAL_SUFFIX = ".tmp"
    private const val RGBA_CHANNELS = 4
    private const val TILE_EDGE = 256
    private const val TILE_BYTES = TILE_EDGE * TILE_EDGE * RGBA_CHANNELS
}
