package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.PerfConstants.CPU_MIRROR_CAP_BYTES
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
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
 * The single writer (`docs/plan/06-document-and-persistence.md` §6.3): one
 * coalescing mirror of unflushed tiles, and one ordered job queue that is the
 * whole §5.6 crash-safety story —
 *
 * 1. a step's "before" tiles not held in the mirror are read from disk *into
 *    the entry* before any flush of those keys,
 * 2. `<seq>.entry` (and `.redo` before restored tiles) is written tmp+rename,
 * 3. the step's readback is awaited, then the tiles the step changed are
 *    flushed,
 * 4. `project.json` last, at the checkpoint.
 *
 * Ordering holds because tiles are flushed **only** inside jobs and jobs run
 * strictly in queue order on one coroutine: when [FlushJob.WriteEntry] for
 * stroke N runs, the disk still holds the pre-N state for every key its
 * mirror capture missed. There is no idle flush — a tile always belongs to
 * some job's step 3, or to a checkpoint.
 *
 * **Storage full** (§6.3): a failed write flips [storageFull] and leaves the
 * tiles pending; nothing is dropped. The mirror cap is lifted
 * ([hasMirrorRoom]), strokes keep committing, [retryPending] retries on each
 * checkpoint/autosave tick, and the first successful write clears the state.
 */
