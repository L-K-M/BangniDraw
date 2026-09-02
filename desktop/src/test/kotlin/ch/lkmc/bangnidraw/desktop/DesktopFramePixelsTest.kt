package ch.lkmc.bangnidraw.desktop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotSame

class DesktopFramePixelsTest {

    @Test
    fun `renderer row order reaches Compose unchanged`() {
        // The renderer contract defines row zero as canvas top. A second
        // desktop flip would invert this asymmetric two-row frame.
        val topThenBottom = byteArrayOf(
            1, 2, 3, 4,
            5, 6, 7, 8,
        )

        val published = DesktopFramePixels.copyForCompose(topThenBottom)

        assertContentEquals(topThenBottom, published)
        assertNotSame(topThenBottom, published)
    }
}
