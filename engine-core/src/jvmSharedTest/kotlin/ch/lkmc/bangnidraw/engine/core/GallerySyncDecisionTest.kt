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
    fun `a pending row we still own is our own stranded claim, so reclaim it`() {
        // A kill between the claim and the publish — force-stop, low memory,
        // ANR — leaves IS_PENDING standing with no code left to run. The row
        // is invisible in gallery apps, so the painting has simply vanished
        // from the user's side, and its DATE_MODIFIED/SIZE describe a
        // half-finished write rather than anyone's edit.
        assertEquals(
            Action.REWRITE,
            GallerySyncDecision.decide(
                uriPresent = true, isOwner = true, threw = false,
                modifiedByOther = false, pending = true,
            ),
        )
        // And it outranks the tamper check, which is the whole point: a
        // mismatch here is our own interrupted write, not a foreign edit, and
        // REINSERT would abandon the invisible row forever while inserting a
        // duplicate beside it. Nothing else can have edited a row no other app
        // can see.
        assertEquals(
            Action.REWRITE,
            GallerySyncDecision.decide(
                uriPresent = true, isOwner = true, threw = false,
                modifiedByOther = true, pending = true,
            ),
        )
    }

    @Test
    fun `a pending row that is not ours is still abandoned`() {
        // Pending reclaims only what we own; the ownership and probe-refused
        // branches still decide first.
        assertEquals(
            Action.REINSERT,
            GallerySyncDecision.decide(
                uriPresent = true, isOwner = false, threw = false,
                modifiedByOther = false, pending = true,
            ),
        )
        assertEquals(
            Action.REINSERT,
            GallerySyncDecision.decide(
                uriPresent = true, isOwner = true, threw = true,
                modifiedByOther = false, pending = true,
            ),
        )
        // No recorded URI still wins over everything.
        assertEquals(
            Action.INSERT,
            GallerySyncDecision.decide(
                uriPresent = false, isOwner = true, threw = false,
                modifiedByOther = false, pending = true,
            ),
        )
    }

    @Test
    fun `a published row keeps the tamper check`() {
        // The guarantee pending must not weaken: with the row published, a
        // foreign edit is still respected rather than overwritten.
        assertEquals(
            Action.REINSERT,
            GallerySyncDecision.decide(
                uriPresent = true, isOwner = true, threw = false,
                modifiedByOther = true, pending = false,
            ),
        )
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