class TileFlusher(
    private val write: TileWriter,
    private val pool: TileBufferPool? = null,
) {
    enum class ReadbackResult { COMPLETE, PENDING }
    enum class StepResult { COMPLETE, DEFERRED }

    /** Writes one tile, or throws [IOException] — [TileStore.write]'s shape. */
    fun interface TileWriter {
        @Throws(IOException::class)
        fun write(layer: LayerId, key: TileKey, pixels: ByteArray)
    }

    /** The ordered work of §6.3; run strictly FIFO by the single worker. */
    sealed interface FlushJob {
        /**
         * One committed stroke's §5.6 sequence. [mirrorBefore] holds raw
         * pixel copies captured from the mirror at commit time (§5.5 step 1);
         * every other key of [entry] is resolved from disk (step 2 of §5.5)
         * or recorded as empty. [awaitReadback] must return only once the
         * step's own readback has landed in the mirror; [changedKeys] then
         * flushes outputs whose owners differ from the entry payload, such as
         * a flattened result. [result] completes with the stamped entry — or
         * null when the entry could not be written, which is a storage-full
         * condition, not a crash. [completion] reports whether readback and
         * disk flush both finished; destructive followers must wait for
         * `COMPLETE`.
         */
        class WriteEntry(
            val entry: HistoryEntry,
            val seq: Long,
            val ts: Long,
            val mirrorBefore: Map<Pair<LayerId, TileKey>, ByteArray>,
            val changedKeys: List<Pair<LayerId, TileKey>> = HistoryCodec.payloadKeys(entry),
            val awaitReadback: suspend () -> ReadbackResult,
            val result: CompletableDeferred<HistoryEntry?> = CompletableDeferred(),
            val completion: CompletableDeferred<StepResult> = CompletableDeferred(),
        ) : FlushJob

        /**
         * The first undo of a step captures its "after" into `<seq>.redo`
         * (§5.4), before the restored tiles are flushed — which queue order
         * guarantees, because the restore's [FlushKeys] is enqueued behind
         * this. [result] is the sidecar's size for the journal's accounting,
         * or null on a failed write.
         */
        class WriteRedo(
            val entry: HistoryEntry,
            val mirrorCurrent: Map<Pair<LayerId, TileKey>, ByteArray>,
            val result: CompletableDeferred<Long?> = CompletableDeferred(),
        ) : FlushJob

        /** Flush these pending keys now — restored tiles after an undo/redo. */
        class FlushKeys(val keys: List<Pair<LayerId, TileKey>>) : FlushJob

        /**
         * §5.6 step 2's tail for a layer deletion: the directory goes only
         * after the entry holding its tiles was written, which queue order
         * provides. No producer until the layer UI lands; the job exists
         * because §6.3 names it and the ordering is this class's contract.
         */
        class DeleteLayerDir(val dir: File) : FlushJob

        /**
         * Flush everything pending; [done] reports whether the mirror is
         * empty afterwards. The checkpoint enqueues this and writes
         * `project.json` only on completion — step 4's "last".
         */
        class Checkpoint(val done: CompletableDeferred<Boolean> = CompletableDeferred()) : FlushJob
    }

    /** Resolves a key's on-disk "before"/"current" bytes for entry payloads. */
    fun interface DiskReader {
        /** The encoded `.tile` bytes verbatim, or null when absent/corrupt. */
        fun read(layer: LayerId, key: TileKey): ByteArray?
    }

    var diskReader: DiskReader = DiskReader { _, _ -> null }

    internal var historyStore: HistoryStore? = null

    private val lock = Any()
    private val pending = LinkedHashMap<Pair<LayerId, TileKey>, CpuTile>()

    /**
     * The newest revision ever accepted per key, kept even after its write
     * completes: two readback chunks can complete out of order, so a stale
     * tile can arrive *after* its newer neighbour was already flushed, and
     * the pending map alone would no longer remember that.
     */
    private val latestRevision = HashMap<Pair<LayerId, TileKey>, Int>()

    /** Serialises job execution: the worker and [runQueued] must not interleave. */
    private val jobMutex = Mutex()

    /**
     * §6.3's bounded queue (`capacity = 64`): enqueue suspends the ViewModel
     * side when IO lags rather than growing without bound.
     */
    private val queue = Channel<FlushJob>(capacity = 64)

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
     * Accepts one readback tile into the mirror. Returns false — with the
     * buffer already recycled — when [CpuTile.revision] is older than what
     * this key has already seen (§10.1's out-of-order case, not an error).
     *
     * Called on the GL thread; the lock covers map mutation only, never a
     * copy (§6.3). No flush is triggered: the tile belongs to its stroke's
     * [FlushJob.WriteEntry], already in the queue.
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
        return true
    }

    /**
     * Raw pixel copies of the mirror's current contents for [keys] — §5.5
     * step 1 of the capture rule, taken on the GL thread at commit, *before*
     * the stroke's own readback can overwrite these keys. Copies, not
     * references: the worker recycles a pending buffer the moment its write
     * lands, and a capture holding the reference would read recycled bytes.
     */
    fun captureMirror(keys: List<Pair<LayerId, TileKey>>): Map<Pair<LayerId, TileKey>, ByteArray> {
        val out = HashMap<Pair<LayerId, TileKey>, ByteArray>()
        synchronized(lock) {
            for (key in keys) {
                val tile = pending[key] ?: continue
                out[key] = tile.pixels.copyOf()
            }
        }
        return out
    }

    /** Enqueues [job]; suspends when the queue is at §6.3's bound. */
    suspend fun enqueue(job: FlushJob) {
        queue.send(job)
    }

    /** Enqueues from the GL thread, refusing instead of blocking when full. */
    fun enqueueNow(job: FlushJob): Boolean = queue.trySend(job).isSuccess

    /** Wakes a retry of everything pending — the checkpoint/autosave hook. */
    fun retryPending() {
        queue.trySend(FlushJob.Checkpoint())
    }

    /**
     * Starts the worker: one coroutine, [io] expected to be
     * `Dispatchers.IO.limitedParallelism(1)` (§6.3). Tests skip this and call
     * [runQueued], so every assertion runs deterministically.
     */
    fun start(scope: CoroutineScope, io: CoroutineDispatcher): Job =
        scope.launch(io) {
            while (isActive) {
                val job = queue.receive()
                jobMutex.withLock { run(job) }
            }
        }

    /** Drains every job queued so far, inline — the tests' worker. */
    suspend fun runQueued() {
        while (true) {
            val job = queue.tryReceive().getOrNull() ?: return
            jobMutex.withLock { run(job) }
        }
    }

    /**
     * Enqueues a [FlushJob.Checkpoint] and waits it out: on return every
     * job enqueued before this call has run, in order, and the answer says
     * whether the mirror drained (false = storage-full; `project.json` may
     * still be written — metadata is not pixels — and the retry keeps the
     * tiles).
     */
    suspend fun checkpointFlush(): Boolean {
        val job = FlushJob.Checkpoint()
        queue.send(job)
        return job.done.await()
    }

    // --------------------------------------------------------------- worker

    private suspend fun run(job: FlushJob) {
        when (job) {
            is FlushJob.WriteEntry -> runWriteEntry(job)
            is FlushJob.WriteRedo -> runWriteRedo(job)
            is FlushJob.FlushKeys -> flushKeys(job.keys)
            is FlushJob.DeleteLayerDir -> job.dir.deleteRecursively()
            is FlushJob.Checkpoint -> {
                flushKeys(synchronized(lock) { pending.keys.toList() })
                job.done.complete(synchronized(lock) { pending.isEmpty() })
            }
        }
    }

    private suspend fun runWriteEntry(job: FlushJob.WriteEntry) {
        val store = historyStore
        if (store == null) {
            job.result.complete(null)
            job.completion.complete(StepResult.DEFERRED)
            return
        }
        val keys = HistoryCodec.payloadKeys(job.entry)
        val payloads = keys.map { key ->
            val raw = job.mirrorBefore[key]
            val encoded = when {
                // §5.5's order: the unflushed mirror first — it holds the
                // tile as of the last commit…
                raw != null -> if (TileCodec.isAllZero(raw)) EMPTY else TileCodec.encode(raw)
                // …else the .tile file: already deflated, header included,
                // copied verbatim (§5.6 step 1 — no inflate/deflate)…
                else -> diskReader.read(key.first, key.second)
                    // …else the tile was empty before (len 0), and undo
                    // deletes it.
                    ?: EMPTY
            }
            HistoryStore.Payload(key.first, key.second, encoded)
        }
        val stamped = try {
            store.append(job.entry, job.seq, job.ts, payloads)
        } catch (_: IOException) {
            _storageFull.value = true
            job.result.complete(null)
            job.completion.complete(StepResult.DEFERRED)
            return
        }
        job.result.complete(stamped)
        // §5.6 step 3: the step's own pixels land in the mirror, then flush.
        val readback = job.awaitReadback()
        if (readback == ReadbackResult.PENDING) {
            job.completion.complete(StepResult.DEFERRED)
            return
        }
        val result = if (flushKeys(job.changedKeys)) {
            StepResult.COMPLETE
        } else {
            StepResult.DEFERRED
        }
        job.completion.complete(result)
    }

    private suspend fun runWriteRedo(job: FlushJob.WriteRedo) {
        val store = historyStore
        if (store == null) {
            job.result.complete(null)
            return
        }
        val keys = HistoryCodec.redoPayloadKeys(job.entry)
        val payloads = keys.map { key ->
            val raw = job.mirrorCurrent[key]
            val encoded = when {
                raw != null -> if (TileCodec.isAllZero(raw)) EMPTY else TileCodec.encode(raw)
                else -> diskReader.read(key.first, key.second) ?: EMPTY
            }
            HistoryStore.Payload(key.first, key.second, encoded)
        }
        val bytes = try {
            store.writeRedo(job.entry, payloads)
        } catch (_: IOException) {
            _storageFull.value = true
            job.result.complete(null)
            return
        }
        job.result.complete(bytes)
    }

    /** Only under [jobMutex]. One failed write stops the pass; nothing is lost. */
    private fun flushKeys(keys: List<Pair<LayerId, TileKey>>): Boolean {
        for (mapKey in keys) {
            val current = synchronized(lock) {
                pending.remove(mapKey)?.also { pendingBytes -= TILE_BYTES }
            } ?: continue
            try {
                write.write(current.layerId, current.key, current.pixels)
                pool?.release(current.pixels)
                _storageFull.value = false
            } catch (_: IOException) {
                synchronized(lock) {
                    if (pending.containsKey(mapKey)) {
                        pool?.release(current.pixels)
                    } else {
                        pending[mapKey] = current
                        pendingBytes += TILE_BYTES
                    }
                }
                _storageFull.value = true
                return false
            }
        }
        return true
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
