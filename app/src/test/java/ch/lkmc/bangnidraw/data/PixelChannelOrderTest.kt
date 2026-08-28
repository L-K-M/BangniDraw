package ch.lkmc.bangnidraw.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class PixelChannelOrderTest {

    private val rgba = byteArrayOf(
        0x11, 0x22, 0x33, 0x44,
        0x55, 0x66, 0x77, 0x7F,
    )

    private val bgra = byteArrayOf(
        0x33, 0x22, 0x11, 0x44,
        0x77, 0x66, 0x55, 0x7F,
    )

    @Test
    fun `RGBA bitmap memory takes GL bytes unchanged`() {
        val pixels = rgba.copyOf()

        PixelChannelOrder.rgbaToArgb8888InPlace(pixels, PixelChannelOrder.Layout.RGBA)

        assertContentEquals(rgba, pixels)
    }

    @Test
    fun `BGRA bitmap memory swaps red and blue`() {
        val pixels = rgba.copyOf()

        PixelChannelOrder.rgbaToArgb8888InPlace(pixels, PixelChannelOrder.Layout.BGRA)

        assertContentEquals(bgra, pixels)
    }

    @Test
    fun `RGBA bitmap memory round trip is the identity`() {
        val pixels = rgba.copyOf()

        PixelChannelOrder.rgbaToArgb8888InPlace(pixels, PixelChannelOrder.Layout.RGBA)
        PixelChannelOrder.argb8888ToRgbaInPlace(pixels, PixelChannelOrder.Layout.RGBA)

        assertContentEquals(rgba, pixels)
    }

    @Test
    fun `BGRA bitmap memory round trip restores the reusable RGBA buffer`() {
        val pixels = rgba.copyOf()

        PixelChannelOrder.rgbaToArgb8888InPlace(pixels, PixelChannelOrder.Layout.BGRA)
        assertContentEquals(bgra, pixels)
        PixelChannelOrder.argb8888ToRgbaInPlace(pixels, PixelChannelOrder.Layout.BGRA)

        assertContentEquals(rgba, pixels)
    }

    @Test
    fun `scoped conversion restores RGBA when its block throws`() {
        val pixels = rgba.copyOf()

        assertFailsWith<IllegalStateException> {
            PixelChannelOrder.withArgb8888Bytes(pixels, PixelChannelOrder.Layout.BGRA) {
                throw IllegalStateException("expected")
            }
        }

        assertContentEquals(rgba, pixels)
    }

    @Test
    fun `incomplete texels are refused`() {
        assertFailsWith<IllegalArgumentException> {
            PixelChannelOrder.rgbaToArgb8888InPlace(
                ByteArray(5),
                PixelChannelOrder.Layout.RGBA,
            )
        }
    }
}
