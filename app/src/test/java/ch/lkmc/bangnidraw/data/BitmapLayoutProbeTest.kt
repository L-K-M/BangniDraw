package ch.lkmc.bangnidraw.data

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class BitmapLayoutProbeTest {

    @Test
    fun `red at byte 0 is RGBA memory`() {
        val bytes = ByteBuffer.wrap(byteArrayOf(0xFF.toByte(), 0, 0, 0xFF.toByte()))

        assertEquals(PixelChannelOrder.Layout.RGBA, BitmapLayoutProbe.classify(bytes))
    }

    @Test
    fun `red at byte 2 is BGRA memory`() {
        val bytes = ByteBuffer.wrap(byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte()))

        assertEquals(PixelChannelOrder.Layout.BGRA, BitmapLayoutProbe.classify(bytes))
    }
}
