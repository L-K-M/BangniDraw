package ch.lkmc.bangnidraw.ui.canvas

/** Result of starting the one-way session release. */
internal enum class ReleaseStart { STARTED, ALREADY_STARTED }

/** Where a readback request was resolved. */
internal enum class ReadbackRequest { DISPATCHED, DEFERRED, RELEASED }

/** Keeps readbacks behind renderer cleanup once release starts. */
internal class SessionReleaseGate<T : Any> {
    private enum class Phase { ACTIVE, RELEASING, RELEASED }

    private var phase = Phase.ACTIVE
    private var releaseResult: T? = null
    private val releaseWaiters = mutableListOf<(T) -> Unit>()

    /** Dispatch and release are serialized so the GL queue preserves order. */
    fun requestReadback(
        dispatch: () -> Unit,
        afterRelease: (T) -> Unit,
    ): ReadbackRequest {
        var completed: T? = null
        val request = synchronized(this) {
            when (phase) {
                Phase.ACTIVE -> {
                    dispatch()
                    ReadbackRequest.DISPATCHED
                }
                Phase.RELEASING -> {
                    releaseWaiters += afterRelease
                    ReadbackRequest.DEFERRED
                }
                Phase.RELEASED -> {
                    completed = releaseResult
                    ReadbackRequest.RELEASED
                }
            }
        }

        completed?.let(afterRelease)
        return request
    }

    @Synchronized
    fun beginRelease(): ReleaseStart {
        if (phase != Phase.ACTIVE) return ReleaseStart.ALREADY_STARTED

        phase = Phase.RELEASING
        return ReleaseStart.STARTED
    }

    fun completeRelease(result: T) {
        val waiters = synchronized(this) {
            if (phase != Phase.RELEASING) return

            phase = Phase.RELEASED
            releaseResult = result
            releaseWaiters.toList().also { releaseWaiters.clear() }
        }
        waiters.forEach { it(result) }
    }

    /** Registers teardown work even while the session is still active. */
    fun afterRelease(callback: (T) -> Unit) {
        var completed: T? = null
        synchronized(this) {
            if (phase == Phase.RELEASED) completed = releaseResult
            else releaseWaiters += callback
        }

        completed?.let(callback)
    }
}
