package ch.lkmc.bangnidraw.ui.canvas

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CanvasCheckpointContractTest {

    @Test
    fun `thumbnail stays dirty until its write succeeds`() {
        val checkpoint = checkpointSource()
        val write = checkpoint.indexOf(THUMBNAIL_WRITE)
        if (write < 0) fail("missing $THUMBNAIL_WRITE")
        val clear = checkpoint.indexOf(CLEAR_THUMBNAIL_DIRTY)
        if (clear < 0) fail("missing $CLEAR_THUMBNAIL_DIRTY")

        assertTrue(write < clear, "$CLEAR_THUMBNAIL_DIRTY must follow $THUMBNAIL_WRITE")
    }

    @Test
    fun `thumbnail clears only for a successful, still-current write`() {
        val checkpoint = checkpointSource()
        val write = checkpoint.indexOf(THUMBNAIL_WRITE)
        if (write < 0) fail("missing $THUMBNAIL_WRITE")

        // The write's RESULT must reach the finisher: FAILED returns
        // unthrown, so ordering and the generation guard alone cannot tell
        // a failed write from a successful one. An earlier revision of this
        // test claimed the guard "subsumes the old WRITTEN branch"; it does
        // not — the guard protects against newer edits, not failed writes —
        // and that claim is how the retry flag came to be cleared
        // unconditionally.
        assertTrue(
            RESULT_CAPTURE in checkpoint,
            "the checkpoint must capture Thumbnails.write's result",
        )

        val finish = checkpoint.indexOf(FINISH_CHECKPOINT)
        if (finish < 0) fail("missing $FINISH_CHECKPOINT")
        assertTrue(finish > write, "$FINISH_CHECKPOINT must follow $THUMBNAIL_WRITE")

        val guarded = checkpoint.substring(finish)
        val clear = guarded.indexOf(GUARDED_CLEAR)
        if (clear < 0) fail("missing $GUARDED_CLEAR in finishCheckpoint")
        val staleRecheck = guarded.indexOf(FRESHNESS_STALE)

        assertTrue(
            staleRecheck in 0 until clear,
            "thumbDirty must clear only behind the stale-generation re-check",
        )
    }

    @Test
    fun `clean project fast path retains maintenance work`() {
        val checkpoint = checkpointSource()
        val barrier = checkpoint.indexOf(COMMIT_BARRIER)
        if (barrier < 0) fail("missing $COMMIT_BARRIER")
        val fastPath = checkpoint.substring(0, barrier)

        assertTrue(THUMBNAIL_DIRTY in fastPath)
        assertTrue(PENDING_DELETES in fastPath)
    }

    private fun checkpointSource(): String {
        val viewModel = source(CANVAS_VIEW_MODEL_PATH)
        val start = viewModel.indexOf(CHECKPOINT_START)
        if (start < 0) fail("missing $CHECKPOINT_START")
        val end = viewModel.indexOf(CHECKPOINT_END, start)
        if (end <= start) fail("missing $CHECKPOINT_END after checkpoint")

        return viewModel.substring(start, end)
    }

    private companion object {
        const val CANVAS_VIEW_MODEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/canvas/CanvasViewModel.kt"
        const val CHECKPOINT_START = "private suspend fun checkpoint("
        const val CHECKPOINT_END = "private suspend fun maybeSyncGallery("
        const val COMMIT_BARRIER = "CheckpointBarrier.commitWhenFlushed("
        const val THUMBNAIL_WRITE = "Thumbnails.write("
        const val CLEAR_THUMBNAIL_DIRTY = "thumbDirty = false"
        const val RESULT_CAPTURE =
            "val thumbnailResult = if (snapshot.thumbnailWork == ThumbnailWork.WRITE) {"
        const val GUARDED_CLEAR =
            "if (thumbnailResult != ThumbnailWriteResult.FAILED) thumbDirty = false"
        const val FINISH_CHECKPOINT = "private fun finishCheckpoint("
        const val FRESHNESS_STALE = "CheckpointFreshness.STALE"
        const val THUMBNAIL_DIRTY = "thumbDirty"
        const val PENDING_DELETES = "pendingDeletes"
    }

    private fun source(path: String): String = ContractTestSources.read(path)
}
