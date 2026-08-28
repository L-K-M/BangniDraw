package ch.lkmc.bangnidraw.data

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Decides this device's [PixelChannelOrder.Layout] by measurement, not by
 * version assumption: fill a 1×1 `ARGB_8888` bitmap with opaque red and read
 * its memory back. `copyPixelsToBuffer` is the same memcpy as
 * `copyPixelsFromBuffer`, so byte 0 holding the red channel means the bitmap
 * stores R,G,B,A (modern Skia); byte 2 holding it means B,G,R,A (older
 * port-configured builds). Android's N32 color type is only ever those two;
 * a measurement matching neither throws rather than silently picking one.
 */
internal object BitmapLayoutProbe {

    val layout: PixelChannelOrder.Layout by lazy { probe() }

    private fun probe(): PixelChannelOrder.Layout {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(PROBE_RED)
            val bytes = ByteBuffer.allocateDirect(PIXEL_BYTES)
            bitmap.copyPixelsToBuffer(bytes)
            return classify(bytes)
        } finally {
            bitmap.recycle()
        }
    }

    /** The decision itself, pure so the JVM suite pins it. */
    internal fun classify(bytes: ByteBuffer): PixelChannelOrder.Layout {
        val redAt0 = bytes.get(RED_OFFSET) != 0.toByte()
        val redAt2 = bytes.get(BLUE_OFFSET) != 0.toByte()
        if (redAt0) return PixelChannelOrder.Layout.RGBA
        if (redAt2) return PixelChannelOrder.Layout.BGRA

        // A probe that wrote nothing would otherwise silently pick the
        // layout that reintroduces the swap this probe exists to prevent.
        error(
            "ARGB_8888 probe bytes match neither RGBA nor BGRA " +
                "(byte0=0x%02x, byte2=0x%02x)"
                    .format(bytes.get(RED_OFFSET), bytes.get(BLUE_OFFSET)),
        )
    }

    private const val PROBE_RED = 0xFFFF0000.toInt()
    private const val PIXEL_BYTES = 4
    private const val RED_OFFSET = 0
    private const val BLUE_OFFSET = 2
}
