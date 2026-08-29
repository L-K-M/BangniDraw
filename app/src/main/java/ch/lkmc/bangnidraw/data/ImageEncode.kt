package ch.lkmc.bangnidraw.data

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Premultiplied RGBA (a `CpuFlatten` result) → encoded image bytes
 * (`docs/plan/06-document-and-persistence.md` §9.1): the bytes go into an
 * `ARGB_8888` bitmap whose memory layout is probed per device — modern Skia
 * stores R,G,B,A exactly like GL, older builds store B,G,R,A, so
 * [PixelChannelOrder] reorders around the bitmap memcpy when they differ;
 * `Bitmap.compress` then writes straight alpha itself.
 */
object ImageEncode {

    enum class Format(val extension: String, val mimeType: String) {
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg"),
    }

    /**
     * Encodes [rgba] (`width × height × 4`, premultiplied). JPEG carries no
     * alpha, so a transparent painting is matted over white first — the
     * honest reading of "share as JPEG"; PNG keeps the alpha the flatten
     * kept.
     */
    fun encode(rgba: ByteArray, width: Int, height: Int, format: Format, quality: Int = 100): ByteArray {
        require(rgba.size == width * height * 4)
        var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelChannelOrder.withArgb8888Bytes(rgba, BitmapLayoutProbe.layout) { pixels ->
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            }
            if (format == Format.JPEG) {
                val matted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(matted)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                bitmap.recycle()
                bitmap = matted
            }
            val out = ByteArrayOutputStream(1 shl 20)
            bitmap.compress(
                if (format == Format.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                quality,
                out,
            )
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
