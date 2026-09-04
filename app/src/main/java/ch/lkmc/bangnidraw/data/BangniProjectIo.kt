package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.data.shared.BangniCodec
import ch.lkmc.bangnidraw.data.shared.BangniDocument
import ch.lkmc.bangnidraw.data.shared.BangniManifest
import ch.lkmc.bangnidraw.data.shared.BangniReadResult
import ch.lkmc.bangnidraw.data.shared.BangniReferenceRecord
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TracingReference
import java.io.InputStream
import java.io.OutputStream

/**
 * A painting's project folder ⇄ a `.bangni` file.
 *
 * The container itself is [BangniCodec], which both products compile, so this
 * only moves between that document and Android's own storage: `project.json`
 * plus `layers/<id>/<tx>_<ty>.tile` files.
 *
 * Neither direction touches the undo journal or the gallery bookkeeping. A
 * file you hand to another machine carries the painting; where it came from
 * and what it has been mirrored to are this device's business.
 */
internal object BangniProjectIo {

    sealed interface ImportResult {
        /** [warnings] name what the file carried that this build skipped. */
        data class Imported(val document: Document, val warnings: List<String>) : ImportResult
        data class Failed(val message: String) : ImportResult
    }

    /**
     * Writes [document]'s painting to [out], reading its tiles through
     * [layerDirFor] and its tracing image through [referenceAsset]. A tile
     * that fails to decode is skipped: an export of a partly damaged painting
     * is better than no export.
     *
     * The tracing image travels with the painting. It is private data — never
     * in a share or an export *picture* — but a `.bangni` is the painting
     * itself moving between the user's own machines, and a placement with no
     * pixels behind it would arrive as a reference the other end cannot draw.
     */
    fun export(
        document: Document,
        out: OutputStream,
        layerDirFor: (LayerId) -> java.io.File,
        referenceAsset: (TracingReference) -> ByteArray? = { null },
    ) {
        val tiles = document.stack.layers.associate { layer ->
            val store = TileStore(layerDirFor(layer.id))
            layer.id to layer.tiles.mapNotNull { key ->
                (store.read(key) as? TileStore.Read.Pixels)?.let { key to it.pixels }
            }.toMap()
        }.filterValues { it.isNotEmpty() }

        // Both or neither: the reader needs the pixels to draw the record.
        val referencePng = document.tracingReference?.let(referenceAsset)
        val reference = document.tracingReference?.takeIf { referencePng != null }

        BangniCodec.write(
            out,
            BangniDocument(
                manifest = BangniManifest(
                    title = document.title,
                    width = document.width,
                    height = document.height,
                    dpi = document.dpi,
                    paperColor = document.paperColor,
                    layers = document.stack.layers.map { it.props.toRecord() },
                    activeLayerId = document.stack.active.id.value,
                    nextLayerName = document.stack.nextName,
                    tracingReference = reference?.let(BangniReferenceRecord::of),
                    createdAt = document.createdAt,
                    updatedAt = document.updatedAt,
                ),
                tiles = tiles,
                referencePng = referencePng,
            ),
        )
    }

    /**
     * Reads [input] into a fresh [Document] under [id], writing its tiles
     * through [layerDirFor]. The caller creates the project folder from the
     * returned document; nothing here writes `project.json`.
     *
     * Layer ids are **re-minted** rather than carried over. Two devices can
     * hold the same painting, and importing it twice must produce two
     * independent projects rather than two folders quietly sharing a layer
     * directory name.
     */
    fun import(
        input: InputStream,
        id: String,
        newLayerId: () -> LayerId,
        layerDirFor: (LayerId) -> java.io.File,
        now: Long = System.currentTimeMillis(),
        /**
         * Stages the tracing image's pixels under the new project, throwing
         * [java.io.IOException] as `ProjectStore.writeReferenceAsset` does. A
         * caller with nowhere to put them leaves this out and the reference
         * is dropped rather than left pointing at nothing.
         */
        writeReferenceAsset: ((String, ByteArray) -> Unit)? = null,
    ): ImportResult {
        val read = when (val result = BangniCodec.read(input)) {
            is BangniReadResult.Failed -> return ImportResult.Failed(result.message)
            is BangniReadResult.Ok -> result
        }
        val manifest = read.document.manifest
        val warnings = read.warnings.toMutableList()
        val grid = try {
            TileGrid(manifest.width, manifest.height)
        } catch (failure: IllegalArgumentException) {
            return ImportResult.Failed(failure.message ?: "the file's canvas size is unusable")
        }

        val layers = ArrayList<Layer>(manifest.layers.size)
        for (record in manifest.layers) {
            val original = record.toPropsOrNull()
            if (original == null) {
                warnings += "skipped a layer with an unusable id: ${record.id}"
                continue
            }
            val props = original.copy(id = newLayerId())
            val tiles = read.document.tiles[original.id].orEmpty().filterKeys(grid::contains)
            val written = LinkedHashSet<TileKey>(tiles.size)
            val store = TileStore(layerDirFor(props.id))
            for ((key, pixels) in tiles) {
                store.write(key, pixels)
                written += key
            }
            layers += Layer(props, written)
        }
        if (layers.isEmpty()) return ImportResult.Failed("the file has no layers this build can read")

        val activeIndex = manifest.layers
            .indexOfFirst { it.id == manifest.activeLayerId }
            .takeIf { it in layers.indices } ?: 0
        val stack = try {
            LayerStack(
                layers = layers,
                activeIndex = activeIndex,
                // Never below the layer count: the counter only grows, and a
                // file that predates the field reports 0.
                nextName = maxOf(manifest.nextLayerName, layers.size + 1),
            )
        } catch (failure: IllegalArgumentException) {
            return ImportResult.Failed(failure.message ?: "the file's layers are inconsistent")
        }

        return ImportResult.Imported(
            Document(
                id = id,
                title = manifest.title,
                width = manifest.width,
                height = manifest.height,
                dpi = manifest.dpi,
                paperColor = manifest.paperColor,
                stack = stack,
                tracingReference = importedReference(
                    manifest.tracingReference,
                    read.document.referencePng,
                    writeReferenceAsset,
                    warnings,
                ),
                createdAt = if (manifest.createdAt > 0L) manifest.createdAt else now,
                updatedAt = now,
            ),
            warnings,
        )
    }

    /**
     * The tracing image, staged under the new project — or null with a
     * warning. Every way this can fail leaves the painting importable: an
     * unusable record, missing pixels, no place to put them, or a write that
     * failed. The asset name comes from the file, so it is a name
     * [TracingReference] itself validates before anything joins it to a path.
     */
    private fun importedReference(
        record: BangniReferenceRecord?,
        png: ByteArray?,
        writeReferenceAsset: ((String, ByteArray) -> Unit)?,
        warnings: MutableList<String>,
    ): TracingReference? {
        if (record == null) return null

        val reference = record.toReferenceOrNull()
        if (reference == null || png == null || writeReferenceAsset == null) {
            warnings += "skipped the tracing image"
            return null
        }

        return try {
            writeReferenceAsset(reference.assetName, png)
            reference
        } catch (failure: java.io.IOException) {
            warnings += "skipped the tracing image: " + failure.message
            null
        } catch (failure: IllegalArgumentException) {
            warnings += "skipped the tracing image: " + failure.message
            null
        }
    }
}
