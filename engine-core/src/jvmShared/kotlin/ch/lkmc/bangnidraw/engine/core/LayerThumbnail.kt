package ch.lkmc.bangnidraw.engine.core

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Top-down straight ARGB pixels ready for `Bitmap.createBitmap`. */
class LayerThumbnail(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0)
        require(argb.size == width * height)
    }

    companion object {
        const val LONGEST_SIDE = 128

        fun size(canvas: CanvasSize): Pair<Int, Int> {
            if (canvas.width >= canvas.height) {
                val height = (canvas.height.toFloat() / canvas.width * LONGEST_SIDE)
                    .roundToInt()
                    .coerceAtLeast(1)
                return LONGEST_SIDE to height
            }

            val width = (canvas.width.toFloat() / canvas.height * LONGEST_SIDE)
                .roundToInt()
                .coerceAtLeast(1)
            return width to LONGEST_SIDE
        }

        /** OpenGL rows start at the bottom; Android bitmaps start at the top. */
        fun fromBottomUpRgba(width: Int, height: Int, rgba: ByteBuffer): LayerThumbnail {
            require(rgba.remaining() >= width * height * CHANNELS)
            val start = rgba.position()
            val argb = IntArray(width * height)
            for (y in 0 until height) {
                val sourceY = height - y - 1
                for (x in 0 until width) {
                    val source = start + (sourceY * width + x) * CHANNELS
                    val premultipliedRed = rgba.get(source).toInt() and CHANNEL_MASK
                    val premultipliedGreen = rgba.get(source + 1).toInt() and CHANNEL_MASK
                    val premultipliedBlue = rgba.get(source + 2).toInt() and CHANNEL_MASK
                    val alpha = rgba.get(source + 3).toInt() and CHANNEL_MASK
                    val red = unpremultiply(premultipliedRed, alpha)
                    val green = unpremultiply(premultipliedGreen, alpha)
                    val blue = unpremultiply(premultipliedBlue, alpha)
                    argb[y * width + x] =
                        (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or
                            (green shl GREEN_SHIFT) or blue
                }
            }
            return LayerThumbnail(width, height, argb)
        }

        private fun unpremultiply(channel: Int, alpha: Int): Int {
            if (alpha == 0) return 0

            return ((channel * CHANNEL_MASK + alpha / 2) / alpha).coerceAtMost(CHANNEL_MASK)
        }

        private const val CHANNELS = 4
        private const val CHANNEL_MASK = 0xFF
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
    }
}
