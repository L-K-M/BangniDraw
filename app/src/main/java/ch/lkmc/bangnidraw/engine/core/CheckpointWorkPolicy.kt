package ch.lkmc.bangnidraw.engine.core

/** Whether a checkpoint already has a durable project commit point. */
internal enum class CheckpointProjectState {
    CLEAN_EXISTING,
    DIRTY_EXISTING,
    MISSING,
}

/** Whether tile files can stay unchanged while a checkpoint flattens them. */
internal enum class CheckpointStrokeState {
    IDLE,
    LIVE,
}

/** Work that remains after a checkpoint snapshot is captured. */
internal enum class CheckpointWork {
    NONE,
    SYNC_GALLERY,
    FLUSH_THEN_WRITE,
    FLUSH_THEN_SYNC_AND_WRITE;

    val flushesTiles: Boolean
        get() = this == FLUSH_THEN_WRITE || this == FLUSH_THEN_SYNC_AND_WRITE

    val syncsGallery: Boolean
        get() = this == SYNC_GALLERY || this == FLUSH_THEN_SYNC_AND_WRITE

    val writesProject: Boolean
        get() = this == FLUSH_THEN_WRITE || this == FLUSH_THEN_SYNC_AND_WRITE
}

/** A live stroke keeps CPU flattening away from tile files it may soon rewrite. */
internal object CheckpointWorkPolicy {

    fun decide(
        project: CheckpointProjectState,
        stroke: CheckpointStrokeState,
    ): CheckpointWork {
        if (stroke == CheckpointStrokeState.LIVE) {
            return when (project) {
                CheckpointProjectState.CLEAN_EXISTING -> CheckpointWork.NONE
                CheckpointProjectState.DIRTY_EXISTING,
                CheckpointProjectState.MISSING,
                -> CheckpointWork.FLUSH_THEN_WRITE
            }
        }

        return when (project) {
            CheckpointProjectState.CLEAN_EXISTING -> CheckpointWork.SYNC_GALLERY
            CheckpointProjectState.DIRTY_EXISTING,
            CheckpointProjectState.MISSING,
            -> CheckpointWork.FLUSH_THEN_SYNC_AND_WRITE
        }
    }
}
