package ch.lkmc.bangnidraw.data.shared

import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerRecord
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TracingReference
import kotlinx.serialization.Serializable

/**
 * The manifest inside a `.bangni` file.
 *
 * Deliberately *not* `ProjectFile`: that one is Android's on-disk project
 * folder and carries things that belong to a device — gallery row bookkeeping,
 * the undo journal's checkpoint state, the last tool. A document you hand to
 * another machine carries the painting and nothing else.
 *
 * Every field has a default, so a file written by a newer version still opens
 * for whatever this version understands (the same rule
 * `06-document-and-persistence.md` §13 sets for `project.json`).
 */
@Serializable
data class BangniManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val title: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val dpi: Int = Document.DEFAULT_DPI,
    /** ARGB; alpha 0 means transparent paper. */
    val paperColor: Int = OPAQUE_WHITE,
    /** Bottom to top, as [ch.lkmc.bangnidraw.engine.core.LayerStack] holds them. */
    val layers: List<LayerRecord> = emptyList(),
    val activeLayerId: String = "",
    /** `LayerStack.nextName`; 0 means "not written", and the reader re-derives it. */
    val nextLayerName: Int = 0,
    val tracingReference: BangniReferenceRecord? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        /**
         * Bumped only for a change a *reader* must know about. Adding an
         * optional field does not bump it: defaults already cover that, and a
         * bump would lock older builds out of files they can read fine.
         */
        const val FORMAT_VERSION = 1
        const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
    }
}

/** The tracing image's placement; its pixels are a PNG entry beside this. */
@Serializable
data class BangniReferenceRecord(
    val assetName: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val xx: Float = 1f,
    val xy: Float = 0f,
    val yx: Float = 0f,
    val yy: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
    val opacity: Float = TracingReference.DEFAULT_OPACITY,
    val visible: Boolean = true,
)

/**
 * One painting as it travels between machines: the manifest, the tiles, and
 * the tracing image if the painting has one.
 *
 * Tiles are premultiplied RGBA8, `PerfConstants.TILE_BYTES` each — the same
 * bytes the GPU reads back and `TileStore` writes, so neither end converts.
 */
class BangniDocument(
    val manifest: BangniManifest,
    val tiles: Map<LayerId, Map<TileKey, ByteArray>>,
    /** The tracing image's PNG bytes, when [BangniManifest.tracingReference] is set. */
    val referencePng: ByteArray? = null,
)

sealed interface BangniReadResult {
    /**
     * [warnings] name what was skipped — a corrupt tile, an entry the reader
     * does not know. A painting with one bad tile still opens
     * (`06-document-and-persistence.md` §4's rule, applied to the container).
     */
    data class Ok(val document: BangniDocument, val warnings: List<String>) : BangniReadResult

    /** The file is not a `.bangni`, or is damaged past the point of opening. */
    data class Failed(val message: String) : BangniReadResult
}
