package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.data.shared.BangniCodec
import ch.lkmc.bangnidraw.data.shared.BangniDocument
import ch.lkmc.bangnidraw.data.shared.BangniManifest
import ch.lkmc.bangnidraw.data.shared.BangniReadResult
import ch.lkmc.bangnidraw.data.shared.BangniReferenceRecord
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerProps
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TracingReference
import java.io.File

/** A painting as a new document opens with it: the model plus its pixels. */
internal class DesktopInitialContent(
    val canvas: CanvasSize,
    val stack: LayerStack,
    val tiles: Map<LayerId, Map<TileKey, ByteArray>>,
    val paperArgb: Int,
    val title: String = "",
    /** Preserved across saves so a document keeps one creation date. */
    val createdAt: Long = 0L,
    /** The tracing image the file carried, with the pixels it was stored as. */
    val reference: TracingReference? = null,
    val referencePng: ByteArray? = null,
)

internal sealed interface DesktopOpenResult {
    data class Opened(
        val content: DesktopInitialContent,
        /** What the file carried that this build skipped; empty is the norm. */
        val warnings: List<String> = emptyList(),
    ) : DesktopOpenResult

    data class Failed(val message: String) : DesktopOpenResult
}

/**
 * Opening and saving a painting.
 *
 * Two formats, on purpose:
 *
 * - **`.bangni`** keeps the whole document — every layer with its props, the
 *   paper colour, the layer-name counter. It is a ZIP written by
 *   [BangniCodec], which both products compile, so a file written on a phone
 *   opens on a laptop and back.
 * - **PNG** is interchange. It holds one layer, so opening one gives a
 *   painting with one layer and saving one flattens the stack. That is the
 *   format's own limit, not a shortcut here.
 *
 * The format is chosen by extension when saving — the user picked it — and by
 * *content* when opening, so a `.bangni` someone renamed still opens.
 */
internal object DesktopDocumentIo {

    val OPENABLE_EXTENSIONS = listOf(BangniCodec.EXTENSION, DesktopImageIo.EXTENSION, "jpg", "jpeg")

    fun isBangni(file: File): Boolean =
        file.extension.equals(BangniCodec.EXTENSION, ignoreCase = true)

    fun read(file: File): DesktopOpenResult {
        // Content, not the name: a `.bangni` that lost its extension in a
        // download still opens, and a PNG named `.bangni` does not silently
        // fail the zip parse.
        if (looksLikeZip(file)) return readBangni(file)

        return when (val image = DesktopImageIo.read(file)) {
            is DesktopImageResult.Failed -> DesktopOpenResult.Failed(image.message)
            is DesktopImageResult.Opened -> DesktopOpenResult.Opened(fromImage(image.image))
        }
    }

    /** The whole document, ready for [BangniCodec.write]. */
    fun snapshot(
        title: String,
        canvas: CanvasSize,
        paperArgb: Int,
        stack: LayerStack,
        mirror: Map<LayerId, Map<TileKey, ByteArray>>,
        createdAt: Long,
        updatedAt: Long,
        reference: TracingReference? = null,
        referencePng: ByteArray? = null,
    ): BangniDocument = BangniDocument(
        manifest = BangniManifest(
            title = title,
            width = canvas.width,
            height = canvas.height,
            paperColor = paperArgb,
            layers = stack.layers.map { it.props.toRecord() },
            activeLayerId = stack.active.id.value,
            nextLayerName = stack.nextName,
            // Both or neither: a record with no pixels beside it would open
            // as a reference the reader cannot draw.
            tracingReference = reference
                ?.takeIf { referencePng != null }
                ?.let(BangniReferenceRecord::of),
            createdAt = createdAt,
            updatedAt = updatedAt,
        ),
        // Only what the model still lists: the mirror can hold a layer's tiles
        // for a moment after an edit drops it, and a file must not.
        tiles = stack.layers.associate { layer ->
            layer.id to (mirror[layer.id]?.filterKeys { it in layer.tiles } ?: emptyMap())
        }.filterValues { it.isNotEmpty() },
        referencePng = referencePng?.takeIf { reference != null },
    )

    private fun readBangni(file: File): DesktopOpenResult = try {
        when (val result = file.inputStream().buffered().use(BangniCodec::read)) {
            is BangniReadResult.Failed -> DesktopOpenResult.Failed(result.message)
            is BangniReadResult.Ok -> toContent(result)
        }
    } catch (failure: java.io.IOException) {
        DesktopOpenResult.Failed(failure.message ?: "the file could not be read")
    }

    private fun toContent(result: BangniReadResult.Ok): DesktopOpenResult {
        val manifest = result.document.manifest
        val warnings = result.warnings.toMutableList()
        val layers = ArrayList<Layer>(manifest.layers.size)
        for (record in manifest.layers) {
            val props = record.toPropsOrNull()
            if (props == null) {
                // One unusable record is a skipped layer, not a failed open
                // (`06-document-and-persistence.md` §4).
                warnings += "skipped a layer with an unusable id: ${record.id}"
                continue
            }
            layers += Layer(props, result.document.tiles[props.id]?.keys.orEmpty().toSet())
        }
        if (layers.isEmpty()) return DesktopOpenResult.Failed("the file lists no usable layers")

        val activeIndex = layers.indexOfFirst { it.id.value == manifest.activeLayerId }
            .takeIf { it >= 0 } ?: 0
        val stack = try {
            LayerStack(
                layers = layers,
                activeIndex = activeIndex,
                // The counter only ever grows; a file that predates the field
                // (or lies) must not reissue a name a layer already holds.
                nextName = maxOf(manifest.nextLayerName, layers.size + 1),
            )
        } catch (failure: IllegalArgumentException) {
            return DesktopOpenResult.Failed(failure.message ?: "the file's layers are inconsistent")
        }

        // A reference whose record or pixels do not survive the trip is a
        // skipped aid, never a failed open: the painting is the document.
        val referencePng = result.document.referencePng
        val reference = manifest.tracingReference
            ?.toReferenceOrNull()
            ?.takeIf { referencePng != null }
        if (manifest.tracingReference != null && reference == null) {
            warnings += "skipped an unreadable tracing image"
        }

        return DesktopOpenResult.Opened(
            DesktopInitialContent(
                canvas = CanvasSize(manifest.width, manifest.height),
                stack = stack,
                tiles = result.document.tiles,
                paperArgb = manifest.paperColor,
                title = manifest.title,
                createdAt = manifest.createdAt,
                reference = reference,
                referencePng = referencePng?.takeIf { reference != null },
            ),
            warnings,
        )
    }

    /** A flat picture becomes a one-layer painting on opaque white paper. */
    private fun fromImage(image: DesktopImage): DesktopInitialContent {
        val tiles = DesktopImageIo.tiles(image)
        val id = LayerId("layer-1")
        return DesktopInitialContent(
            canvas = CanvasSize(image.width, image.height),
            stack = LayerStack(
                layers = listOf(Layer(LayerProps(id, LayerStack.defaultName(1)), tiles.keys.toSet())),
                activeIndex = 0,
                nextName = 2,
            ),
            tiles = mapOf(id to tiles),
            paperArgb = BangniManifest.OPAQUE_WHITE,
        )
    }

    /** The local file header's signature; enough to tell a container apart. */
    private fun looksLikeZip(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val header = ByteArray(ZIP_MAGIC.size)
            stream.read(header) == header.size && header.contentEquals(ZIP_MAGIC)
        }
    } catch (_: java.io.IOException) {
        false
    }

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
}
