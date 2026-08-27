package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.HistoryJournal
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `entries are named by sequence and load in order`() {
        put(3)
        put(1)
        put(2)
        assertEquals(
            listOf("00000001.entry", "00000002.entry", "00000003.entry"),
            dir.list()!!.sorted(),
        )
        val loaded = store.load(HistoryRecord(cursor = 3, nextSeq = 4, oldestSeq = 1, entries = 3))
        assertEquals(listOf(1L, 2L, 3L), loaded.entries.map { it.seq })
        assertEquals(3, loaded.cursor)
    }

    @Test
    fun `an unproved legacy gap exposes no speculative prefix`() {
        put(1)
        put(2)
        put(4)
        val loaded = store.load(HistoryRecord(cursor = 4, nextSeq = 5, oldestSeq = 1, entries = 4))
        assertEquals(emptyList(), loaded.entries)
        assertEquals(0, loaded.cursor)
    }

    @Test
    fun `checkpointed divergent branch keeps its exact sequence set`() {
        put(1)
        put(4)

        val loaded = store.load(
            HistoryRecord(
                cursor = 2,
                nextSeq = 5,
                oldestSeq = 1,
                entries = 2,
                seqs = listOf(1, 4),
            ),
        )

        assertEquals(listOf(1L, 4L), loaded.entries.map { it.seq })
        assertEquals(2, loaded.cursor)
    }

    @Test
    fun `a far redo prune followed by a later push survives reopen`() {
        val first = putStroke(1)
        val second = put(2)
        val farRedo = put(3)
        val redoBytes = store.writeRedo(
            first,
            listOf(HistoryStore.Payload(a, TileKey(1, 1), ByteArray(0))),
        )
        val initialBytes = first.bytes + second.bytes + farRedo.bytes
        val journal = HistoryJournal(
            HistoryJournal.Limits(10, initialBytes + redoBytes - farRedo.bytes),
            initial = listOf(first, second, farRedo),
            initialCursor = 1,
        )

        assertEquals(1L, journal.undo()?.seq)
        journal.noteRedoBytes(1, redoBytes)
        val pruned = journal.pruneAfterRedoAccounting()
        assertEquals(listOf(3L), pruned)

        val saved = HistoryRecord(
            cursor = journal.cursor,
            nextSeq = 4,
            oldestSeq = 1,
            entries = journal.stats().entries,
            bytes = journal.stats().bytes,
            seqs = journal.entries.map(HistoryEntry::seq),
        )
        store.delete(pruned)

        val later = put(4)
        val push = journal.push(later)
        val loaded = store.load(saved)

        assertEquals(listOf(2L, 1L), push.truncated)
        assertEquals(listOf(4L), loaded.entries.map(HistoryEntry::seq))
        assertEquals(1, loaded.cursor)
        assertEquals(5L, loaded.nextSeq)
        assertTrue(!store.entryFile(1).exists())
        assertTrue(!store.entryFile(2).exists())
        assertTrue(!store.entryFile(3).exists())
        assertTrue(store.entryFile(4).isFile)
    }

    @Test
    fun `exact checkpoint membership removes stale truncated entries`() {
        put(1)
        put(2)
        put(3)
        put(4)

        val loaded = store.load(
            HistoryRecord(
                cursor = 2,
                nextSeq = 5,
                oldestSeq = 1,
                entries = 2,
                seqs = listOf(1, 4),
            ),
        )

        assertEquals(listOf(1L, 4L), loaded.entries.map { it.seq })
        assertTrue(!store.entryFile(2).exists())
        assertTrue(!store.entryFile(3).exists())
    }

    @Test
    fun `a committed entry replaces the checkpoint redo branch`() {
        put(1)
        put(2)
        put(3)
        put(4)

        val loaded = store.load(
            HistoryRecord(
                cursor = 1,
                nextSeq = 4,
                oldestSeq = 1,
                entries = 3,
                seqs = listOf(1, 2, 3),
            ),
        )

        assertEquals(listOf(1L, 4L), loaded.entries.map(HistoryEntry::seq))
        assertEquals(2, loaded.cursor)
        assertTrue(!store.entryFile(2).exists())
        assertTrue(!store.entryFile(3).exists())
    }

    @Test
    fun `legacy checkpoint infers a complete gapped membership`() {
        put(1)
        put(4)

        val loaded = store.load(
            HistoryRecord(
                cursor = 2,
                nextSeq = 5,
                oldestSeq = 1,
                entries = 2,
            ),
        )

        assertEquals(listOf(1L, 4L), loaded.entries.map(HistoryEntry::seq))
        assertEquals(2, loaded.cursor)
    }

    @Test
    fun `legacy stale contiguous files cannot replace saved membership`() {
        put(1)
        put(2)
        put(3)
        put(4)

        val loaded = store.load(
            HistoryRecord(
                cursor = 3,
                nextSeq = 5,
                oldestSeq = 1,
                entries = 3,
            ),
        )

        assertEquals(emptyList(), loaded.entries)
        assertEquals(0, loaded.cursor)
        assertEquals(listOf(1L, 2L, 3L, 4L), (1L..4L).filter { store.entryFile(it).isFile })
    }

    @Test
    fun `an exact membership count mismatch exposes nothing and preserves files`() {
        put(1)
        put(2)
        put(3)

        val loaded = store.load(
            HistoryRecord(
                cursor = 1,
                nextSeq = 3,
                oldestSeq = 1,
                entries = 2,
                seqs = listOf(1),
            ),
        )

        assertEquals(emptyList(), loaded.entries)
        assertEquals(0, loaded.cursor)
        assertTrue(store.entryFile(1).isFile)
        assertTrue(store.entryFile(2).isFile)
        assertTrue(store.entryFile(3).isFile)
    }

    @Test
    fun `a nonpositive next sequence preserves every entry`() {
        put(1)

        val loaded = store.load(
            HistoryRecord(
                cursor = 0,
                nextSeq = 0,
                oldestSeq = 1,
                entries = 0,
                seqs = emptyList(),
            ),
        )

        assertEquals(emptyList(), loaded.entries)
        assertEquals(2, loaded.nextSeq)
        assertTrue(store.entryFile(1).isFile)
    }

    @Test
    fun `an exhausted sequence preserves the terminal entry`() {
        val original = byteArrayOf(1, 2, 3)
        store.entryFile(Long.MAX_VALUE).writeBytes(original)

        val loaded = store.load(
            HistoryRecord(
                cursor = 0,
                nextSeq = Long.MAX_VALUE,
                oldestSeq = 1,
                entries = 0,
                seqs = emptyList(),
            ),
        )

        assertEquals(Long.MAX_VALUE, loaded.nextSeq)
        assertFailsWith<IOException> {
            store.append(
                HistoryEntry.PaperColor(
                    activeBefore = a,
                    activeAfter = a,
                    before = 0,
                    after = 1,
                ),
                seq = loaded.nextSeq,
                ts = 10,
                payloads = emptyList(),
            )
        }
        assertContentEquals(original, store.entryFile(Long.MAX_VALUE).readBytes())
    }

    @Test
    fun `orphan sidecars floor the next sequence`() {
        store.redoFile(7).writeBytes(byteArrayOf(7))
        store.afterFile(9).writeBytes(byteArrayOf(9))

        val loaded = store.load(
            HistoryRecord(
                cursor = 0,
                nextSeq = 1,
                oldestSeq = 1,
                entries = 0,
                seqs = emptyList(),
            ),
        )

        assertEquals(10, loaded.nextSeq)
    }

    @Test
    fun `append refuses an orphan sidecar sequence`() {
        val redo = byteArrayOf(7)
        val after = byteArrayOf(8)
        store.redoFile(7).writeBytes(redo)
        store.afterFile(8).writeBytes(after)

        assertFailsWith<IOException> { put(7) }
        assertFailsWith<IOException> { put(8) }
        assertContentEquals(redo, store.redoFile(7).readBytes())
        assertContentEquals(after, store.afterFile(8).readBytes())
    }

    @Test
    fun `invalid exact membership cannot trigger pre-oldest deletion`() {
        put(1)

        val loaded = store.load(
            HistoryRecord(
                cursor = 1,
                nextSeq = 4,
                oldestSeq = 2,
                entries = 1,
                seqs = listOf(1),
            ),
        )

        assertEquals(emptyList(), loaded.entries)
        assertEquals(4, loaded.nextSeq)
        assertTrue(store.entryFile(1).isFile)
    }

    @Test
    fun `invalid legacy count cannot trigger stale deletion`() {
        put(1)
        put(2)
        put(4)

        val loaded = store.load(
            HistoryRecord(
                cursor = 2,
                nextSeq = 3,
                oldestSeq = 1,
                entries = 99,
            ),
        )

        assertEquals(emptyList(), loaded.entries)
        assertEquals(5, loaded.nextSeq)
        assertTrue(store.entryFile(1).isFile)
        assertTrue(store.entryFile(2).isFile)
        assertTrue(store.entryFile(4).isFile)
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
        val loaded = store.load(HistoryRecord(cursor = 2, nextSeq = 4, oldestSeq = 2, entries = 2))
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
        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1, entries = 1))
        assertEquals(listOf(1L, 2L, 3L), loaded.entries.map { it.seq })
        assertEquals(3, loaded.cursor, "the recovered entries are applied, not redoable")
        assertTrue(!store.entryFile(5).exists())
    }

    @Test
    fun `a post-checkpoint pixel entry without an after-image is excluded`() {
        putStroke(1)

        val loaded = store.load(HistoryRecord(cursor = 0, nextSeq = 1, oldestSeq = 1))

        assertTrue(loaded.entries.isEmpty())
        assertEquals(0, loaded.cursor)
    }

    @Test
    fun `a post-checkpoint pixel entry with a valid after-image is recovered`() {
        val stamped = putStroke(1)
        val after = Random(2).nextBytes(TILE_BYTES)
        store.writeRecoveryAfter(
            seq = stamped.seq,
            payloads = listOf(
                HistoryStore.Payload(a, TileKey(1, 1), TileCodec.encode(after)),
            ),
        )

        val loaded = store.load(HistoryRecord(cursor = 0, nextSeq = 1, oldestSeq = 1))

        assertEquals(listOf(1L), loaded.entries.map(HistoryEntry::seq))
        assertEquals(1, loaded.cursor)
    }

    @Test
    fun `a corrupt post-checkpoint after-image is incomplete`() {
        putStroke(1)
        store.afterFile(1).writeText("not a recovery image")

        val loaded = store.load(HistoryRecord(cursor = 0, nextSeq = 1, oldestSeq = 1))

        assertTrue(loaded.entries.isEmpty())
        assertEquals(0, loaded.cursor)
    }

    @Test
    fun `entries past nextSeq are never appended onto a truncated prefix`() {
        // Files 1 and 3 inside the checkpointed range with 2 missing, and 4
        // at nextSeq. The prefix stops at 1; appending 4 onto it would replay
        // a stroke against a state missing the two steps between. No legacy
        // membership was proven, so neither the prefix nor recovery is safe.
        put(1)
        put(3)
        put(4)
        val loaded = store.load(HistoryRecord(cursor = 3, nextSeq = 4, oldestSeq = 1, entries = 3))
        assertEquals(emptyList(), loaded.entries)
        assertEquals(0, loaded.cursor)
    }

    @Test
    fun `a redo-branch entry without its sidecar loses the branch, not the undo half`() {
        putStroke(1)
        putStroke(2)
        putStroke(3)
        // Cursor 1: seq 1 applied, 2 and 3 redoable. A stroke's redo needs
        // its .redo, and none was ever written (say the crash beat it), so
        // the redo branch is dropped whole; undo is untouched (§5.4).
        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = 4, oldestSeq = 1, entries = 3))
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
        val loaded = store.load(HistoryRecord(cursor = 0, nextSeq = 2, oldestSeq = 1, entries = 1))
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
        val loaded = store.load(HistoryRecord(cursor = 0, nextSeq = 3, oldestSeq = 1, entries = 2))
        assertEquals(2, loaded.entries.size)
        assertEquals(0, loaded.cursor)
    }

    @Test
    fun `an empty or missing history dir preserves the next sequence`() {
        val record = HistoryRecord(
            cursor = 0,
            nextSeq = 7,
            oldestSeq = 7,
            entries = 0,
            seqs = emptyList(),
        )
        val expected = HistoryStore.Loaded(emptyList(), cursor = 0, nextSeq = 7)

        assertEquals(expected, store.load(record))
        val missing = HistoryStore(File(dir, "never-created"))
        assertEquals(expected, missing.load(record))
    }

    @Test
    fun `foreign file names are ignored`() {
        put(1)
        File(dir, "notes.txt").writeText("x")
        File(dir, "0000002.entry").writeText("too short")
        File(dir, "00000001.redo.tmp").writeText("in flight")
        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1, entries = 1))
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
        assertEquals("x".repeat(16), payloads.single().encoded.decodeToString())
    }

    @Test
    fun `a payload offset that overflows the guard truncates the journal`() {
        writeEntry(off = Long.MAX_VALUE, len = TILE_BYTES)
        val loaded = store.load(HistoryRecord(cursor = 1, nextSeq = 2, oldestSeq = 1, entries = 1))
        assertTrue(loaded.entries.isEmpty(), "an entry whose payloads exceed the file is a lie")
        assertEquals(0, loaded.cursor)
    }

    @Test
    fun `readPayloads answers null for an offset that overflows the guard`() {
        writeEntry(off = Long.MAX_VALUE, len = TILE_BYTES)
        assertEquals(null, store.readPayloads(1, sidecar = false))
    }
}
