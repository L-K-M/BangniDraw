package ch.lkmc.bangnidraw.engine.core

enum class ReadbackDrainResult { COMPLETE, PENDING }

enum class StrokeCommitDecision { COMMIT, CANCEL }

/** Keeps edits from consuming GPU state that has not reached the CPU mirror. */
object ReadbackPolicy {

    fun drainResult(pending: Int): ReadbackDrainResult {
        require(pending >= NO_PENDING_READBACKS)
        return if (pending == NO_PENDING_READBACKS) {
            ReadbackDrainResult.COMPLETE
        } else {
            ReadbackDrainResult.PENDING
        }
    }

    fun strokeCommit(pending: Int): StrokeCommitDecision = when (drainResult(pending)) {
        ReadbackDrainResult.COMPLETE -> StrokeCommitDecision.COMMIT
        ReadbackDrainResult.PENDING -> StrokeCommitDecision.CANCEL
    }

    private const val NO_PENDING_READBACKS = 0
}
