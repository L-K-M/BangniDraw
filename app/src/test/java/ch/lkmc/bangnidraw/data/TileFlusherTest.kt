package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants.CPU_MIRROR_CAP_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §5's `TileFlusherTest`.
 *
 * No worker coroutine: the tests drive `flushAll` directly, so every
 * assertion is deterministic — the worker the production wiring starts is
 * one `wake → drain` loop over the same code.
 */
class TileFlusherTest {

    private class FakeWriter : TileFlusher.TileWriter {
        var fail = false
        val written = ArrayList<Pair<TileKey, ByteArray>>()

        override fun write(layer: LayerId, key: TileKey, pixels: ByteArray) {
            if (fail) throw IOException("disk full")
            written += key to pixels
        }
    }

    private val writer = FakeWriter()
    private val flusher = TileFlusher(writer)
    private val layer = LayerId("layer-a")

    private fun tile(key: TileKey, revision: Int = 1, fill: Byte = 1) =
        CpuTile(layer, key, revision, ByteArray(TILE_BYTES) { fill })

    @Test
    fun `storage full lifts the mirror cap and keeps committing`() = runBlocking {
        writer.fail = true

        // Fill the mirror past CPU_MIRROR_CAP_BYTES while every write fails.
        // One shared pixel buffer keeps the test's footprint sane; the flusher
        // never copies, so identity aliasing is fine here.
        val shared = ByteArray(TILE_BYTES) { 7 }
        val tilesOverCap = (CPU_MIRROR_CAP_BYTES / TILE_BYTES).toInt() + 1
        for (i in 0 until tilesOverCap) {
            assertTrue(flusher.markDirty(CpuTile(layer, TileKey(i % 64, i / 64), 1, shared)))
        }
        assertFalse(flusher.flushAll(), "a failing disk cannot drain")
        assertTrue(flusher.storageFull.value)

        // The cap is lifted in the storage-full state: a commit past it is
        // still accepted, because a refused readback tile is lost work.
        assertTrue(flusher.hasMirrorRoom())
        assertTrue(flusher.markDirty(CpuTile(layer, TileKey(63, 63), 2, shared)))

        // Retried on the next tick, still failing: state holds, nothing lost.
        assertFalse(flusher.flushAll())
        assertTrue(flusher.storageFull.value)
        assertEquals(0, writer.written.size)

        // The disk recovers: the first successful write leaves the state, and
        // every pending tile drains.
        writer.fail = false
        assertTrue(flusher.flushAll())
        assertFalse(flusher.storageFull.value)
        assertEquals(tilesOverCap + 1, writer.written.size)
        assertEquals(0L, flusher.pendingBytes)
    }

    @Test
    fun `the mirror cap answers no room only when healthy and over cap`() = runBlocking {
        val shared = ByteArray(TILE_BYTES)
        assertTrue(flusher.hasMirrorRoom(), "empty mirror has room")
        writer.fail = true
        val tilesOverCap = (CPU_MIRROR_CAP_BYTES / TILE_BYTES).toInt() + 1
        for (i in 0 until tilesOverCap) {
            flusher.markDirty(CpuTile(layer, TileKey(i % 64, i / 64), 1, shared))
        }
        // Over cap but not yet storage-full (no write has failed yet): the
        // commit path must wait.
        assertFalse(flusher.hasMirrorRoom())
        flusher.flushAll()
        // Now storage-full: lifted.
        assertTrue(flusher.hasMirrorRoom())
    }

    @Test
    fun `a tile dirtied twice before the drain is written once, with the latest bytes`() = runBlocking {
        val key = TileKey(2, 3)
        flusher.markDirty(tile(key, revision = 1, fill = 1))
        flusher.markDirty(tile(key, revision = 2, fill = 2))
        assertTrue(flusher.flushAll())
        assertEquals(1, writer.written.size)
        assertEquals(2, writer.written[0].second[0].toInt())
    }

    @Test
    fun `a stale revision never overwrites a newer one`() = runBlocking {
        val key = TileKey(1, 1)
        assertTrue(flusher.markDirty(tile(key, revision = 5, fill = 5)))
        // A chunk of revision 4 completing late (§10.1: two PBOs can finish
        // out of order) must be refused…
        assertFalse(flusher.markDirty(tile(key, revision = 4, fill = 4)))
        assertTrue(flusher.flushAll())
        assertEquals(5, writer.written.single().second[0].toInt())

        // …even after the newer write already drained.
        assertFalse(flusher.markDirty(tile(key, revision = 4, fill = 4)))
        assertTrue(flusher.flushAll())
        assertEquals(1, writer.written.size)
    }

    @Test
    fun `pendingBytes tracks the unflushed mirror`() = runBlocking {
        flusher.markDirty(tile(TileKey(0, 0)))
        flusher.markDirty(tile(TileKey(0, 1)))
        assertEquals(2L * TILE_BYTES, flusher.pendingBytes)
        assertTrue(flusher.flushAll())
        assertEquals(0L, flusher.pendingBytes)
    }
}
