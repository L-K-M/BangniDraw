package ch.lkmc.bangnidraw.engine.core

/** Publishes an asynchronous result only while its generation is newest. */
internal class LatestPublicationGate {
    private val lock = Any()
    private var currentGeneration = 0L

    internal fun nextGeneration(): Long = synchronized(lock) {
        currentGeneration += 1L
        currentGeneration
    }

    internal fun publishIfCurrent(generation: Long, publish: () -> Unit): Boolean =
        synchronized(lock) {
            if (generation != currentGeneration) return@synchronized false

            publish()
            true
        }
}
