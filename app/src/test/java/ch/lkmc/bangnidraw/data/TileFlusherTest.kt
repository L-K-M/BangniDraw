package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerRecord
import ch.lkmc.bangnidraw.engine.core.PerfConstants.CPU_MIRROR_CAP_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `docs/plan/11-testing.md` §5's `TileFlusherTest`, on the §6.3 job queue.
 *
 * No worker coroutine: the tests enqueue jobs and drain with [TileFlusher
 * .runQueued], so every assertion is deterministic — the production worker is
 * one `receive → run` loop over the same code.
 */
class TileFlusherTest {

    private class FakeWriter : TileFlusher.TileWriter {
        var fail = false
        val events = ArrayList<String>()

        override fun write(layer: LayerId, key: TileKey, pixels: ByteArray) {
            if (fail) throw IOException("disk full")
            events += "tile:${key.tx}_${key.ty}"
        }
    }

    private val historyDir = createTempDirectory("bangni-flusher").toFile()
    private val writer = FakeWriter()
    private val flusher = TileFlusher(writer).also {
        it.historyStore = HistoryStore(historyDir)
    }
    private val layer = LayerId("layer-a")

    @AfterTest
    fun tearDown() {
        historyDir.deleteRecursively()
    }

    private fun tile(key: TileKey, revision: Int = 1, fill: Byte = 1) =
        CpuTile(layer, key, revision, ByteArray(TILE_BYTES) { fill })

    private fun strokeEntry(keys: List<TileKey>) = HistoryEntry.Stroke(
        activeBefore = layer, activeAfter = layer, layerId = layer, tiles = keys,
    )

    /** Enqueue a checkpoint, drain the queue, await its answer. */
    private suspend fun flushEverything(): Boolean {
        val job = TileFlusher.FlushJob.Checkpoint()
        flusher.enqueue(job)
        flusher.runQueued()
        return job.done.await()
    }

    @Test
    fun `nonblocking enqueue refuses a full queue`() = runBlocking {
        repeat(EXPECTED_QUEUE_CAPACITY) {
            assertTrue(flusher.enqueueNow(TileFlusher.FlushJob.Checkpoint()))
        }
        assertFalse(flusher.enqueueNow(TileFlusher.FlushJob.Checkpoint()))

        flusher.runQueued()
        assertTrue(flusher.enqueueNow(TileFlusher.FlushJob.Checkpoint()))
    }

    @Test
    fun `storage full lifts the mirror cap and keeps committing`() = runBlocking {
        writer.fail = true

        // Fill the mirror past CPU_MIRROR_CAP_BYTES while every write fails.
        // One shared pixel buffer keeps the test's footprint sane; the mirror
        // never copies on ingest, so aliasing is fine here.
        val shared = ByteArray(TILE_BYTES) { 7 }
        val tilesOverCap = (CPU_MIRROR_CAP_BYTES / TILE_BYTES).toInt() + 1
        for (i in 0 until tilesOverCap) {
            assertTrue(flusher.markDirty(CpuTile(layer, TileKey(i % 64, i / 64), 1, shared)))
        }
        assertFalse(flushEverything(), "a failing disk cannot drain")
        assertTrue(flusher.storageFull.value)

        // The cap is lifted in the storage-full state: a commit past it is
        // still accepted, because a refused readback tile is lost work.
        assertTrue(flusher.hasMirrorRoom())
        assertTrue(flusher.markDirty(CpuTile(layer, TileKey(63, 63), 2, shared)))

        // Retried on the next tick, still failing: state holds, nothing lost.
        assertFalse(flushEverything())
        assertTrue(flusher.storageFull.value)
        assertEquals(0, writer.events.size)

        // The disk recovers: the first successful write leaves the state, and
        // every pending tile drains.
        writer.fail = false
        assertTrue(flushEverything())
        assertFalse(flusher.storageFull.value)
        assertEquals(tilesOverCap + 1, writer.events.size)
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
        flushEverything()
        // Now storage-full: lifted.
        assertTrue(flusher.hasMirrorRoom())
    }

    @Test
    fun `a tile dirtied twice before the flush is written once, with the latest bytes`() = runBlocking {
        val key = TileKey(2, 3)
        flusher.markDirty(tile(key, revision = 1, fill = 1))
        flusher.markDirty(tile(key, revision = 2, fill = 2))
        assertTrue(flushEverything())
        assertEquals(listOf("tile:2_3"), writer.events)
    }

    @Test
    fun `a stale revision never overwrites a newer one`() = runBlocking {
        val key = TileKey(1, 1)
        assertTrue(flusher.markDirty(tile(key, revision = 5, fill = 5)))
        // A chunk of revision 4 completing late (§10.1: two PBOs can finish
        // out of order) must be refused…
        assertFalse(flusher.markDirty(tile(key, revision = 4, fill = 4)))
        assertTrue(flushEverything())
        assertEquals(1, writer.events.size)

        // …even after the newer write already drained.
        assertFalse(flusher.markDirty(tile(key, revision = 4, fill = 4)))
        assertTrue(flushEverything())
        assertEquals(1, writer.events.size)
    }

