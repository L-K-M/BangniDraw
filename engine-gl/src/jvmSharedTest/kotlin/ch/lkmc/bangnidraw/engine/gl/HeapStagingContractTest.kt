package ch.lkmc.bangnidraw.engine.gl.platform

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ANALYSIS.md D11's contract for the desktop facade's buffer adaptation,
 * pinnable with no GL context: heap buffers the shared engine may legally
 * hand the facade must cross into LWJGL through **direct** memory, contents
 * intact and the caller's buffer state restored — because LWJGL passes the
 * raw address of whatever it receives, and a heap buffer's is a garbage
 * pointer into the zero page.
 */
class HeapStagingContractTest {

    @Test
    fun `a direct buffer passes through untouched`() {
        val direct = ByteBuffer.allocateDirect(16).put(ByteArray(16) { it.toByte() }).flip() as ByteBuffer

        assertTrue(directOrStaged(direct) === direct, "the engine's direct hot paths must not pay a copy")
    }

    @Test
    fun `a heap buffer is staged into direct memory with its bytes`() {
        val bytes = ByteArray(24) { (it * 7).toByte() }
        val heap = ByteBuffer.wrap(bytes)
        heap.position(4)

        val staged = directOrStaged(heap)

        assertTrue(staged.isDirect, "LWJGL receives the raw address; heap memory faults")
        assertEquals(20, staged.remaining(), "exactly the bytes from the caller's position")
        for (i in 0 until 20) {
            assertEquals(bytes[4 + i], staged.get(i), "byte $i must survive the stage")
        }
        assertEquals(4, heap.position(), "the caller's buffer position is restored")
    }

    @Test
    fun `a heap float buffer stages as direct bytes`() {
        val floats = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val heap = java.nio.FloatBuffer.wrap(floats)

        val staged = directOrStaged(heap, 3 * 4)

        assertTrue(staged.isDirect)
        val view = staged.asFloatBuffer()
        assertEquals(1f, view.get(0))
        assertEquals(2f, view.get(1))
        assertEquals(3f, view.get(2))
    }

    @Test
    fun `staging grows monotonically and is rewound between uses`() {
        val first = GLES30.stagingFor(32)
        assertEquals(32, first.remaining(), "a fresh stage holds exactly what was asked")

        first.putInt(0x0BADC0DE)

        val second = GLES30.stagingFor(16)
        assertEquals(16, second.remaining())
        assertTrue(second === first || second.capacity() >= 16, "smaller requests reuse the same stage")
        assertEquals(0, second.position(), "a reused stage starts rewound — cleared in the buffer sense")
    }

    @Test
    fun `absolute native writes copy back without advancing the staging position`() {
        val destinationBytes = ByteArray(DESTINATION_OFFSET + READBACK_BYTES + 1) { UNTOUCHED_BYTE }
        val destination = ByteBuffer.wrap(destinationBytes).apply {
            position(DESTINATION_OFFSET)
            limit(DESTINATION_OFFSET + READBACK_BYTES)
        }
        val staged = GLES30.stagingFor(READBACK_BYTES)

        // Native GL writes through the address; it does not advance the Java
        // buffer position as a relative ByteBuffer.put would.
        for (i in 0 until READBACK_BYTES) staged.put(i, (FIRST_PIXEL_BYTE + i).toByte())
        assertEquals(0, staged.position(), "the simulated native write is absolute")

        GLES30.copyStagedReadback(staged, destination, READBACK_BYTES)

        assertEquals(DESTINATION_OFFSET + READBACK_BYTES, destination.position())
        assertEquals(0, staged.position(), "copyback preserves the reused staging buffer state")
        for (i in 0 until READBACK_BYTES) {
            assertEquals(
                (FIRST_PIXEL_BYTE + i).toByte(),
                destinationBytes[DESTINATION_OFFSET + i],
                "readback byte $i",
            )
        }
        assertEquals(UNTOUCHED_BYTE, destinationBytes[DESTINATION_OFFSET - 1])
        assertEquals(UNTOUCHED_BYTE, destinationBytes[DESTINATION_OFFSET + READBACK_BYTES])
    }

    private companion object {
        const val DESTINATION_OFFSET = 2
        const val READBACK_BYTES = 8
        const val FIRST_PIXEL_BYTE = 17
        const val UNTOUCHED_BYTE: Byte = 0x55
    }
}

/** Reaches the desktop actual's internal staging seam without a GL context. */
private fun directOrStaged(source: ByteBuffer): ByteBuffer = GLES30.directOrStaged(source)

private fun directOrStaged(source: java.nio.FloatBuffer, sizeBytes: Int): ByteBuffer =
    GLES30.directOrStaged(source, sizeBytes)
