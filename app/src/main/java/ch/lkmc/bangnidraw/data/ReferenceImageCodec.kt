package ch.lkmc.bangnidraw.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Composite
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TracingReference
import ch.lkmc.bangnidraw.engine.core.TracingReferencePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Android image decoding boundary for private tracing-reference assets. */
@Singleton
class ReferenceImageCodec @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ProjectStore,
) {
    data class Normalized(
        val width: Int,
        val height: Int,
    )

    @Throws(IOException::class)
    fun importAsset(
        projectId: String,
        assetName: String,
        uri: Uri,
        canvas: CanvasSize,
        maxPixelBytes: Long,
    ): Normalized {
        var normalized: Normalized? = null
        store.writeReferenceAsset(projectId, assetName) { output ->
            normalized = normalize(uri, canvas, maxPixelBytes, output)
        }

        return normalized ?: throw IOException("reference asset writer did not run")
    }

    fun discardAsset(projectId: String, assetName: String) {
        store.discardReferenceAsset(projectId, assetName)
    }

    private fun normalize(
        uri: Uri,
        canvas: CanvasSize,
        maxPixelBytes: Long,
        output: OutputStream,
    ): Normalized {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (width, height) = TracingReferencePolicy.normalizedSize(
                sourceWidth = info.size.width,
                sourceHeight = info.size.height,
                canvasWidth = canvas.width,
                canvasHeight = canvas.height,
                maxPixelBytes = maxPixelBytes,
            )
            if (width != info.size.width || height != info.size.height) {
                decoder.setTargetSize(width, height)
            }
            // ImageDecoder applies EXIF orientation; PNG then bakes that result.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        try {
            // PNG is lossless; Android ignores its quality hint.
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 0, output)) {
                throw IOException("reference PNG encode failed")
            }

            return Normalized(bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes the private PNG as straight (non-premultiplied) ARGB for a
     * gallery-variant flatten — the same asset `streamTiles` uploads, so a
     * variant never shows pixels the canvas cannot. Null when the asset is
     * missing or its size no longer matches the metadata; the caller treats
     * that as "no variant this round", never a failed sync of the painting.
     */
    internal fun decodeFlatReference(
        projectId: String,
        reference: TracingReference,
    ): CpuFlatten.FlatReference? {
        val file = store.referenceFile(projectId, reference.assetName)

        // Bounds before pixels: the asset is capped at import
        // (`TracingReferencePolicy.normalizedSize`), but the file is
        // app-storage, and a replaced or hand-mangled one must be refused
        // before the decode allocates, not after.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth != reference.imageWidth || bounds.outHeight != reference.imageHeight) {
            Log.w(
                TAG,
                "reference variant asset drifted: " +
                    "${bounds.outWidth}x${bounds.outHeight} vs " +
                    "${reference.imageWidth}x${reference.imageHeight}",
            )
            return null
        }

        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
        if (bitmap == null) {
            Log.w(TAG, "reference variant decode failed: $file")
            return null
        }
        try {
            if (bitmap.width != reference.imageWidth || bitmap.height != reference.imageHeight) {
                Log.w(
                    TAG,
                    "reference variant size drifted: " +
                        "${bitmap.width}x${bitmap.height} vs ${reference.imageWidth}x${reference.imageHeight}",
                )
                return null
            }
            val argb = IntArray(bitmap.width * bitmap.height)
            // getPixels un-premultiplies, which is the straight form
            // ReferenceComposite's Source expects.
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return CpuFlatten.FlatReference(reference, argb)
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes the private PNG into premultiplied RGBA tile batches. */
    fun streamTiles(
        projectId: String,
        reference: TracingReference,
        batchSize: Int,
        onBatch: (List<Pair<TileKey, ByteArray>>) -> Unit,
    ): Boolean {
        require(batchSize > 0) { "batch size must be positive" }
        val file = store.referenceFile(projectId, reference.assetName)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
        try {
            if (bitmap.width != reference.imageWidth || bitmap.height != reference.imageHeight) {
                return false
            }
            val grid = TileGrid(bitmap.width, bitmap.height)
            val batch = ArrayList<Pair<TileKey, ByteArray>>(batchSize)
            val row = IntArray(TILE_SIZE)
            for (ty in 0 until grid.tilesY) {
                for (tx in 0 until grid.tilesX) {
                    val key = TileKey(tx, ty)
                    batch += key to tilePixels(bitmap, grid, key, row)
                    if (batch.size < batchSize) continue

                    onBatch(ArrayList(batch))
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) onBatch(ArrayList(batch))

            return true
        } finally {
            bitmap.recycle()
        }
    }

    private fun tilePixels(
        bitmap: Bitmap,
        grid: TileGrid,
        key: TileKey,
        row: IntArray,
    ): ByteArray {
        val rect = grid.tileRect(key)
        val output = ByteArray(TILE_BYTES)
        for (localY in 0 until rect.height) {
            bitmap.getPixels(
                row,
                0,
                rect.width,
                rect.left,
                rect.top + localY,
                rect.width,
                1,
            )
            for (localX in 0 until rect.width) {
                val color = Composite.premultiply(row[localX])
                val offset = (localY * TILE_SIZE + localX) * CHANNELS
                output[offset] = (color ushr 16).toByte()
                output[offset + 1] = (color ushr 8).toByte()
                output[offset + 2] = color.toByte()
                output[offset + 3] = (color ushr 24).toByte()
            }
        }

        return output
    }

    private companion object {
        const val CHANNELS = 4
        const val TAG = "ReferenceImageCodec"
    }
}
