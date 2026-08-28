package ch.lkmc.bangnidraw.engine.core

/** Publishes an asynchronous result only while its generation is newest. */
internal class LatestPublicationGate {
    private val lock = Any()
    private var currentGeneration = 0L

    internal fun nextGeneration(): Long = synchronized(lock) {
        currentGeneration += 1L
        currentGeneration
    }

    /**
     * Runs [publish] under the gate lock when [generation] is newest.
     * The callback must stay quick, non-blocking, and must not call this gate.
     */
    internal fun publishIfCurrent(generation: Long, publish: () -> Unit): Boolean =
        synchronized(lock) {
            if (generation != currentGeneration) return@synchronized false

            publish()
            true
        }
}
