package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.Composite
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TileReader
import java.io.File

/**
 * The CPU flatten (`docs/plan/06-document-and-persistence.md` §9.3): the
 * whole painting composited by `Composite` — the pinned reference the shaders
 * must match, so this and a GPU flatten produce the same pixels — over tiles
 * read from disk, one band of tile rows resident at a time.
 *
 * Output is premultiplied RGBA8, row-major, top-left origin — 03 §2.4's one
 * pixel format — so an `ARGB_8888` bitmap consumes it through
 * `copyPixelsFromBuffer` after `PixelChannelOrder`'s device-probed reorder,
 * and `Bitmap.compress` writes straight alpha itself (06 §9.1). Alpha is kept when the paper is transparent: a user who chose
 * transparent paper wants a transparent PNG.
 *
 * Step 4 uses this for **every** flatten, on-canvas syncs included; 06 §9.1's
 * GL band flatten (`CanvasRenderer.flatten`, 03 §10.4) is deferred with the
 * same reasoning as the thumbnail's (AGENTS.md): at every trigger the tiles
 * are flushed first, the pixels are identical by PLAN §7's pinning, and the
 * GL thread is never borrowed. Seconds for a huge painting on IO is the
 * accepted cost until §10.4's machinery exists.
 */
internal object CpuFlatten {

    /**
     * Flattens [document] from the tiles under [layerDirFor] into one
     * `width × height × 4` RGBA buffer. Corrupt or missing tiles composite
     * as transparent (the §4 rule); hidden layers are skipped (§9.1
     * flattens what the user sees).
     */
    fun flatten(document: Document, layerDirFor: (LayerId) -> File): ByteArray {
        val width = document.width
        val height = document.height
        val out = ByteArray(width * height * 4)
        val visible = document.stack.layers.filter { it.props.visible }
        val stores = visible.associate { it.id to TileStore(layerDirFor(it.id)) }

        // One band of tile rows at a time (§9.3): at most one row of tiles
        // per layer is resident beside the output buffer.
        for (ty in 0 until document.grid.tilesY) {
            val band = HashMap<Pair<LayerId, TileKey>, IntArray>()
            val reader = TileReader { layer, key ->
                band.getOrPut(layer to key) {
                    readTileArgb(stores.getValue(layer), key) ?: EMPTY_TILE
                }.takeIf { it !== EMPTY_TILE }
            }
            for (tx in 0 until document.grid.tilesX) {
                val key = TileKey(tx, ty)
                val pixels = Composite.tile(visible, key, document.paperColor, reader)
                writeTile(out, width, height, key, pixels)
            }
        }
        return out
    }

    /** One `.tile` as premultiplied ARGB ints, or null for empty/corrupt. */
    private fun readTileArgb(store: TileStore, key: TileKey): IntArray? {
        val bytes = (store.read(key) as? TileStore.Read.Pixels)?.pixels ?: return null
        val out = IntArray(TILE_SIZE * TILE_SIZE)
        var b = 0
        for (i in out.indices) {
            // Disk holds GL byte order (R,G,B,A per texel); Composite packs
            // 0xAARRGGBB. This pairing is the one place the two meet.
            val r = bytes[b].toInt() and 0xFF
            val g = bytes[b + 1].toInt() and 0xFF
            val bl = bytes[b + 2].toInt() and 0xFF
            val a = bytes[b + 3].toInt() and 0xFF
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or bl
            b += 4
        }
        return out
    }

    /** The inverse pairing: ARGB ints into the RGBA output at [key]'s rect. */
    private fun writeTile(out: ByteArray, width: Int, height: Int, key: TileKey, pixels: IntArray) {
        val originX = key.tx * TILE_SIZE
        val originY = key.ty * TILE_SIZE
        val copyW = minOf(TILE_SIZE, width - originX)
        val copyH = minOf(TILE_SIZE, height - originY)
        for (row in 0 until copyH) {
            var src = row * TILE_SIZE
            var dst = ((originY + row) * width + originX) * 4
            for (col in 0 until copyW) {
                val p = pixels[src]
                out[dst] = ((p shr 16) and 0xFF).toByte()
                out[dst + 1] = ((p shr 8) and 0xFF).toByte()
                out[dst + 2] = (p and 0xFF).toByte()
                out[dst + 3] = (p ushr 24).toByte()
                src += 1
                dst += 4
            }
        }
    }

    /** Sentinel so the band cache remembers "read and found nothing". */
    private val EMPTY_TILE = IntArray(0)
}
