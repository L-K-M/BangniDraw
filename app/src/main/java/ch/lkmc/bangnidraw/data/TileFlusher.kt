package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants.CPU_MIRROR_CAP_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single coalescing tile writer (`docs/plan/06-document-and-persistence.md`
 * §6.3) — roadmap 3a's half of it: `markDirty` plus the drain. The `FlushJob`
 * queue that orders history entries against tile flushes is 3b's, because
 * there is no entry to order against until the journal exists.
 *
 * [pending] holds the latest copy per (layer, tile) — a tile dirtied five
 * times before the drainer reaches it is written once, with the latest bytes.
 * It is also the unflushed CPU mirror of §5.5 step 1: between a readback
 * landing and its write completing, these buffers are the newest CPU-side
 * truth for their keys, which is what 3b's journal capture will read.
 *
 * **Storage full** (§6.3): a failed write flips [storageFull] on and stops
 * the drain; nothing is dropped. The mirror cap is lifted — [hasMirrorRoom]
 * answers true — strokes keep committing into memory, and the pending writes
 * are retried on [retryPending] (each checkpoint now; each autosave tick once
 * 3b's clocks exist). The first successful write leaves the state.
 */
class TileFlusher(
    private val write: TileWriter,
    private val pool: TileBufferPool? = null,
) {
    /** Writes one tile, or throws [IOException] — [TileStore.write]'s shape. */
    fun interface TileWriter {
        @Throws(IOException::class)
        fun write(layer: LayerId, key: TileKey, pixels: ByteArray)
    }

    private val lock = Any()
    private val pending = LinkedHashMap<Pair<LayerId, TileKey>, CpuTile>()

    /**
     * The newest revision ever accepted per key, kept even after its write
     * completes: two readback chunks can complete out of order, so a stale
     * tile can arrive *after* its newer neighbour was already flushed, and
     * the pending map alone would no longer remember that.
     */
    private val latestRevision = HashMap<Pair<LayerId, TileKey>, Int>()

    /** Serialises the drain: the worker and [flushAll] must not interleave. */
    private val drainMutex = Mutex()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _storageFull = MutableStateFlow(false)

    /** §6.3's storage-full state, for the `err_storage_full` banner. */
    val storageFull: StateFlow<Boolean> get() = _storageFull

    /** Unflushed mirror bytes — raw, [TILE_BYTES] per pending tile. */
    @Volatile
    var pendingBytes: Long = 0L
        private set

    /**
     * Whether a commit's readback may land more tiles here (§6.3's mirror
     * cap). Advisory: [markDirty] itself never refuses pixels, because a
     * refused readback tile is lost work — the cap is for the commit path to
     * consult *before* issuing a readback, and in the storage-full state it
     * is lifted so strokes keep committing.
     */
    fun hasMirrorRoom(): Boolean = _storageFull.value || pendingBytes < CPU_MIRROR_CAP_BYTES

    /**
     * Accepts one readback tile: replaces any pending copy for the key and
     * wakes the drain. Returns false — with the buffer already recycled —
     * when [CpuTile.revision] is older than what this key has already seen,
     * which is the §10.1 out-of-order case, not an error.
     *
     * Called on the GL thread; the lock covers map mutation only, never a
     * copy (§6.3).
     */
    fun markDirty(tile: CpuTile): Boolean {
        val mapKey = tile.layerId to tile.key
        val replaced: CpuTile?
        synchronized(lock) {
            val latest = latestRevision[mapKey]
            if (latest != null && tile.revision < latest) {
                pool?.release(tile.pixels)
                return false
            }
            latestRevision[mapKey] = tile.revision
            replaced = pending.put(mapKey, tile)
            if (replaced == null) pendingBytes += TILE_BYTES
        }
        replaced?.let { pool?.release(it.pixels) }
        wake.trySend(Unit)
        return true
    }

    /** Wakes the drain to retry after a failure — the checkpoint/autosave hook. */
    fun retryPending() {
        wake.trySend(Unit)
    }

    /**
     * Starts the worker: one coroutine, [io] expected to be
     * `Dispatchers.IO.limitedParallelism(1)` (§6.3). Tests skip this and call
     * [flushAll] directly, so every assertion runs deterministically.
     */
    fun start(scope: CoroutineScope, io: CoroutineDispatcher): Job =
        scope.launch(io) {
            while (isActive) {
                wake.receive()
                drainMutex.withLock { drain() }
            }
        }

    /**
     * Drains everything pending now — the checkpoint path, which must see
     * tiles on disk before `project.json` is renamed. Returns true when the
     * mirror is empty afterwards; false means a write failed and
     * [storageFull] is set.
     */
    suspend fun flushAll(): Boolean = drainMutex.withLock {
        drain()
        synchronized(lock) { pending.isEmpty() }
    }

    /** Only under [drainMutex]. */
    private fun drain() {
        while (true) {
            val next: CpuTile
            synchronized(lock) {
                val entry = pending.entries.firstOrNull() ?: return
                next = entry.value
                pending.remove(entry.key)
                pendingBytes -= TILE_BYTES
            }
            try {
                write.write(next.layerId, next.key, next.pixels)
                pool?.release(next.pixels)
                _storageFull.value = false
            } catch (_: IOException) {
                val mapKey = next.layerId to next.key
                synchronized(lock) {
                    // Put the tile back — unless a newer copy arrived while
                    // this write was failing, in which case that copy is the
                    // one to keep and this buffer goes back to the pool.
                    if (pending.containsKey(mapKey)) {
                        pool?.release(next.pixels)
                    } else {
                        pending[mapKey] = next
                        pendingBytes += TILE_BYTES
                    }
                }
                _storageFull.value = true
                return
            }
        }
    }
}
