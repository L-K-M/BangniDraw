package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.Composite
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.ReferenceComposite
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TileReader
import ch.lkmc.bangnidraw.engine.core.TracingReference
import java.io.File

/**
 * The CPU flatten (`docs/plan/06-document-and-persistence.md` §9.3): the
 * whole painting composited by `Composite` — the pinned reference the shaders
 * must match, so this and a GPU flatten produce the same pixels — over tiles
 * read from disk, one band of tile rows resident at a time.
 *
 * Output is premultiplied RGBA8, row-major, top-left origin — 03 §2.4's one
 * pixel format — so an `ARGB_8888` bitmap consumes it byte-for-byte through
 * `copyPixelsFromBuffer` and `Bitmap.compress` writes straight alpha itself
 * (06 §9.1). Alpha is kept when the paper is transparent: a user who chose
 * transparent paper wants a transparent PNG.
 *
 * The default flatten omits the tracing reference, like every plan-era
 * export; the gallery's reference variant passes one in (AGENTS.md).
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
     * A tracing reference rendered into a flatten: the model's placement
     * plus the decoded asset as straight ARGB (`ReferenceImageCodec`'s
     * decode). Dims must equal [TracingReference.imageWidth]/[imageHeight];
     * a decode that cannot promise that returns null and no flatten sees a
     * reference at all.
     */
    internal class FlatReference(
        val reference: TracingReference,
        val argb: IntArray,
    )

    /**
     * Flattens [document] from the tiles under [layerDirFor] into one
     * `width × height × 4` RGBA buffer — without [reference], which the
     * default omits exactly as the plan's exports always have. With it, the
     * reference joins as a synthetic source-over layer *above the paper and
     * below every paint layer*, the render order
     * `CompositePass.drawReferenceToTile` pins on the GPU.
     *
     * Corrupt or missing tiles composite as transparent (the §4 rule);
     * hidden layers are skipped (§9.1 flattens what the user sees).
     */
    fun flatten(
        document: Document,
        reference: FlatReference? = null,
        layerDirFor: (LayerId) -> File,
    ): ByteArray {
        val width = document.width
        val height = document.height
        val out = ByteArray(width * height * 4)
        val visible = document.stack.layers.filter { it.props.visible }
        val stores = visible.associate { it.id to TileStore(layerDirFor(it.id)) }

        // The reference is one more layer beneath the paint: `Composite.tile`
        // blends it over the paper ground first, so opacity and blend math
        // take the same code path every paint layer takes. Its id never
        // names a directory — `stores` was built from `visible` above.
        var referenceLayer: Layer? = null
        var referenceTiles: ((TileKey) -> IntArray?)? = null
        if (reference != null) {
            val model = reference.reference
            require(reference.argb.size == model.imageWidth * model.imageHeight) {
                "reference pixels are ${reference.argb.size}, " +
                    "expected ${model.imageWidth}×${model.imageHeight}"
            }
            val covered = HashSet<TileKey>()
            for (tileY in 0 until document.grid.tilesY) {
                for (tileX in 0 until document.grid.tilesX) {
                    val key = TileKey(tileX, tileY)
                    if (ReferenceComposite.coversTile(model, document.grid.tileRect(key))) {
                        covered.add(key)
                    }
                }
            }
            referenceLayer = Layer(
                props = LayerProps(
                    id = REFERENCE_LAYER_ID,
                    name = model.assetName,
                    opacity = model.opacity,
                ),
                tiles = covered,
            )
            val source = ReferenceComposite.Source { x, y ->
                reference.argb[y * model.imageWidth + x]
            }
            referenceTiles = { key ->
                if (key in covered) {
                    ReferenceComposite.tile(model, source, key.tx * TILE_SIZE, key.ty * TILE_SIZE)
                } else {
                    null
                }
            }
        }
        val layers = if (referenceLayer != null) listOf(referenceLayer) + visible else visible

        // One band of tile rows at a time (§9.3): at most one row of tiles
        // per layer is resident beside the output buffer.
        for (ty in 0 until document.grid.tilesY) {
            val band = HashMap<Pair<LayerId, TileKey>, IntArray>()
            val referenceFor = referenceTiles
            val reader = TileReader { layer, key ->
                if (layer == REFERENCE_LAYER_ID) {
                    referenceFor?.invoke(key)
                } else {
                    band.getOrPut(layer to key) {
                        readTileArgb(stores.getValue(layer), key) ?: EMPTY_TILE
                    }.takeIf { it !== EMPTY_TILE }
                }
            }
            for (tx in 0 until document.grid.tilesX) {
                val key = TileKey(tx, ty)
                val pixels = Composite.tile(layers, key, document.paperColor, reader)
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

    /** Names the synthetic layer; never a directory — `stores` skips it. */
    private val REFERENCE_LAYER_ID = LayerId("tracing-reference")
}
