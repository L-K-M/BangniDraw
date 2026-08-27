package ch.lkmc.bangnidraw.engine.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/plan/11-testing.md` §3.8's pure journal cases. The cases that need
 * pixels (undo/redo identity on tile *bytes*, redo-sidecar capture) live
 * with the store and the apply path, which own them.
 */
class HistoryJournalTest {

    private val a = LayerId("layer-a")

    private fun entry(seq: Long, bytes: Long = 10L): HistoryEntry =
        HistoryEntry.Stroke(
            activeBefore = a,
            activeAfter = a,
            layerId = a,
            tiles = listOf(TileKey(0, 0)),
        ).stamp(seq = seq, timestamp = seq, bytes = bytes)

    private fun journal(maxEntries: Int = 100, maxBytes: Long = 1_000_000L) =
        HistoryJournal(HistoryJournal.Limits(maxEntries, maxBytes))

    @Test
    fun `push appends and moves the cursor to the end`() {
        val j = journal()
        j.push(entry(1))
        j.push(entry(2))
        assertEquals(2, j.cursor)
        assertEquals(listOf(1L, 2L), j.entries.map { it.seq })
        assertTrue(j.canUndo())
        assertTrue(!j.canRedo())
    }

    @Test
    fun `push after undo truncates the redo branch`() {
        val j = journal()
        j.push(entry(1))
        j.push(entry(2))
        j.push(entry(3))
        j.undo()
        j.undo()
        val result = j.push(entry(4))
        assertEquals(listOf(3L, 2L), result.truncated)
        assertEquals(listOf(1L, 4L), j.entries.map { it.seq })
        assertEquals(2, j.cursor)
        assertTrue(result.pruned.isEmpty())
    }

    @Test
    fun `an unjournaled edit still discards its obsolete redo branch`() {
        val j = journal()
        j.push(entry(1))
        j.push(entry(2))
        j.push(entry(3))
        j.undo()
        j.undo()

        val truncated = j.truncateRedo()

        assertEquals(listOf(3L, 2L), truncated)
        assertTrue(!j.canRedo())
        assertEquals(listOf(1L), j.entries.map { it.seq })
    }

    @Test
    fun `undo then redo is identity on the journal`() {
        // Property over random push/undo/redo sequences: wherever the walk
        // lands, one undo();redo() pair leaves (cursor, entries) unchanged.
        val random = Random(42)
        val j = journal()
        var seq = 0L
        repeat(300) {
            when (random.nextInt(3)) {
                0 -> j.push(entry(++seq))
                1 -> j.undo()
                else -> j.redo()
            }
            val cursorBefore = j.cursor
            val seqsBefore = j.entries.map { it.seq }
            if (j.undo() != null) {
                j.redo()
            }
            assertEquals(cursorBefore, j.cursor)
            assertEquals(seqsBefore, j.entries.map { it.seq })
        }
    }

    @Test
    fun `undo at cursor zero and redo at the end are no-ops`() {
        val j = journal()
        assertNull(j.undo())
        assertNull(j.redo())
        j.push(entry(1))
        assertNull(j.redo())
        j.undo()
        assertNull(j.undo())
        assertEquals(0, j.cursor)
        assertEquals(1, j.entries.size)
    }

    @Test
    fun `prune by step count drops the oldest entries`() {
        val j = journal(maxEntries = 3)
        j.push(entry(1))
        j.push(entry(2))
        j.push(entry(3))
        val result = j.push(entry(4))
        assertEquals(listOf(1L), result.pruned)
        assertEquals(listOf(2L, 3L, 4L), j.entries.map { it.seq })
        assertEquals(3, j.cursor)
    }

    @Test
    fun `prune by bytes drops oldest until under budget`() {
        val j = journal(maxBytes = 100L)
        j.push(entry(1, bytes = 40))
        j.push(entry(2, bytes = 40))
        val result = j.push(entry(3, bytes = 40))
        assertEquals(listOf(1L), result.pruned)
        assertEquals(80L, j.bytes)
    }

    @Test
    fun `pruning never removes the entry just pushed, even alone over budget`() {
        // A flatten of a huge painting is still undoable once (06 §5.1).
        val j = journal(maxBytes = 100L)
        j.push(entry(1, bytes = 40))
        val result = j.push(entry(2, bytes = 500))
        assertEquals(listOf(1L), result.pruned)
        assertEquals(listOf(2L), j.entries.map { it.seq })
        assertEquals(1, j.cursor)
        assertTrue(j.canUndo())
    }

    @Test
    fun `the journal reports counts and bytes for the UI`() {
        val j = journal()
        j.push(entry(1, bytes = 30))
        j.push(entry(2, bytes = 12))
        assertEquals(HistoryJournal.Stats(2, 42L), j.stats())
    }

    @Test
    fun `redo sidecar bytes join the entry and the prune budget`() {
        val j = journal(maxBytes = 100L)
        j.push(entry(1, bytes = 40))
        j.push(entry(2, bytes = 40))
        j.noteRedoBytes(1, 15)
        assertEquals(95L, j.bytes)
        // The grown entry now tips the budget on the next push, so seq 1 —
        // entry plus sidecar — is what gets pruned.
        val result = j.push(entry(3, bytes = 40))
        assertEquals(listOf(1L), result.pruned)
        assertEquals(80L, j.bytes)
        // A sidecar landing for a seq that is gone is ignored, not an error.
        j.noteRedoBytes(1, 15)
        assertEquals(80L, j.bytes)
    }

    @Test
    fun `a loaded journal resumes with its cursor mid-list`() {
        // The reopen path: two applied, one redoable (06 §5.7).
        val loaded = HistoryJournal(
            HistoryJournal.Limits(100, 1_000L),
            initial = listOf(entry(3), entry(5), entry(9)),
            initialCursor = 2,
        )
        assertTrue(loaded.canUndo())
        assertTrue(loaded.canRedo())
        assertEquals(9L, loaded.redo()?.seq)
    }

    @Test
    fun `an unstamped entry and a reused seq are refused`() {
        val j = journal()
        assertFailsWith<IllegalArgumentException> {
            j.push(HistoryEntry.PaperColor(activeBefore = a, activeAfter = a, before = 0, after = 1))
        }
        j.push(entry(5))
        assertFailsWith<IllegalArgumentException> { j.push(entry(5)) }
        assertFailsWith<IllegalArgumentException> {
            HistoryJournal(
                HistoryJournal.Limits(10, 100L),
                initial = listOf(entry(2), entry(2)),
            )
        }
    }
}
