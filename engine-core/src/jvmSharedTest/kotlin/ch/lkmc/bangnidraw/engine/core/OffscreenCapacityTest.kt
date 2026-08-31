package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class OffscreenCapacityTest {

    @Test
    fun `pressure ramp allocates only at a new high water mark`() {
        val requests = listOf(
            40 to 60,
            80 to 96,
            72 to 88,
            48 to 64,
            80 to 96,
        )
        var capacity = OffscreenCapacity.EMPTY
        val allocations = ArrayList<OffscreenCapacity>()

        for ((width, height) in requests) {
            val next = capacity.growTo(width, height)
            if (next != capacity) allocations += next
            capacity = next
        }

        assertEquals(
            listOf(
                OffscreenCapacity(40, 60),
                OffscreenCapacity(80, 96),
            ),
            allocations,
        )
    }

    @Test
    fun `growth retains independent width and height high water marks`() {
        val wide = OffscreenCapacity(96, 40)
        val grown = wide.growTo(48, 80)

        assertEquals(OffscreenCapacity(96, 80), grown)
        assertSame(grown, grown.growTo(64, 64))
    }

    @Test
    fun `RGBA8 bytes describe retained capacity`() {
        assertEquals(0L, OffscreenCapacity.EMPTY.rgba8Bytes)
        assertEquals(
            96L * 80L * RGBA8_BYTES_PER_PIXEL,
            OffscreenCapacity(96, 80).rgba8Bytes,
        )
    }

    @Test
    fun `invalid dimensions are refused`() {
        assertFailsWith<IllegalArgumentException> { OffscreenCapacity(-1, 0) }
        assertFailsWith<IllegalArgumentException> { OffscreenCapacity.EMPTY.growTo(0, 1) }
    }

    private companion object {
        const val RGBA8_BYTES_PER_PIXEL = 4L
    }
}
