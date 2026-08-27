package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.TileKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RmwHistoryCaptureTest {

    @Test
    fun `capture keeps first-touch order and mirror before-images`() {
        val capture = RmwHistoryCapture()
        val layer = LayerId("layer")
        val a = layer to TileKey(0, 0)
        val b = layer to TileKey(1, 0)
        capture.begin(layer)

        assertTrue(capture.touch(layer, listOf(a, b), mapOf(a to byteArrayOf(1))))
        assertTrue(capture.touch(layer, listOf(a), mapOf(a to byteArrayOf(9))))
        val snapshot = checkNotNull(capture.finish(layer))

        assertEquals(listOf(TileKey(0, 0), TileKey(1, 0)), snapshot.keys)
        assertContentEquals(byteArrayOf(1), snapshot.mirrorBefore.getValue(a))
        assertNull(capture.finish(layer))
    }

    @Test
    fun `capture rejects a touch from another layer`() {
        val capture = RmwHistoryCapture()
        capture.begin(LayerId("expected"))

        assertEquals(
            false,
            capture.touch(LayerId("other"), emptyList(), emptyMap()),
        )
    }

    @Test
    fun `reset drops a capture owned by a dead GL context`() {
        val capture = RmwHistoryCapture()
        val layer = LayerId("layer")
        capture.begin(layer)

        capture.reset()

        assertNull(capture.finish(layer))
    }
}
