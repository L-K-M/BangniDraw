package ch.lkmc.bangnidraw.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The one pure decision in the thumbnail writer: its pixel size
 * (06 §6.4 — longest side 512, aspect kept). The compositing itself is
 * platform Bitmap code, device-gated like the rest of the screens.
 */
class ThumbnailsTest {

    @Test
    fun `the longest side is 512 and the aspect is kept`() {
        assertEquals(512 to 512, Thumbnails.thumbSize(2048, 2048))
        assertEquals(512 to 256, Thumbnails.thumbSize(4096, 2048))
        assertEquals(288 to 512, Thumbnails.thumbSize(1080, 1920))
    }

    @Test
    fun `a painting smaller than the thumbnail is never upscaled`() {
        assertEquals(256 to 300, Thumbnails.thumbSize(256, 300))
    }

    @Test
    fun `an extreme aspect never collapses to zero`() {
        assertEquals(512 to 16, Thumbnails.thumbSize(8192, 256))
        assertEquals(1 to 512, Thumbnails.thumbSize(1, 100_000, longest = 512))
    }

    @Test
    fun `nonsense sizes are refused`() {
        assertFailsWith<IllegalArgumentException> { Thumbnails.thumbSize(0, 100) }
    }
}
