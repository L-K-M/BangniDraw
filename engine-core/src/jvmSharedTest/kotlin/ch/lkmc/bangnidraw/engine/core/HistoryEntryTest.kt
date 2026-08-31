package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The declaration-level contract of `docs/plan/06-document-and-persistence.md`
 * §5.2: an entry is born unstamped, the journal stamps it once, and stamping
 * changes nothing else. The journal itself lands with roadmap step 3.
 */
class HistoryEntryTest {

    private val a = LayerId("a")
    private val b = LayerId("b")
    private val record = LayerRecord(id = "a", name = "Layer 1")
    private val tiles = listOf(TileKey(0, 0), TileKey(1, 2))

    // Distinct on purpose. The payload check below rewinds the stamp and
    // compares for equality, which only catches a field swap if the two fields
    // differ: with before == after, a stamp() that swapped them would pass.
    private val recordBefore = LayerRecord(id = "a", name = "before")
    private val recordAfter = LayerRecord(id = "a", name = "after", opacity = 0.25f)
    private val upperRecord = LayerRecord(id = "b", name = "upper")
    private val otherTiles = listOf(TileKey(5, 6))

    /**
     * One of every kind. Listed by hand rather than reflected over the sealed
     * interface, so adding a kind without adding it here is a visible omission
     * in the diff rather than a silently smaller test.
     */
    private val everyKind: List<HistoryEntry> = listOf(
        HistoryEntry.Stroke(activeBefore = a, activeAfter = a, layerId = a, tiles = tiles),
        HistoryEntry.Fill(activeBefore = a, activeAfter = a, layerId = a, tiles = tiles),
        // The added layer cannot be active *before* the add, and the deleted
        // layer cannot still be active after. Nothing asserts these today, but
        // this list is the de-facto documentation of a well-formed entry.
        HistoryEntry.LayerAdd(activeBefore = b, activeAfter = a, layer = record, index = 1),
        HistoryEntry.LayerDelete(activeBefore = a, activeAfter = b, layer = record, index = 1, tiles = tiles),
        HistoryEntry.LayerReorder(activeBefore = a, activeAfter = a, layerId = a, fromIndex = 0, toIndex = 2),
        HistoryEntry.LayerProps(
            activeBefore = a,
            activeAfter = a,
            layerId = a,
            before = recordBefore,
            after = recordAfter,
        ),
        HistoryEntry.LayerMerge(
            activeBefore = b,
            activeAfter = a,
            upper = upperRecord,
            upperIndex = 1,
            upperTiles = otherTiles,
            lower = record,
            lowerTiles = tiles,
        ),
        // A copy always gets a fresh id; two live layers never share one.
        HistoryEntry.LayerDuplicate(
            activeBefore = a,
            activeAfter = b,
            sourceId = a,
            copy = upperRecord,
            index = 1,
        ),
        HistoryEntry.LayerClear(activeBefore = a, activeAfter = a, layerId = a, tiles = tiles),
        HistoryEntry.Flatten(
            activeBefore = b,
            activeAfter = a,
            layers = listOf(record, upperRecord),
            tilesPerLayer = mapOf(a to tiles, b to otherTiles),
            result = record,
        ),
        HistoryEntry.PaperColor(activeBefore = a, activeAfter = a, before = 0xFFFFFFFF.toInt(), after = 0),
    )

    @Test
    fun `no kind is listed twice`() {
        // Named for what it checks. It cannot detect a *missing* kind — the
        // `when` in stampedBackToUnstamped is the compiler-enforced half of
        // that, and adding a kind there without adding it here leaves the new
        // kind untested. Reflection would close it, but `sealedSubclasses`
        // needs kotlin-reflect, which this module does not take.
        assertEquals(
            everyKind.size,
            everyKind.map { it::class }.distinct().size,
            "the fixture list repeats a kind, so some kind below is untested",
        )
    }

    @Test
    fun `an entry a pure operation produced is unstamped`() {
        for (entry in everyKind) {
            assertFalse(entry.isStamped, "${entry::class.simpleName} was born stamped")
            assertEquals(HistoryEntry.UNSTAMPED, entry.seq, entry::class.simpleName)
            assertEquals(HistoryEntry.UNSTAMPED, entry.timestamp, entry::class.simpleName)
            assertEquals(HistoryEntry.UNSTAMPED, entry.bytes, entry::class.simpleName)
        }
    }

