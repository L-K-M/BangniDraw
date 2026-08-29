package ch.lkmc.bangnidraw.data

/**
 * Bridges GL's RGBA bytes and `ARGB_8888` bitmap memory.
 *
 * `Bitmap.copyPixelsFromBuffer` is a raw memcpy, so the buffer must match the
 * bitmap's byte order exactly — and that order is **not** fixed across
 * Android versions. Skia's `kN32` color type follows `SK_R32_SHIFT`: modern
 * Android defaults it to 0 ("RGBA everywhere except BGRA on Windows"), so
 * bitmap memory is plain R,G,B,A and matches GL; older port-configured builds
 * (API 29 era) store B,G,R,A. `BitmapLayoutProbe` decides which device this
 * is; the conversion itself stays pure and testable.
 */
internal object PixelChannelOrder {

    /** How one pixel's bytes lie in this device's `ARGB_8888` bitmap memory. */
    enum class Layout { RGBA, BGRA }

    fun rgbaToArgb8888InPlace(pixels: ByteArray, layout: Layout) {
        requirePixelBuffer(pixels)
        if (layout == Layout.RGBA) return
        swapRedBlue(pixels)
    }

    fun argb8888ToRgbaInPlace(pixels: ByteArray, layout: Layout) {
        // The swap is an involution: the way back is the way there.
        rgbaToArgb8888InPlace(pixels, layout)
    }

    /**
     * Temporarily reorders [pixels] in place as this device's `ARGB_8888`
     * bytes, runs [block], then restores RGBA even when [block] throws. The
     * block must not retain the array or a view over it, such as a wrapped
     * `ByteBuffer`, because that storage returns to RGBA when the block ends.
     */
    inline fun <T> withArgb8888Bytes(
        pixels: ByteArray,
        layout: Layout,
        block: (ByteArray) -> T,
    ): T {
        rgbaToArgb8888InPlace(pixels, layout)
        return try {
            block(pixels)
        } finally {
            argb8888ToRgbaInPlace(pixels, layout)
        }
    }

    private fun requirePixelBuffer(pixels: ByteArray) {
        require(pixels.size % CHANNEL_COUNT == 0) {
            "pixel buffer must contain complete RGBA texels"
        }
    }

    private fun swapRedBlue(pixels: ByteArray) {
        for (offset in pixels.indices step CHANNEL_COUNT) {
            val red = pixels[offset + RED_OFFSET]
            pixels[offset + RED_OFFSET] = pixels[offset + BLUE_OFFSET]
            pixels[offset + BLUE_OFFSET] = red
        }
    }

    private const val CHANNEL_COUNT = 4
    private const val RED_OFFSET = 0
    private const val BLUE_OFFSET = 2
}
