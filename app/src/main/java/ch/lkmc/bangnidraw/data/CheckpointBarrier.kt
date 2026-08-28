package ch.lkmc.bangnidraw.data

internal enum class CheckpointResult { COMMITTED, DEFERRED }

/** Keeps the project commit behind every source of newer tile bytes. */
internal object CheckpointBarrier {

    suspend fun commitWhenFlushed(
        awaitReadbacks: suspend () -> TileFlusher.ReadbackResult,
        flushTiles: suspend () -> Boolean,
        commitProject: suspend () -> Unit,
    ): CheckpointResult {
        if (awaitReadbacks() == TileFlusher.ReadbackResult.PENDING) {
            return CheckpointResult.DEFERRED
        }
        if (!flushTiles()) return CheckpointResult.DEFERRED

        commitProject()
        return CheckpointResult.COMMITTED
    }
}
