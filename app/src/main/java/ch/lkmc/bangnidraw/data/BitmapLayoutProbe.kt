package ch.lkmc.bangnidraw.data

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Decides this device's [PixelChannelOrder.Layout] by measurement, not by
 * version assumption: fill a 1×1 `ARGB_8888` bitmap with opaque red and read
 * its memory back. `copyPixelsToBuffer` is the same memcpy as
 * `copyPixelsFromBuffer`, so byte 0 holding the red channel means the bitmap
 * stores R,G,B,A (modern Skia); byte 2 holding it means B,G,R,A (older
 * port-configured builds). Anything else is treated as BGRA — the layout the
 * channel-order fix was written for — and logged nowhere because the probe
 * cannot fail without throwing.
 */
internal object BitmapLayoutProbe {

    val layout: PixelChannelOrder.Layout by lazy { probe() }

    private fun probe(): PixelChannelOrder.Layout {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(PROBE_RED)
            val bytes = ByteBuffer.allocateDirect(PIXEL_BYTES)
            bitmap.copyPixelsToBuffer(bytes)
            return if (bytes.get(RED_OFFSET) != 0.toByte()) {
                PixelChannelOrder.Layout.RGBA
            } else {
                PixelChannelOrder.Layout.BGRA
            }
        } finally {
            bitmap.recycle()
        }
    }

    private const val PROBE_RED = 0xFFFF0000.toInt()
    private const val PIXEL_BYTES = 4
    private const val RED_OFFSET = 0
}
