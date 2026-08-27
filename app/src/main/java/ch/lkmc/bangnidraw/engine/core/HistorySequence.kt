package ch.lkmc.bangnidraw.engine.core

/** Allocates contiguous history sequence numbers only after durable admission. */
internal class HistorySequence(initial: Long = 1L) {
    private var next = initial

    init {
        require(initial > 0L) { "history sequence must be positive" }
    }

    @Synchronized
    fun observe(): Long = next

    @Synchronized
    fun commit(candidate: Long) {
        check(candidate == next) { "expected history sequence $next, got $candidate" }
        next += 1L
    }

    @Synchronized
    fun reset(value: Long) {
        require(value > 0L) { "history sequence must be positive" }
        next = value
    }
}
