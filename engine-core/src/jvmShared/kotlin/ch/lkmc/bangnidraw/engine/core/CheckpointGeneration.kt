package ch.lkmc.bangnidraw.engine.core

import java.util.concurrent.atomic.AtomicLong

internal enum class CheckpointFreshness { CURRENT, STALE }

/** Tags asynchronous checkpoints so newer edits keep ownership of dirty state. */
internal class CheckpointGeneration {
    private val current = AtomicLong(0L)

    @JvmInline
    value class Snapshot internal constructor(internal val value: Long)

    fun noteChange() {
        current.incrementAndGet()
    }

    fun capture(): Snapshot = Snapshot(current.get())

    fun freshness(snapshot: Snapshot): CheckpointFreshness =
        if (current.get() == snapshot.value) {
            CheckpointFreshness.CURRENT
        } else {
            CheckpointFreshness.STALE
        }
}
