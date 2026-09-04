package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.data.shared.BangniCodec
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.ReferenceTransform
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TracingReference
import ch.lkmc.bangnidraw.engine.core.TracingReferencePolicy
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File

/** A tracing image ready to place: its record, its PNG bytes, and its tiles. */
internal class DesktopReferenceImage(
    val reference: TracingReference,
    /** What a `.bangni` stores, and what a re-upload after undo decodes again. */
    val png: ByteArray,
    val tiles: Map<TileKey, ByteArray>,
)

internal sealed interface DesktopReferenceResult {
    data class Imported(val image: DesktopReferenceImage) : DesktopReferenceResult

    data class Failed(val message: String) : DesktopReferenceResult
}

/**
 * Bringing a photo in as a tracing reference.
 *
 * Two things make this its own path rather than [DesktopImageIo]'s. A
 * reference is *not* a canvas, so the document's edge limits do not apply to
 * it — a phone photo is normal input, and [TracingReferencePolicy] is what
 * bounds it, against the memory budget rather than against a document size.
 * And its pixels have to survive as a PNG: the `.bangni` container carries
 * the image itself, so the bytes are kept beside the tiles rather than
 * re-encoded from the GPU later.
 *
 * Privacy, same as `:app`: the picked file is read once and never referenced
 * again. What the document holds afterwards is this private copy.
 */
internal object DesktopReferenceIo {

    /** The extensions the import dialog offers. */
    val EXTENSIONS = listOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

    fun import(
        file: File,
        canvas: CanvasSize,
        maxPixelBytes: Long,
    ): DesktopReferenceResult = try {
        val decoded = javax.imageio.ImageIO.read(file)
        when {
            decoded == null ->
                DesktopReferenceResult.Failed("${file.name} is not an image this app can read")

            decoded.width <= 0 || decoded.height <= 0 ->
                DesktopReferenceResult.Failed("${file.name} has no pixels")

            else -> place(decoded, canvas, maxPixelBytes)
        }
    } catch (failure: java.io.IOException) {
        DesktopReferenceResult.Failed(failure.message ?: "the image could not be read")
    } catch (failure: RuntimeException) {
        // ImageIO throws unchecked on malformed data as readily as it throws
        // IOException, and an import must never take the app down with it.
        DesktopReferenceResult.Failed(failure.message ?: "the image could not be decoded")
    } catch (failure: OutOfMemoryError) {
        // A decode sized by the file's own header, before the policy gets to
        // cap anything. Refusing is the honest answer; the heap is intact.
        DesktopReferenceResult.Failed("the image is too large to open")
    }

    /**
     * The tiles for a reference already in hand — the `.bangni` path, where
     * the PNG came out of the container rather than off the file system.
     * Null when the stored bytes no longer decode to the recorded size, which
     * the caller reports as a skipped reference rather than a failed open.
     *
     * **The size is settled from the header before any raster is decoded.**
     * These bytes came out of a document someone handed the user, and
     * `ImageIO.read` allocates from the dimensions the PNG *declares*: a
     * header claiming 60000x60000 is a 14 GB `int[]` before anything gets to
     * compare it against the record. The container's own byte bound does not
     * help — a few hundred KB of PNG can declare that. So the header is read
     * first, and refused unless it both matches the record and lies inside
     * the range [TileGrid] will demand of it anyway.
     *
     * The import path deliberately does *not* do this: that file is the
     * user's own pick, a camera panorama is legitimately larger than a
     * canvas, and [TracingReferencePolicy] shrinks it — with the
     * `OutOfMemoryError` catch as the backstop.
     */
    fun tiles(png: ByteArray, reference: TracingReference): Map<TileKey, ByteArray>? = try {
        val header = headerSize(png)
        when {
            header == null -> null
            header.first != reference.imageWidth || header.second != reference.imageHeight -> null
            header.first !in TileGrid.MIN_EDGE..TileGrid.MAX_EDGE -> null
            header.second !in TileGrid.MIN_EDGE..TileGrid.MAX_EDGE -> null
            else -> javax.imageio.ImageIO.read(png.inputStream())
                ?.takeIf { it.width == reference.imageWidth && it.height == reference.imageHeight }
                ?.let { DesktopImageIo.tiles(it.toDesktopImage()) }
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /**
     * The dimensions a reader reports without decoding the raster, or null
     * when the bytes are not an image this build reads. `getWidth`/`getHeight`
     * parse the header only — that is the whole point of asking here rather
     * than reading the image and measuring it.
     */
    private fun headerSize(png: ByteArray): Pair<Int, Int>? {
        val stream = javax.imageio.ImageIO.createImageInputStream(png.inputStream()) ?: return null
        return stream.use { input ->
            val readers = javax.imageio.ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@use null

            val reader = readers.next()
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun place(
        decoded: BufferedImage,
        canvas: CanvasSize,
        maxPixelBytes: Long,
    ): DesktopReferenceResult {
        val (width, height) = TracingReferencePolicy.normalizedSize(
            sourceWidth = decoded.width,
            sourceHeight = decoded.height,
            canvasWidth = canvas.width,
            canvasHeight = canvas.height,
            maxPixelBytes = maxPixelBytes,
        )
        val scaled = scale(decoded, width, height)
        val png = encode(scaled) ?: return DesktopReferenceResult.Failed(
            "the image could not be prepared",
        )

        return DesktopReferenceResult.Imported(
            DesktopReferenceImage(
                reference = TracingReference(
                    assetName = BangniCodec.REFERENCE_ENTRY,
                    imageWidth = width,
                    imageHeight = height,
                    // Fitted and centred on the canvas, as `:app` opens it.
                    transform = ReferenceTransform.fit(width, height, canvas.width, canvas.height),
                ),
                png = png,
                tiles = DesktopImageIo.tiles(scaled.toDesktopImage()),
            ),
        )
    }

    /**
     * Draws [source] into an ARGB image of the requested size. `TYPE_INT_ARGB`
     * rather than the source's own type on purpose: an indexed or grayscale
     * source would otherwise quantize the interpolated pixels back into its
     * own palette, and a JPEG's opaque type would drop a PNG's alpha.
     */
    private fun scale(source: BufferedImage, width: Int, height: Int): BufferedImage {
        if (source.width == width && source.height == height && source.type == BufferedImage.TYPE_INT_ARGB) {
            return source
        }

        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            graphics.setRenderingHint(
                java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY,
            )
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encode(image: BufferedImage): ByteArray? = try {
        val out = ByteArrayOutputStream()
        if (javax.imageio.ImageIO.write(image, "png", out)) out.toByteArray() else null
    } catch (_: java.io.IOException) {
        null
    }

    /** Straight ARGB, row-major — what [DesktopImageIo.tiles] premultiplies. */
    private fun BufferedImage.toDesktopImage(): DesktopImage {
        val pixels = IntArray(width * height)
        getRGB(0, 0, width, height, pixels, 0, width)
        return DesktopImage(width, height, pixels)
    }
}
