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

/** `docs/plan/11-testing.md` §5's `HistoryStoreTest` plus 06 §5.6's load rules. */
class HistoryStoreTest {

    private val dir = createTempDirectory("bangni-history").toFile()
    private val store = HistoryStore(dir)
    private val a = LayerId("layer-a")

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A paper-colour entry: pixel-free, so no payloads and no sidecar needed. */
    private fun put(seq: Long): HistoryEntry = store.append(
        HistoryEntry.PaperColor(activeBefore = a, activeAfter = a, before = 0, after = seq.toInt()),
        seq = seq,
        ts = seq * 10,
        payloads = emptyList(),
    )

    /** A stroke entry with one real payload — the kind whose redo needs a sidecar. */
    private fun putStroke(seq: Long): HistoryEntry {
        val entry = HistoryEntry.Stroke(
            activeBefore = a, activeAfter = a, layerId = a, tiles = listOf(TileKey(1, 1)),
        )
        return store.append(
            entry, seq = seq, ts = seq * 10,
            payloads = listOf(
                HistoryStore.Payload(
                    a, TileKey(1, 1),
                    TileCodec.encode(Random(seq.toInt()).nextBytes(TILE_BYTES)),
                ),
            ),
        )
    }

    @Test
    fun `load sweeps temp files a kill left behind`() {
        put(1)
        val orphan = File(dir, "00000002.entry.tmp")
        orphan.writeBytes(ByteArray(64))

        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1))

        assertTrue(!orphan.exists(), "the interrupted write's temp must be swept")
        assertEquals(1, loaded.entries.size)
    }

    @Test
    fun `load sweeps a redo sidecar whose entry is gone and keeps a paired one`() {
        val kept = putStroke(1)
        store.writeRedo(
            kept,
            listOf(
                HistoryStore.Payload(
                    a, TileKey(1, 1),
                    TileCodec.encode(Random(41).nextBytes(TILE_BYTES)),
                ),
            ),
        )
        val doomed = putStroke(2)
        store.writeRedo(
            doomed,
            listOf(
                HistoryStore.Payload(
                    a, TileKey(1, 1),
                    TileCodec.encode(Random(42).nextBytes(TILE_BYTES)),
                ),
            ),
        )
        // A pair broken before delete() removed the sidecar first — or by
        // any partial delete: the entry went, the redo stayed.
        assertTrue(store.entryFile(2).delete())

        store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1))

        assertTrue(!store.hasRedo(2), "an orphan sidecar must be swept at load")
        assertTrue(store.hasRedo(1), "a sidecar whose entry survives must stay")
    }

    @Test
    fun `load keeps a redo sidecar beside a corrupt entry`() {
        val entry = putStroke(1)
        store.writeRedo(
            entry,
            listOf(
                HistoryStore.Payload(
                    a, TileKey(1, 1),
                    TileCodec.encode(Random(43).nextBytes(TILE_BYTES)),
                ),
            ),
        )
        // Present but unreadable — junk bytes, not an empty file, so this
        // stays corrupt even if an empty payload ever decodes as a valid
        // entry. Corrupt entries stay on disk as support questions, and
        // their redo belongs with them.
        store.entryFile(1).writeBytes(Random(9).nextBytes(32))

        store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1))

        assertTrue(store.hasRedo(1), "a sidecar beside a corrupt-but-present entry must stay")
    }

    @Test
    fun `entries are named by sequence and load in order`() {
        put(3)
        put(1)
        put(2)
        assertEquals(
            listOf("00000001.entry", "00000002.entry", "00000003.entry"),
            dir.list()!!.sorted(),
        )
        val loaded = store.load(HistoryRecord(cursor = 3, nextSeq = 4, oldestSeq = 1))
        assertEquals(listOf(1L, 2L, 3L), loaded.entries.map { it.seq })
        assertEquals(3, loaded.cursor)
    }

    @Test
    fun `a gap in the sequence stops loading at the gap`() {
        put(1)
        put(2)
        put(4)
        val loaded = store.load(HistoryRecord(cursor = 4, nextSeq = 5, oldestSeq = 1))
        assertEquals(listOf(1L, 2L), loaded.entries.map { it.seq })
        assertEquals(2, loaded.cursor, "the cursor cannot point past what was proven")
    }

    @Test
    fun `prune deletes the files it drops`() {
        putStroke(1)
        put(2)
        store.writeRedo(
            HistoryEntry.Stroke(
                activeBefore = a, activeAfter = a, layerId = a, tiles = listOf(TileKey(1, 1)),
            ).stamp(1, 10, 100),
            payloads = listOf(HistoryStore.Payload(a, TileKey(1, 1), ByteArray(0))),
        )
        assertTrue(store.entryFile(1).isFile && store.redoFile(1).isFile)
        store.delete(listOf(1L))
        assertTrue(!store.entryFile(1).exists() && !store.redoFile(1).exists())
        assertTrue(store.entryFile(2).isFile, "only the dropped seqs go")
    }

    @Test
    fun `seqs below oldestSeq are orphans of an unrecorded pruning and are deleted`() {
        put(1)
        put(2)
        put(3)
        val loaded = store.load(HistoryRecord(cursor = 2, nextSeq = 4, oldestSeq = 2))
        assertEquals(listOf(2L, 3L), loaded.entries.map { it.seq })
        assertTrue(!store.entryFile(1).exists())
    }

    @Test
    fun `a contiguous run past nextSeq is appended as applied and the rest deleted`() {
        // §5.6: entries pushed after the last checkpoint are not orphans — a
        // hard crash keeps undo for every committed stroke. The first gap
        // ends the run; what follows was orphaned by an uncheckpointed
        // truncation and is deleted.
        put(1)
        put(2) // the checkpoint saw 1; 2 and 3 landed after it
        put(3)
        put(5) // after the gap: a truncation's orphan
        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1))
        assertEquals(listOf(1L, 2L, 3L), loaded.entries.map { it.seq })
        assertEquals(3, loaded.cursor, "the recovered entries are applied, not redoable")
        assertTrue(!store.entryFile(5).exists())
    }

    @Test
    fun `entries past nextSeq are never appended onto a truncated prefix`() {
        // Files 1 and 3 inside the checkpointed range with 2 missing, and 4
        // at nextSeq. The prefix stops at 1; appending 4 onto it would replay
        // a stroke against a state missing the two steps between — so the
        // post-checkpoint recovery only runs when the whole recorded range
        // was proven.
        put(1)
        put(3)
        put(4)
        val loaded = store.load(HistoryRecord(cursor = 3, nextSeq = 4, oldestSeq = 1))
        assertEquals(listOf(1L), loaded.entries.map { it.seq })
        assertEquals(1, loaded.cursor)
    }

    @Test
    fun `a redo-branch entry without its sidecar loses the branch, not the undo half`() {
        putStroke(1)
        putStroke(2)
        putStroke(3)
        // Cursor 1: seq 1 applied, 2 and 3 redoable. A stroke's redo needs
        // its .redo, and none was ever written (say the crash beat it), so
        // the redo branch is dropped whole; undo is untouched (§5.4).
        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = 4, oldestSeq = 1))
        assertEquals(listOf(1L), loaded.entries.map { it.seq })
        assertEquals(1, loaded.cursor)
    }

    @Test
    fun `a redo-branch entry with its sidecar survives`() {
        val stamped = putStroke(1)
        store.writeRedo(
            stamped,
            payloads = listOf(
                HistoryStore.Payload(a, TileKey(1, 1), TileCodec.encode(ByteArray(TILE_BYTES))),
            ),
        )
        val loaded = store.load(HistoryRecord(cursor = 0, nextSeq = 2, oldestSeq = 1))
        assertEquals(listOf(1L), loaded.entries.map { it.seq })
        assertEquals(0, loaded.cursor)
        // The entry's byte count includes the sidecar (06 §5.1).
        assertEquals(
            store.entryFile(1).length() + store.redoFile(1).length(),
            loaded.entries.single().bytes,
        )
    }

    @Test
    fun `pixel-free kinds need no sidecar on the redo branch`() {
        put(1)
        put(2)
        val loaded = store.load(HistoryRecord(cursor = 0, nextSeq = 3, oldestSeq = 1))
        assertEquals(2, loaded.entries.size)
        assertEquals(0, loaded.cursor)
    }

    @Test
    fun `an empty or missing history dir loads an empty journal`() {
        assertEquals(HistoryStore.Loaded(emptyList(), 0), store.load(HistoryRecord()))
        val missing = HistoryStore(File(dir, "never-created"))
        assertEquals(HistoryStore.Loaded(emptyList(), 0), missing.load(HistoryRecord()))
    }

    @Test
    fun `foreign file names are ignored`() {
        put(1)
        File(dir, "notes.txt").writeText("x")
        File(dir, "0000002.entry").writeText("too short")
        File(dir, "00000001.redo.tmp").writeText("in flight")
        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1))
        assertEquals(1, loaded.entries.size)
    }

    /**
     * A hand-edited `<seq>.entry` with one payload ref of the caller's
     * `off`/`len`, over a 16-byte body. Used with an `off` near
     * `Long.MAX_VALUE`: the naive guard `bodyOffset + off + len > size`
     * wraps negative and passes, and the slice below then either throws
     * `IndexOutOfBoundsException` out of a path documented "never an
     * exception for content" or reads the payload from a wrong offset.
     * Both halves are pinned: load must truncate at the lying entry, and
     * readPayloads must answer null.
     */
    private fun writeEntry(off: Long, len: Int) {
        val json = java.lang.String.join(
            "",
            "{\"v\":1,\"seq\":1,\"kind\":\"Stroke\",\"ts\":10,",
            "\"activeBefore\":\"layer-a\",\"activeAfter\":\"layer-a\",",
            "\"layerId\":\"layer-a\",",
            "\"payloads\":[{",
            "\"layer\":\"layer-a\",\"tx\":0,\"ty\":0,",
            "\"off\":$off,\"len\":$len}",
            "],\"data\":{}}",
        )
        // A token body: an honest ref addresses it as (0, 16) and nothing else.
        store.entryFile(1).writeText(json + "\n" + "x".repeat(16))
    }

    /** The crafted header parses and an honest ref reads — the control the
     *  overflow tests need so they cannot pass on a parse failure instead. */
    @Test
    fun `an honest offset against the same crafted file still reads`() {
        writeEntry(off = 0, len = 16)
        val payloads = store.readPayloads(1, sidecar = false)
        assertNotNull(payloads, "the header must parse and its ref must address the body")
        // The body's bytes, not just its length: a slice from a wrong
        // in-bounds offset would be 16 bytes of header text and pass a
        // size-only check.
        assertEquals("x".repeat(16), payloads!!.single().encoded.decodeToString())
    }

    @Test
    fun `a payload offset that overflows the guard truncates the journal`() {
        writeEntry(off = Long.MAX_VALUE, len = TILE_BYTES)
        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1))
        assertTrue(loaded.entries.isEmpty(), "an entry whose payloads exceed the file is a lie")
        assertEquals(0, loaded.cursor)
    }

    @Test
    fun `readPayloads answers null for an offset that overflows the guard`() {
        writeEntry(off = Long.MAX_VALUE, len = TILE_BYTES)
        assertEquals(null, store.readPayloads(1, sidecar = false))
    }
}