    @Test
    fun `stamping fills the journal's three fields and nothing else`() {
        for (entry in everyKind) {
            val stamped = entry.stamp(seq = 7, timestamp = 1_700_000_000_000, bytes = 4096)
            val name = entry::class.simpleName
            assertTrue(stamped.isStamped, "$name is not stamped after stamp()")
            assertEquals(7L, stamped.seq, name)
            assertEquals(1_700_000_000_000L, stamped.timestamp, name)
            assertEquals(4096L, stamped.bytes, name)
            assertEquals(entry::class, stamped::class, "$name changed kind when stamped")
            assertEquals(entry.activeBefore, stamped.activeBefore, name)
            assertEquals(entry.activeAfter, stamped.activeAfter, name)
            // The payload is compared by rewinding the stamp: a data class
            // equal to the original once the three fields are back means the
            // stamp touched only those three, whatever the kind's own fields
            // are — which is the claim, and it needs no per-kind unpacking.
            assertEquals(
                entry,
                stamped.stampedBackToUnstamped(),
                "$name changed a payload field when stamped",
            )
        }
    }

    @Test
    fun `an entry is stamped once, never restamped`() {
        for (entry in everyKind) {
            val stamped = entry.stamp(seq = 7, timestamp = 1, bytes = 1)
            // Restamping would rewrite a seq the journal already issued and
            // silently reorder undo; a crash here is the cheap failure.
            assertFailsWith<IllegalStateException>("${entry::class.simpleName} allowed a second stamp") {
                stamped.stamp(seq = 8, timestamp = 2, bytes = 2)
            }
        }
    }

    @Test
    fun `isStamped cannot be fooled by the first sequence number`() {
        // The journal issues seq starting at 1 (06 §3, nextSeq = 1L), so the
        // sentinel must be a value it never issues.
        assertEquals(0L, HistoryEntry.UNSTAMPED)
        for (entry in everyKind) {
            assertTrue(
                entry.stamp(seq = 1, timestamp = 1, bytes = 0).isStamped,
                "${entry::class.simpleName} must read as stamped at seq 1, the journal's first number",
            )
        }
        // Stamping with the sentinel itself would produce an entry that still
        // reports isStamped false — so the single-shot check would pass a
        // second time and the journal could reissue the number.
        for (entry in everyKind) {
            assertFailsWith<IllegalArgumentException>(
                "${entry::class.simpleName} accepted the UNSTAMPED sentinel as a seq",
            ) {
                entry.stamp(seq = HistoryEntry.UNSTAMPED, timestamp = 1, bytes = 1)
            }
        }
    }

    /** Puts [HistoryEntry.UNSTAMPED] back in the three journal fields. */
    private fun HistoryEntry.stampedBackToUnstamped(): HistoryEntry = when (this) {
        is HistoryEntry.Stroke -> copy(seq = U, timestamp = U, bytes = U)
        is HistoryEntry.Fill -> copy(seq = U, timestamp = U, bytes = U)
        is HistoryEntry.LayerAdd -> copy(seq = U, timestamp = U, bytes = U)
        is HistoryEntry.LayerDelete -> copy(seq = U, timestamp = U, bytes = U)
        is HistoryEntry.LayerReorder -> copy(seq = U, timestamp = U, bytes = U)
        is HistoryEntry.LayerProps -> copy(seq = U, timestamp = U, bytes = U)
        is HistoryEntry.LayerMerge -> copy(seq = U, timestamp = U, bytes = U)
        is HistoryEntry.LayerDuplicate -> copy(seq = U, timestamp = U, bytes = U)
        is HistoryEntry.LayerClear -> copy(seq = U, timestamp = U, bytes = U)
        is HistoryEntry.Flatten -> copy(seq = U, timestamp = U, bytes = U)
        is HistoryEntry.PaperColor -> copy(seq = U, timestamp = U, bytes = U)
    }

    private companion object {
        const val U = HistoryEntry.UNSTAMPED
    }
}
