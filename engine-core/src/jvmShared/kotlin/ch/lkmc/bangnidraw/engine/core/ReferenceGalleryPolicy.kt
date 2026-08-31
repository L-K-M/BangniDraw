package ch.lkmc.bangnidraw.engine.core

/**
 * The pure decisions behind the gallery's *reference variant* — the second
 * MediaStore item a painting keeps while a tracing image is visible
 * (AGENTS.md's gallery-variant rule). Sibling of [GallerySyncDecision]:
 * MediaStore code stays Android-only and untested, the choices it acts on
 * are pinned here.
 */
object ReferenceGalleryPolicy {

    /**
     * Whether the reference variant is *involved* at all: a qualifying
     * reference to mirror, or a recorded row left to update or withdraw.
     * When neither holds there is no variant work — the due check is
     * short-circuited so a reference-less painting's advancing pixel
     * revision can never make the variant permanently "due".
     */
    fun variantInvolved(reference: TracingReference?, recordedUri: String?): Boolean =
        includes(reference) || recordedUri != null

    /**
     * Whether the gallery keeps a variant that includes the tracing image:
     * a reference that exists, is shown, and has opacity above zero — the
     * same gate `CanvasRenderer`'s reference draw applies, so the variant
     * never shows pixels the canvas would not.
     */
    fun includes(reference: TracingReference?): Boolean =
        reference != null &&
            reference.visibility == ReferenceVisibility.VISIBLE &&
            reference.opacity > 0f

    /**
     * The variant's debounce, on [GallerySyncDecision]'s pattern: it mirrors
     * when the pixels *or* the reference state moved since its last sync —
     * a transform or opacity change leaves `pixelRevision` alone — and a
     * mid-session checkpoint additionally waits out the shared 30 s floor.
     */
    fun isDue(
        trigger: GallerySyncDecision.Trigger,
        pixelRevision: Int,
        referenceRevision: Int,
        lastSyncedPixelRevision: Int,
        lastSyncedReferenceRevision: Int,
        nowMs: Long,
        lastSyncAtMs: Long,
    ): Boolean {
        if (pixelRevision == lastSyncedPixelRevision &&
            referenceRevision == lastSyncedReferenceRevision
        ) {
            return false
        }
        return when (trigger) {
            GallerySyncDecision.Trigger.LEAVE -> true
            GallerySyncDecision.Trigger.CHECKPOINT ->
                nowMs - lastSyncAtMs >= GallerySyncDecision.CHECKPOINT_FLOOR_MS
        }
    }
}
