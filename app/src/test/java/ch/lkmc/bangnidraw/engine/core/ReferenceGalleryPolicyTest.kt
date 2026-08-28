package ch.lkmc.bangnidraw.engine.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The reference variant's pure decisions, on the `GallerySyncDecision` pattern. */
class ReferenceGalleryPolicyTest {

    private fun reference(
        visibility: ReferenceVisibility = ReferenceVisibility.VISIBLE,
        opacity: Float = 0.5f,
    ) = TracingReference(
        assetName = "reference-x.png",
        imageWidth = 4,
        imageHeight = 4,
        transform = ReferenceTransform.IDENTITY,
        opacity = opacity,
        visibility = visibility,
    )

    @Test
    fun `only a visible reference with opacity contributes`() {
        assertTrue(ReferenceGalleryPolicy.includes(reference()))
        assertTrue(ReferenceGalleryPolicy.includes(reference(opacity = 1f)))
        assertFalse(ReferenceGalleryPolicy.includes(null))
        assertFalse(ReferenceGalleryPolicy.includes(reference(visibility = ReferenceVisibility.HIDDEN)))
        assertFalse(ReferenceGalleryPolicy.includes(reference(opacity = 0f)))
    }

    @Test
    fun `a reference-only edit is due`() {
        // The pixel revision stood still; the variant's pixels moved anyway.
        assertTrue(
            ReferenceGalleryPolicy.isDue(
                trigger = GallerySyncDecision.Trigger.LEAVE,
                pixelRevision = 7,
                referenceRevision = 2,
                lastSyncedPixelRevision = 7,
                lastSyncedReferenceRevision = 1,
                nowMs = 100,
                lastSyncAtMs = 90,
            ),
        )
    }

    @Test
    fun `a pixel-only edit is due too`() {
        assertTrue(
            ReferenceGalleryPolicy.isDue(
                trigger = GallerySyncDecision.Trigger.LEAVE,
                pixelRevision = 8,
                referenceRevision = 2,
                lastSyncedPixelRevision = 7,
                lastSyncedReferenceRevision = 2,
                nowMs = 100,
                lastSyncAtMs = 90,
            ),
        )
    }

    @Test
    fun `simultaneous pixel and reference edits are due`() {
        // Pins OR semantics: a suite of single-axis cases alone would also
        // pass an implementation demanding "exactly one" axis to differ.
        assertTrue(
            ReferenceGalleryPolicy.isDue(
                trigger = GallerySyncDecision.Trigger.LEAVE,
                pixelRevision = 8,
                referenceRevision = 3,
                lastSyncedPixelRevision = 7,
                lastSyncedReferenceRevision = 2,
                nowMs = 100,
                lastSyncAtMs = 90,
            ),
        )
    }

    @Test
    fun `nothing moved is never due`() {
        for (trigger in GallerySyncDecision.Trigger.entries) {
            assertFalse(
                ReferenceGalleryPolicy.isDue(
                    trigger = trigger,
                    pixelRevision = 7,
                    referenceRevision = 2,
                    lastSyncedPixelRevision = 7,
                    lastSyncedReferenceRevision = 2,
                    // Past the checkpoint floor: elapsed time alone must
                    // never make the variant due.
                    nowMs = GallerySyncDecision.CHECKPOINT_FLOOR_MS + 1,
                    lastSyncAtMs = 0,
                ),
            )
        }
    }

    @Test
    fun `checkpoints wait out the shared floor`() {
        assertFalse(
            ReferenceGalleryPolicy.isDue(
                trigger = GallerySyncDecision.Trigger.CHECKPOINT,
                pixelRevision = 7,
                referenceRevision = 3,
                lastSyncedPixelRevision = 7,
                lastSyncedReferenceRevision = 2,
                nowMs = GallerySyncDecision.CHECKPOINT_FLOOR_MS,
                lastSyncAtMs = 1,
            ),
            "one millisecond short of the floor",
        )
        assertTrue(
            ReferenceGalleryPolicy.isDue(
                trigger = GallerySyncDecision.Trigger.CHECKPOINT,
                pixelRevision = 7,
                referenceRevision = 3,
                lastSyncedPixelRevision = 7,
                lastSyncedReferenceRevision = 2,
                nowMs = GallerySyncDecision.CHECKPOINT_FLOOR_MS + 1,
                lastSyncAtMs = 1,
            ),
        )
    }
}
