package ch.lkmc.bangnidraw.engine.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LayerThumbnailTest {
    @Test
    fun `thumbnail size keeps canvas aspect`() {
        assertEquals(128 to 128, LayerThumbnail.size(CanvasSize(2048, 2048)))
        assertEquals(128 to 64, LayerThumbnail.size(CanvasSize(4096, 2048)))
        assertEquals(72 to 128, LayerThumbnail.size(CanvasSize(1080, 1920)))
    }

    @Test
    fun `converts bottom-up rgba readback to top-down argb`() {
        val rgbaBottomUp = ByteBuffer.wrap(
            byteArrayOf(
                0x20, 0x40, 0x60, 0x80.toByte(),
                0x40, 0x20, 0x10, 0x80.toByte(),
            ),
        )

        val thumbnail = LayerThumbnail.fromBottomUpRgba(1, 2, rgbaBottomUp)

        assertContentEquals(
            intArrayOf(0x80804020.toInt(), 0x804080BF.toInt()),
            thumbnail.argb,
        )
    }
}
