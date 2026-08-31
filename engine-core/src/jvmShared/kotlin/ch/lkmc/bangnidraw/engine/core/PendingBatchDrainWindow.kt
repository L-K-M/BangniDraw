package ch.lkmc.bangnidraw.engine.core

enum class PendingBatchDrainScope {
    FRAME_SNAPSHOT,
    EXHAUSTIVE,
}

/** Bounds one live frame while terminal stroke drains still empty the queue. */
class PendingBatchDrainWindow {

    private var scope = PendingBatchDrainScope.FRAME_SNAPSHOT
    private var remaining = 0

    fun begin(scope: PendingBatchDrainScope, pendingAtStart: Int) {
        require(pendingAtStart >= 0) { "pending batch count must not be negative" }

        this.scope = scope
        remaining = pendingAtStart
    }

    fun canPoll(): Boolean {
        if (scope == PendingBatchDrainScope.EXHAUSTIVE) return true
        if (remaining == 0) return false

        remaining--
        return true
    }
}
