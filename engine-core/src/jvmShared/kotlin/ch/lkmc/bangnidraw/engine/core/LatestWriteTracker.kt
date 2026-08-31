package ch.lkmc.bangnidraw.engine.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe revisions used to prevent older asynchronous writes winning. */
internal class LatestWriteTracker<K> {
    private val clock = AtomicLong(0L)
    private val current = ConcurrentHashMap<K, Long>()

    fun issue(key: K): Long = clock.incrementAndGet().also { current[key] = it }

    fun isCurrent(key: K, revision: Long): Boolean = current[key] == revision

    fun complete(key: K, revision: Long) {
        current.remove(key, revision)
    }
}
