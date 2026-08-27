package ch.lkmc.bangnidraw.engine.core

/** Durable checkpoint outcome; pending states retain every dirty input. */
internal enum class CheckpointResult {
    COMPLETE,
    READBACK_PENDING,
    STORAGE_PENDING,
}

/** Retry cadence after a checkpoint could not advance its commit point. */
internal object CheckpointRetryPolicy {

    /** Readback fences normally settle within a frame; avoid a hot retry loop. */
    const val READBACK_RETRY_MS = 50L

    fun delayMs(result: CheckpointResult): Long? = when (result) {
        CheckpointResult.COMPLETE -> null
        CheckpointResult.READBACK_PENDING -> READBACK_RETRY_MS
        CheckpointResult.STORAGE_PENDING -> AutosavePolicy.QUIET_MS
    }
}
