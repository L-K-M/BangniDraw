package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `docs/plan/11-testing.md` §3.8's pixel cases — undo/redo identity on tile
 * *bytes* — run against the real protocol: [TileFlusher]'s job queue,
 * [HistoryStore]'s files and [HistoryPixels]'s capture-then-read ordering,
 * over a [TileStore] on a temp dir. The GPU upload is the one part faked: a
 * restore lands in the mirror exactly as the ViewModel lands it, and the
 * disk is the state under test (decision 3: pixels are the document).
 */
class HistoryPixelsTest {

    private val root = createTempDirectory("bangni-hpx").toFile()
    private val layerDir = File(root, "layer")
    private val historyDir = File(root, "history")
    private val tiles = TileStore(layerDir)
    private val store = HistoryStore(historyDir)
    private val layer = LayerId("layer-a")

    private val flusher = TileFlusher(
        write = { _, key, pixels -> tiles.write(key, pixels) },
    ).also {
        it.historyStore = store
        it.diskReader = TileFlusher.DiskReader { _, key ->
            File(layerDir, TileStore.fileName(key)).takeIf { f -> f.isFile }?.readBytes()
        }
    }

    private val pixels = HistoryPixels(flusher, store)

    private var seq = 0L
    private var revision = 0

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    /** One committed stroke, end to end: capture → WriteEntry → readback → flush. */
    private suspend fun stroke(edits: List<Pair<TileKey, ByteArray>>): HistoryEntry {
        val keys = edits.map { it.first }
        val entry = HistoryEntry.Stroke(
            activeBefore = layer, activeAfter = layer, layerId = layer, tiles = keys,
        )
        val job = TileFlusher.FlushJob.WriteEntry(
            entry = entry,
            seq = ++seq,
            ts = seq * 100,
            mirrorBefore = flusher.captureMirror(keys.map { layer to it }),
            awaitReadback = {
                for ((key, bytes) in edits) {
                    flusher.markDirty(CpuTile(layer, key, ++revision, bytes.copyOf()))
                }
            },
        )
        flusher.enqueue(job)
        flusher.runQueued()
        return assertNotNull(job.result.await())
    }

    /** The ViewModel's restore, minus the GPU: mirror + flush. */
    private suspend fun apply(restores: List<HistoryPixels.Restore>) {
        for (restore in restores) {
            for ((key, raw) in restore.tiles) {
                flusher.markDirty(
                    CpuTile(restore.layer, key, ++revision, raw ?: ByteArray(TILE_BYTES)),
                )
            }
        }
        pixels.flushRestored(restores)
        flusher.runQueued()
    }

    private suspend fun undo(entry: HistoryEntry): Long {
        val undo = assertNotNull(pixels.beforeUndo(entry))
        apply(undo.restores)
        // The queue has drained by now (apply ran it), so the deferred is
        // settled; -1 = no capture was needed this cycle.
        return undo.redoBytes?.await() ?: -1L
    }

    private suspend fun redo(entry: HistoryEntry) {
        apply(assertNotNull(pixels.beforeRedo(entry)))
    }

    private fun diskBytes(key: TileKey): ByteArray? = when (val read = tiles.read(key)) {
        is TileStore.Read.Pixels -> read.pixels
        TileStore.Read.Empty -> null
        TileStore.Read.Corrupt -> error("corrupt tile in test")
    }

    @Test
    fun `undo of a pixel entry restores before-tiles and captures after-tiles`() = runBlocking {
        val virgin = TileKey(0, 0)
        val painted = TileKey(1, 0)
        val before = Random(1).nextBytes(TILE_BYTES)
        stroke(listOf(painted to before))
        val after = Random(2).nextBytes(TILE_BYTES)
        val entry = stroke(listOf(painted to after, virgin to Random(3).nextBytes(TILE_BYTES)))

        val redoBytes = undo(entry)
        assertTrue(redoBytes > 0, "the first undo writes the sidecar")
        assertTrue(store.hasRedo(entry.seq))
        // The painted tile is back to its pre-stroke bytes; the virgin tile's
        // len-0 payload deleted its file — without that record, undo could
        // not know to clear it (06 §5.3).
        assertTrue(diskBytes(painted).contentEquals(before))
        assertEquals(null, diskBytes(virgin))

        // The sidecar is captured once and reused: a second undo/redo cycle
        // must not rewrite it (§5.4 — between an undo and a redo nothing
        // else can edit).
        val sidecarBytes = store.redoFile(entry.seq).readBytes()
        redo(entry)
        val secondRedoBytes = undo(entry)
        assertEquals(-1L, secondRedoBytes, "no second capture")
        assertTrue(store.redoFile(entry.seq).readBytes().contentEquals(sidecarBytes))
    }

    @Test
    fun `undo then redo is identity on tile bytes`() = runBlocking {
        // Property over random edit sequences: whatever the walk did, one
        // undo();redo() pair leaves every tile's bytes byte-equal.
        val random = Random(42)
        val keys = listOf(TileKey(0, 0), TileKey(1, 0), TileKey(0, 1), TileKey(2, 2))
        val entries = ArrayList<HistoryEntry>()
        repeat(6) {
            val touched = keys.shuffled(random).take(1 + random.nextInt(3))
            entries += stroke(touched.map { it to random.nextBytes(TILE_BYTES) })
        }

        // Walk back a random distance, then forward again over the same
        // entries; then check one undo/redo pair at that depth too.
        val depth = 1 + random.nextInt(entries.size)
        val snapshot = keys.associateWith { diskBytes(it)?.copyOf() }
        for (i in 0 until depth) undo(entries[entries.size - 1 - i])
        for (i in depth - 1 downTo 0) redo(entries[entries.size - 1 - i])
        for (key in keys) {
            val expected = snapshot[key]
            val actual = diskBytes(key)
            if (expected == null) {
                assertEquals(null, actual, "$key")
            } else {
                assertTrue(actual != null && actual.contentEquals(expected), "$key")
            }
        }
    }

    @Test
    fun `a redo without its sidecar reverts rather than guessing`() = runBlocking {
        val key = TileKey(3, 3)
        val entry = stroke(listOf(key to Random(9).nextBytes(TILE_BYTES)))
        undo(entry)
        store.deleteRedo(entry.seq)
        assertEquals(null, pixels.beforeRedo(entry))
    }
}
