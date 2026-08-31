package ch.lkmc.bangnidraw.engine.core

/** Owns SurfaceHolder redraw completions until their attachment frame is submitted. */
class RedrawCompletionTracker {

    private var released = false
    private var currentGeneration: Long? = null
    private var finishedGeneration: Long? = null
    private val pending = linkedMapOf<Long, MutableList<Runnable>>()

    /** Starts a generation and returns callbacks abandoned by the replacement. */
    @Synchronized
    fun beginGeneration(generation: Long): List<Runnable> {
        if (released) return emptyList()

        currentGeneration = generation
        finishedGeneration = null

        val abandoned = mutableListOf<Runnable>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == generation) continue

            abandoned += entry.value
            iterator.remove()
        }
        return abandoned
    }

    /** Queues a callback, or returns it when its generation cannot present. */
    @Synchronized
    fun queue(generation: Long, completion: Runnable): List<Runnable> {
        if (
            released ||
            generation != currentGeneration ||
            generation == finishedGeneration
        ) {
            return listOf(completion)
        }

        pending.getOrPut(generation, ::mutableListOf).add(completion)
        return emptyList()
    }

    /** Returns the current generation's callbacks exactly once. */
    @Synchronized
    fun finish(generation: Long): List<Runnable> {
        if (released || generation != currentGeneration) return emptyList()
        if (generation == finishedGeneration) return emptyList()

        finishedGeneration = generation
        return pending.remove(generation)?.toList().orEmpty()
    }

    /** Cancels an invalid target without touching a newer generation. */
    @Synchronized
    fun abandon(generation: Long): List<Runnable> {
        if (released || generation != currentGeneration) return emptyList()

        currentGeneration = null
        finishedGeneration = null
        return pending.remove(generation)?.toList().orEmpty()
    }

    @Synchronized
    fun destroy(): List<Runnable> {
        currentGeneration = null
        finishedGeneration = null
        return drain()
    }

    @Synchronized
    fun release(): List<Runnable> {
        if (released) return emptyList()

        released = true
        currentGeneration = null
        finishedGeneration = null
        return drain()
    }

    private fun drain(): List<Runnable> {
        val completions = pending.values.flatten()
        pending.clear()
        return completions
    }
}