    @Test
    fun `WriteEntry runs section 5-6's order, entry then readback then tiles`() = runBlocking {
        val key = TileKey(0, 0)
        val entry = strokeEntry(listOf(key))
        val job = TileFlusher.FlushJob.WriteEntry(
            entry = entry, seq = 1, ts = 10,
            mirrorBefore = emptyMap(),
            awaitReadback = {
                // The step's pixels land only now — after the entry is on
                // disk, before the flush.
                writer.events += "readback"
                flusher.markDirty(tile(key, revision = 1, fill = 9))
                TileFlusher.ReadbackResult.COMPLETE
            },
        )
        flusher.enqueue(job)
        flusher.enqueue(TileFlusher.FlushJob.Checkpoint())
        flusher.runQueued()

        val stamped = assertNotNull(job.result.await())
        assertTrue(stamped.isStamped)
        assertEquals(TileFlusher.StepResult.COMPLETE, job.completion.await())
        assertTrue(HistoryStore(historyDir).entryFile(1).isFile)
        assertEquals(listOf("readback", "tile:0_0"), writer.events)
        assertEquals(0L, flusher.pendingBytes)
    }

    @Test
    fun `a pending readback leaves dirty pixels unflushed`() = runBlocking {
        val key = TileKey(0, 0)
        flusher.markDirty(tile(key, revision = 1, fill = 9))
        val job = TileFlusher.FlushJob.WriteEntry(
            entry = strokeEntry(listOf(key)),
            seq = 1,
            ts = 10,
            mirrorBefore = emptyMap(),
            awaitReadback = { TileFlusher.ReadbackResult.PENDING },
        )

        flusher.enqueue(job)
        flusher.runQueued()

        assertNotNull(job.result.await())
        assertEquals(TileFlusher.StepResult.DEFERRED, job.completion.await())
        assertTrue(writer.events.isEmpty())
        assertEquals(TILE_BYTES.toLong(), flusher.pendingBytes)
    }

    @Test
    fun `a failed tile flush defers destructive followers`() = runBlocking {
        val key = TileKey(0, 0)
        flusher.markDirty(tile(key, revision = 1, fill = 9))
        writer.fail = true
        val job = TileFlusher.FlushJob.WriteEntry(
            entry = strokeEntry(listOf(key)),
            seq = 1,
            ts = 10,
            mirrorBefore = emptyMap(),
            awaitReadback = { TileFlusher.ReadbackResult.COMPLETE },
        )

        flusher.enqueue(job)
        flusher.runQueued()

        assertEquals(TileFlusher.StepResult.DEFERRED, job.completion.await())
        assertEquals(TILE_BYTES.toLong(), flusher.pendingBytes)
    }

    @Test
    fun `WriteEntry flushes structural outputs outside its payload`() = runBlocking {
        val before = TileKey(0, 0)
        val output = TileKey(1, 0)
        val job = TileFlusher.FlushJob.WriteEntry(
            entry = strokeEntry(listOf(before)),
            seq = 1,
            ts = 10,
            mirrorBefore = emptyMap(),
            changedKeys = listOf(layer to before, layer to output),
            awaitReadback = {
                flusher.markDirty(tile(output, revision = 1, fill = 9))
                TileFlusher.ReadbackResult.COMPLETE
            },
        )

        flusher.enqueue(job)
        flusher.runQueued()

        assertEquals(listOf("tile:1_0"), writer.events)
        assertEquals(0L, flusher.pendingBytes)
    }

    @Test
    fun `WriteEntry resolves before-tiles mirror first, then disk, then empty`() = runBlocking {
        val store = HistoryStore(historyDir)
        val mirrorKey = TileKey(1, 0)
        val diskKey = TileKey(2, 0)
        val virginKey = TileKey(3, 0)
        val mirrorPixels = Random(1).nextBytes(TILE_BYTES)
        val diskEncoded = TileCodec.encode(Random(2).nextBytes(TILE_BYTES))
        flusher.diskReader = TileFlusher.DiskReader { _, key ->
            if (key == diskKey) diskEncoded else null
        }

        val entry = strokeEntry(listOf(mirrorKey, diskKey, virginKey))
        val job = TileFlusher.FlushJob.WriteEntry(
            entry = entry, seq = 1, ts = 10,
            mirrorBefore = mapOf((layer to mirrorKey) to mirrorPixels),
            awaitReadback = { TileFlusher.ReadbackResult.COMPLETE },
        )
        flusher.enqueue(job)
        flusher.runQueued()
        assertNotNull(job.result.await())

        val payloads = store.readPayloads(1, sidecar = false)!!
        assertEquals(3, payloads.size)
        // Mirror-held raw pixels are deflated at capture…
        val mirrorPayload = payloads[0]
        assertTrue(
            (TileCodec.decode(mirrorPayload.encoded) as TileCodec.Decoded.Ok)
                .pixels.contentEquals(mirrorPixels),
        )
        // …disk bytes are copied verbatim, no inflate/deflate (§5.6 step 1)…
        assertTrue(payloads[1].encoded.contentEquals(diskEncoded))
        // …and a virgin tile is a len-0 payload: "empty before".
        assertEquals(0, payloads[2].encoded.size)
    }

