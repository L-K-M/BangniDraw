package ch.lkmc.bangnidraw.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * `thumb.png` — the Studio's picture of a painting
 * (`docs/plan/06-document-and-persistence.md` §6.4): longest side
 * [LONGEST_SIDE] px, composited over the paper, alpha kept when the paper is
 * transparent (the shelf draws a checkerboard under it).
 *
 * **Deviation from 06 §6.4, recorded in AGENTS.md**: the plan renders the
 * thumbnail on the GL thread through `CompositePass` at checkpoints; this
 * writes it on IO from the flushed tiles with the platform's compositor
 * instead. At a checkpoint the tiles are on disk by construction (§5.6 step
 * 4 — `project.json`, and this file after it, come last), the pixels are
 * identical for everything v1 can produce (a NORMAL source-over stack is the
 * same arithmetic in both), and the GL thread is never borrowed mid-gesture
 * at all — 06's own worry. The GL path arrives with step 4's flatten, which
 * needs the §10.4 machinery anyway; a non-NORMAL blend (step 6) is composited
 * source-over here until then, with a log line.
 */
internal object Thumbnails {

    const val LONGEST_SIDE = 512
    private const val TAG = "Thumbnails"

    /**
     * The thumbnail's pixel size: aspect kept, longest side [longest], never
     * upscaled past 1:1, never zero. Pure, so the one decision here is
     * testable without a bitmap.
     */
    fun thumbSize(width: Int, height: Int, longest: Int = LONGEST_SIDE): Pair<Int, Int> {
        require(width > 0 && height > 0 && longest > 0)
        val scale = minOf(1f, longest.toFloat() / maxOf(width, height))
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    /**
     * Composites [document]'s flushed tiles into `thumb.png` at [target],
     * atomically. Failures are logged, never thrown: a stale thumbnail of
     * the right painting still identifies it (06 §6.4, Meltorama's rule).
     */
    fun write(document: Document, layerDirFor: (LayerId) -> File, target: File) {
        val (tw, th) = thumbSize(document.width, document.height)
        val thumb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val tile = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(thumb)
            if (!document.isPaperTransparent) {
                canvas.drawColor(document.paperColor)
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val scaleX = tw.toFloat() / document.width
            val scaleY = th.toFloat() / document.height
            var warnedBlend = false
            for (layer in document.stack.layers) {
                if (!layer.props.visible) continue
                if (layer.props.blendMode != BlendMode.NORMAL && !warnedBlend) {
                    warnedBlend = true
                    Log.w(TAG, "thumbnail approximates ${layer.props.blendMode} as source-over")
                }
                paint.alpha = (layer.props.opacity * 255f).toInt().coerceIn(0, 255)
                val store = TileStore(layerDirFor(layer.id))
                for (key in layer.tiles) {
                    val pixels = (store.read(key) as? TileStore.Read.Pixels)?.pixels ?: continue
                    // Both sides are premultiplied ARGB_8888, so the copy is
                    // byte-for-byte (03 §2.4).
                    tile.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                    val rect = document.grid.tileRect(key)
                    val src = Rect(0, 0, rect.width, rect.height)
                    val dst = RectF(
                        rect.left * scaleX,
                        rect.top * scaleY,
                        rect.right * scaleX,
                        rect.bottom * scaleY,
                    )
                    canvas.drawBitmap(tile, src, dst, paint)
                }
            }
            val out = ByteArrayOutputStream(64 * 1024)
            thumb.compress(Bitmap.CompressFormat.PNG, 100, out)
            AtomicFiles.write(target, out.toByteArray())
        } catch (e: IOException) {
            Log.w(TAG, "thumbnail write failed; keeping the previous one", e)
        } finally {
            tile.recycle()
            thumb.recycle()
        }
    }
}
