package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.GallerySyncDecision.Action
import ch.lkmc.bangnidraw.engine.core.GallerySyncDecision.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `docs/plan/12-roadmap.md` step 4's pure decision table
 * (06 §9.2) and the §9.3 debounce.
 */
class GallerySyncDecisionTest {

    @Test
    fun `no recorded row inserts`() {
        // The other inputs are meaningless without a URI and must not flip
        // the answer.
        for (owner in listOf(true, false)) {
            for (threw in listOf(true, false)) {
                for (modified in listOf(true, false)) {
                    assertEquals(
                        Action.INSERT,
                        GallerySyncDecision.decide(false, owner, threw, modified),
                    )
                }
            }
        }
    }

    @Test
    fun `an owned, untouched row is rewritten in place`() {
        assertEquals(
            Action.REWRITE,
            GallerySyncDecision.decide(
                uriPresent = true, isOwner = true, threw = false, modifiedByOther = false,
            ),
        )
    }

    @Test
    fun `lost ownership, a thrown rewrite, or a foreign edit all abandon the row`() {
        // §9.2: three distinct causes, one honest outcome — the item stays as
        // the user left it and a fresh one is inserted. Overwriting someone
        // else's edit is what this table exists to prevent.
        assertEquals(Action.REINSERT, GallerySyncDecision.decide(true, false, false, false))
        assertEquals(Action.REINSERT, GallerySyncDecision.decide(true, true, true, false))
        assertEquals(Action.REINSERT, GallerySyncDecision.decide(true, true, false, true))
    }

    @Test
    fun `nothing syncs when nothing changed`() {
        assertFalse(
            GallerySyncDecision.isDue(Trigger.LEAVE, 5, 5, nowMs = 1_000_000, lastSyncAtMs = 0),
        )
        assertFalse(
            GallerySyncDecision.isDue(Trigger.CHECKPOINT, 5, 5, nowMs = 1_000_000, lastSyncAtMs = 0),
        )
    }

    @Test
    fun `leave syncs unconditionally, a checkpoint waits out the floor`() {
        val floor = GallerySyncDecision.CHECKPOINT_FLOOR_MS
        assertTrue(GallerySyncDecision.isDue(Trigger.LEAVE, 6, 5, nowMs = 1, lastSyncAtMs = 0))
        assertFalse(
            GallerySyncDecision.isDue(Trigger.CHECKPOINT, 6, 5, nowMs = floor - 1, lastSyncAtMs = 0),
        )
        assertTrue(
            GallerySyncDecision.isDue(Trigger.CHECKPOINT, 6, 5, nowMs = floor, lastSyncAtMs = 0),
        )
    }

    @Test
    fun `the on-disk staleness rule is updatedAt past the last sync`() {
        assertTrue(GallerySyncDecision.isStaleOnDisk(updatedAt = 10, lastGallerySyncAt = 0))
        assertTrue(GallerySyncDecision.isStaleOnDisk(updatedAt = 10, lastGallerySyncAt = 9))
        assertFalse(GallerySyncDecision.isStaleOnDisk(updatedAt = 10, lastGallerySyncAt = 10))
        assertFalse(GallerySyncDecision.isStaleOnDisk(updatedAt = 10, lastGallerySyncAt = 11))
    }
}
