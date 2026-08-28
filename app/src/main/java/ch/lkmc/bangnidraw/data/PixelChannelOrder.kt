package ch.lkmc.bangnidraw.data

import java.nio.ByteOrder

/** Bridges GL's RGBA bytes and Android's native-order packed ARGB bitmap bytes. */
internal object PixelChannelOrder {

    fun rgbaToArgb8888InPlace(
        pixels: ByteArray,
        byteOrder: ByteOrder = ByteOrder.nativeOrder(),
    ) {
        requirePixelBuffer(pixels)
        if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            swapRedBlue(pixels)
            return
        }

        // Packed 0xAARRGGBB is A,R,G,B in big-endian memory.
        for (offset in pixels.indices step CHANNEL_COUNT) {
            val alpha = pixels[offset + ALPHA_OFFSET]
            pixels[offset + ALPHA_OFFSET] = pixels[offset + BLUE_OFFSET]
            pixels[offset + BLUE_OFFSET] = pixels[offset + GREEN_OFFSET]
            pixels[offset + GREEN_OFFSET] = pixels[offset + RED_OFFSET]
            pixels[offset + RED_OFFSET] = alpha
        }
    }

    fun argb8888ToRgbaInPlace(
        pixels: ByteArray,
        byteOrder: ByteOrder = ByteOrder.nativeOrder(),
    ) {
        requirePixelBuffer(pixels)
        if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            swapRedBlue(pixels)
            return
        }

        // Restore the caller's reusable GL-order buffer after the bitmap copy.
        for (offset in pixels.indices step CHANNEL_COUNT) {
            val alpha = pixels[offset + RED_OFFSET]
            pixels[offset + RED_OFFSET] = pixels[offset + GREEN_OFFSET]
            pixels[offset + GREEN_OFFSET] = pixels[offset + BLUE_OFFSET]
            pixels[offset + BLUE_OFFSET] = pixels[offset + ALPHA_OFFSET]
            pixels[offset + ALPHA_OFFSET] = alpha
        }
    }

    /**
     * Temporarily reorders [pixels] in place as native `ARGB_8888` bytes,
     * runs [block], then restores RGBA even when [block] throws. The block
     * must not retain the array or a view over it, such as a wrapped
     * `ByteBuffer`, because that storage returns to RGBA when the block ends.
     */
    inline fun <T> withArgb8888Bytes(pixels: ByteArray, block: (ByteArray) -> T): T {
        rgbaToArgb8888InPlace(pixels)
        return try {
            block(pixels)
        } finally {
            argb8888ToRgbaInPlace(pixels)
        }
    }

    private fun requirePixelBuffer(pixels: ByteArray) {
        require(pixels.size % CHANNEL_COUNT == 0) {
            "pixel buffer must contain complete RGBA texels"
        }
    }

    private fun swapRedBlue(pixels: ByteArray) {
        // Packed 0xAARRGGBB is B,G,R,A in little-endian memory.
        for (offset in pixels.indices step CHANNEL_COUNT) {
            val red = pixels[offset + RED_OFFSET]
            pixels[offset + RED_OFFSET] = pixels[offset + BLUE_OFFSET]
            pixels[offset + BLUE_OFFSET] = red
        }
    }

    private const val CHANNEL_COUNT = 4
    private const val RED_OFFSET = 0
    private const val GREEN_OFFSET = 1
    private const val BLUE_OFFSET = 2
    private const val ALPHA_OFFSET = 3
}
