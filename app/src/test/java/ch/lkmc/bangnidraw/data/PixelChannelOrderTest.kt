package ch.lkmc.bangnidraw.data

import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals

class PixelChannelOrderTest {

    private val rgba = byteArrayOf(
        0x11, 0x22, 0x33, 0x44,
        0x55, 0x66, 0x77, 0x7F,
    )

    @Test
    fun `little endian ARGB bitmap bytes are BGRA`() {
        val pixels = rgba.copyOf()

        PixelChannelOrder.rgbaToArgb8888InPlace(pixels, ByteOrder.LITTLE_ENDIAN)

        assertContentEquals(
            byteArrayOf(
                0x33, 0x22, 0x11, 0x44,
                0x77, 0x66, 0x55, 0x7F,
            ),
            pixels,
        )
    }

    @Test
    fun `big endian ARGB bitmap bytes are ARGB`() {
        val pixels = rgba.copyOf()

        PixelChannelOrder.rgbaToArgb8888InPlace(pixels, ByteOrder.BIG_ENDIAN)

        assertContentEquals(
            byteArrayOf(
                0x44, 0x11, 0x22, 0x33,
                0x7F, 0x55, 0x66, 0x77,
            ),
            pixels,
        )
    }

    @Test
    fun `bitmap conversion restores the reusable RGBA buffer`() {
        val pixels = rgba.copyOf()

        PixelChannelOrder.rgbaToArgb8888InPlace(pixels, ByteOrder.nativeOrder())
        PixelChannelOrder.argb8888ToRgbaInPlace(pixels, ByteOrder.nativeOrder())

        assertContentEquals(rgba, pixels)
    }
}
