package ch.lkmc.bangnidraw.engine.core

/**
 * The gallery mirror's two pure decisions (`docs/plan/06-document-and-persistence.md`
 * §9.2, §9.3), kept out of `GalleryExporter` so the MediaStore code can stay
 * Android-only and untested while the choices it acts on are pinned on the
 * JVM (`docs/plan/12-roadmap.md` step 4's stated split).
 */
object GallerySyncDecision {

    /** What to do with the MediaStore row this sync. */
    enum class Action {
        /** No row was ever ours: insert a fresh item and remember its URI. */
        INSERT,

        /** The row is ours and untouched: rewrite it in place (`"wt"`). */
        REWRITE,

        /**
         * The row exists but is not ours to overwrite — ownership lost
         * (reinstall), the rewrite threw `SecurityException`, or another app
         * edited the file in place (§9.2's `DATE_MODIFIED`/`SIZE` mismatch).
         * The URI is forgotten, the item stays as the user left it, and a
         * fresh item is inserted. Overwriting someone's edit is the one
         * thing this table exists to prevent (principle 3).
         */
        REINSERT,
    }

    /**
     * §9.2's inputs. [threw] is a `SecurityException` from a *previous probe
     * or write attempt* on this row; [isOwner], [modifiedByOther] and
     * [pending] come from `probeRow`'s query. When no URI is recorded the
     * others are meaningless and ignored.
     *
     * [pending] is the row's `IS_PENDING`, and it decides ahead of the tamper
     * check. A write claims the row pending, truncates it, writes, and
     * publishes; a kill anywhere in that window — force-stop, low-memory,
     * ANR — leaves the claim standing with no code left to run. Such a row is
     * invisible in gallery apps, so from the user's side the painting has
     * simply vanished, and its `DATE_MODIFIED`/`SIZE` describe a half-finished
     * write rather than anyone's edit. Comparing them decides nothing:
     * matching pre-write metadata reads as "unchanged" and a mismatch reads
     * as a foreign edit, and the second answer, REINSERT, abandons the
     * invisible row permanently while inserting a duplicate beside it.
     *
     * Ours and unpublished, so reclaim it. A pending row cannot carry an edit
     * worth protecting: no other app can see it to edit.
     */
    fun decide(
        uriPresent: Boolean,
        isOwner: Boolean,
        threw: Boolean,
        modifiedByOther: Boolean,
        pending: Boolean = false,
    ): Action = when {
        !uriPresent -> Action.INSERT
        threw || !isOwner -> Action.REINSERT
        pending -> Action.REWRITE
        modifiedByOther -> Action.REINSERT
        else -> Action.REWRITE
    }

    /** §9.3's triggers. Studio-open uses the on-disk rule below instead. */
    enum class Trigger { LEAVE, CHECKPOINT }

    /**
     * §9.3's debounce: leaving syncs whenever pixels (or the title, for
     * `DISPLAY_NAME`) moved since the last sync; a mid-session checkpoint
     * additionally waits out [CHECKPOINT_FLOOR_MS] since the last sync, so a
     * steady painter is not flattening a 4096² canvas every quiet window.
     */
    fun isDue(
        trigger: Trigger,
        pixelRevision: Int,
        lastSyncedRevision: Int,
        nowMs: Long,
        lastSyncAtMs: Long,
    ): Boolean {
        if (pixelRevision == lastSyncedRevision) return false
        return when (trigger) {
            Trigger.LEAVE -> true
            Trigger.CHECKPOINT -> nowMs - lastSyncAtMs >= CHECKPOINT_FLOOR_MS
        }
    }

    /**
     * §9.3's on-disk equivalent for paintings that are not open: the Studio
     * syncs any painting edited since its last sync, one at a time, on the
     * CPU path. `lastGallerySyncAt` 0 means never synced — due as soon as
     * the painting has any content timestamp at all.
     */
    fun isStaleOnDisk(updatedAt: Long, lastGallerySyncAt: Long): Boolean =
        updatedAt > lastGallerySyncAt

    /** §9.3's 30 s floor between mid-session gallery flattens. */
    const val CHECKPOINT_FLOOR_MS = 30_000L
}