    @Test
    fun `a failed entry write is storage-full, completes null, and flushes nothing`() = runBlocking {
        historyDir.deleteRecursively()
        historyDir.writeText("not a directory")
        val job = TileFlusher.FlushJob.WriteEntry(
            entry = strokeEntry(listOf(TileKey(0, 0))), seq = 1, ts = 1,
            mirrorBefore = emptyMap(),
            awaitReadback = {
                writer.events += "readback"
                TileFlusher.ReadbackResult.COMPLETE
            },
        )
        flusher.enqueue(job)
        flusher.runQueued()
        assertNull(job.result.await())
        assertTrue(flusher.storageFull.value)
        assertTrue(writer.events.isEmpty(), "no readback wait, no tile flush")
    }

    @Test
    fun `WriteRedo captures current the same way and reports the sidecar size`() = runBlocking {
        val store = HistoryStore(historyDir)
        val key = TileKey(4, 4)
        val stamped = store.append(
            strokeEntry(listOf(key)), seq = 1, ts = 1,
            payloads = listOf(HistoryStore.Payload(layer, key, ByteArray(0))),
        )
        val current = Random(5).nextBytes(TILE_BYTES)
        val job = TileFlusher.FlushJob.WriteRedo(
            entry = stamped,
            mirrorCurrent = mapOf((layer to key) to current),
        )
        flusher.enqueue(job)
        flusher.runQueued()
        val bytes = assertNotNull(job.result.await())
        assertEquals(store.redoFile(1).length(), bytes)
        val payload = store.readPayloads(1, sidecar = true)!!.single()
        assertTrue(
            (TileCodec.decode(payload.encoded) as TileCodec.Decoded.Ok)
                .pixels.contentEquals(current),
        )
    }

    @Test
    fun `merge redo captures rewritten tiles from the lower layer`() = runBlocking {
        val store = HistoryStore(historyDir)
        val upper = LayerId("upper")
        val key = TileKey(5, 5)
        val entry = HistoryEntry.LayerMerge(
            activeBefore = upper,
            activeAfter = layer,
            upper = LayerRecord(upper.value, "upper"),
            upperIndex = 1,
            upperTiles = listOf(key),
            lower = LayerRecord(layer.value, "lower"),
            lowerTiles = emptyList(),
        )
        val stamped = store.append(
            entry,
            seq = 1,
            ts = 1,
            payloads = listOf(HistoryStore.Payload(upper, key, ByteArray(0))),
        )
        val merged = Random(8).nextBytes(TILE_BYTES)
        val job = TileFlusher.FlushJob.WriteRedo(
            entry = stamped,
            mirrorCurrent = mapOf((layer to key) to merged),
        )

        flusher.enqueue(job)
        flusher.runQueued()
        assertNotNull(job.result.await())

        val payload = store.readPayloads(1, sidecar = true)!!.single()
        assertEquals(layer, payload.layer)
        assertTrue(
            (TileCodec.decode(payload.encoded) as TileCodec.Decoded.Ok)
                .pixels.contentEquals(merged),
        )
    }

    @Test
    fun `captureMirror copies, so a recycled buffer cannot corrupt an entry`() = runBlocking {
        val key = TileKey(6, 6)
        val original = tile(key, fill = 3)
        flusher.markDirty(original)
        val captured = flusher.captureMirror(listOf(layer to key))
        // The mirror buffer mutates after capture — as the pool recycling it
        // for the next readback would.
        original.pixels.fill(0)
        assertEquals(3, captured[layer to key]!![0].toInt())
    }

    @Test
    fun `pendingBytes tracks the unflushed mirror`() = runBlocking {
        flusher.markDirty(tile(TileKey(0, 0)))
        flusher.markDirty(tile(TileKey(0, 1)))
        assertEquals(2L * TILE_BYTES, flusher.pendingBytes)
        assertTrue(flushEverything())
        assertEquals(0L, flusher.pendingBytes)
    }

    private companion object {
        const val EXPECTED_QUEUE_CAPACITY = 64
    }
}
