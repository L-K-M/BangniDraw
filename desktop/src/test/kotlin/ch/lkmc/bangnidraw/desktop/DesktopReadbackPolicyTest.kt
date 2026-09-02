package ch.lkmc.bangnidraw.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopReadbackPolicyTest {

    @Test
    fun `drain completes immediately when no chunk remains`() {
        var calls = 0

        val result = DesktopReadbackPolicy.drain {
            calls += 1
            0
        }

        assertEquals(ReadbackDrain.Complete, result)
        assertEquals(1, calls)
    }

    @Test
    fun `drain retries a bounded fence timeout`() {
        var calls = 0

        val result = DesktopReadbackPolicy.drain {
            calls += 1
            if (calls < 3) 1 else 0
        }

        assertEquals(ReadbackDrain.Complete, result)
        assertEquals(3, calls)
    }

    @Test
    fun `drain reports a persistent timeout after its bound`() {
        var calls = 0

        val result = DesktopReadbackPolicy.drain {
            calls += 1
            1
        }

        assertEquals(ReadbackDrain.TimedOut, result)
        assertEquals(DesktopReadbackPolicy.MAX_ATTEMPTS, calls)
    }

    @Test
    fun `revision coverage rejects missing or stale tiles`() {
        val first = ch.lkmc.bangnidraw.engine.core.TileKey(1)
        val second = ch.lkmc.bangnidraw.engine.core.TileKey(2)
        val revisions = mapOf(first to 7, second to 6)

        assertEquals(
            ReadbackDelivery.Complete,
            DesktopReadbackPolicy.delivery(listOf(first), expectedRevision = 7, revisions::get),
        )
        assertEquals(
            ReadbackDelivery.Incomplete,
            DesktopReadbackPolicy.delivery(listOf(first, second), expectedRevision = 7, revisions::get),
        )
        assertEquals(
            ReadbackDelivery.Incomplete,
            DesktopReadbackPolicy.delivery(listOf(first, ch.lkmc.bangnidraw.engine.core.TileKey(3)), 7, revisions::get),
        )
    }
}
