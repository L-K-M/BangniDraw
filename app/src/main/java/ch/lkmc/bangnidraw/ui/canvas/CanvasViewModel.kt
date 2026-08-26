package ch.lkmc.bangnidraw.ui.canvas

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.ApplicationScope
import ch.lkmc.bangnidraw.data.CpuTile
import ch.lkmc.bangnidraw.data.HistoryCodec
import ch.lkmc.bangnidraw.data.HistoryPixels
import ch.lkmc.bangnidraw.data.HistoryRecord
import ch.lkmc.bangnidraw.data.HistoryStore
import ch.lkmc.bangnidraw.data.ProjectStore
import ch.lkmc.bangnidraw.data.TileBufferPool
import ch.lkmc.bangnidraw.data.TileFlusher
import ch.lkmc.bangnidraw.data.TileStore
import ch.lkmc.bangnidraw.data.highestDefaultNameIn
import ch.lkmc.bangnidraw.engine.core.AutosavePolicy
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.HistoryJournal
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.ui.navigation.CanvasRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Canvas screen's persistence half (roadmap 3a + 3b): opens or creates
 * the routed project, streams its tiles into the engine, funnels §10.1's
 * readback into the [TileFlusher], journals every stroke, applies undo/redo,
 * and checkpoints `project.json` on leave, `ON_STOP`, and
 * `AutosavePolicy`'s quiet/ceiling clocks
 * (`docs/plan/06-document-and-persistence.md` §6.2).
 *
 * Writes run on [appScope] under [NonCancellable], not [viewModelScope]: the
 * leave checkpoint is what makes leaving safe, so the navigation pop that
 * clears this ViewModel must not cancel it.
 *
 * Threading: [journal], [document] and the UI state are main-thread; the
 * flusher's jobs run on its single IO worker; [onStrokeMerged] runs on the
 * GL thread and only captures + enqueues.
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ProjectStore,
    @ApplicationScope private val appScope: CoroutineScope,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState

        /** The painting could not be opened; it is never silently replaced. */
        data class Failed(@StringRes val message: Int) : UiState

        data class Ready(
            val canvas: CanvasSize,
            val stack: LayerStack,
            val paperColor: Int,
            /** One honest toast about degraded content, or null (06 §4). */
            @StringRes val warning: Int? = null,
            val canUndo: Boolean = false,
            val canRedo: Boolean = false,
            /** The "history capped at N steps / M MB" readout's numbers. */
            val historySteps: Int = 0,
            val historyBytes: Long = 0L,
            val historyMaxSteps: Int = 0,
            val historyMaxBytes: Long = 0L,
        ) : UiState
    }

    private val projectId: String = savedStateHandle.toRoute<CanvasRoute>().projectId

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val pool = TileBufferPool()

    /** Shared with every [EngineSession] of this screen — see its KDoc. */
    val revisions = AtomicInteger(0)

    private val flusher = TileFlusher(
        write = { layer, key, pixels ->
            TileStore(store.layerDir(projectId, layer)).write(key, pixels)
        },
        pool = pool,
    ).also {
        it.start(appScope, Dispatchers.IO.limitedParallelism(1))
    }

    /** §6.3's storage-full state, for the `err_storage_full` banner. */
    val storageFull: StateFlow<Boolean> = flusher.storageFull

    /**
     * The document as of the last model change. Written on the main thread
     * (load, stroke bookkeeping) and read by checkpoint coroutines; immutable
     * value, `@Volatile` reference.
     */
    @Volatile
    private var document: Document? = null

    // Written once during open() on the IO loader and read on the main
    // thread afterwards; @Volatile is the publication edge (the StateFlow
    // emission would usually provide one, but that is incidental, not a
    // contract).
    @Volatile
    private var historyStore: HistoryStore? = null

    @Volatile
    private var historyPixels: HistoryPixels? = null

    /**
     * Confined to the main thread after publication, like the document
     * cursor it mirrors; only the open() loader writes the reference.
     */
    @Volatile
    private var journal: HistoryJournal? = null

    /** Journal seqs whose files go once the next `project.json` lands (§5.6). */
    private val pendingDeletes = ArrayList<Long>()

    /** Next `<seq>` to allocate; never reused within a project (06 §3). */
    private val nextSeq = AtomicLong(1L)

    /** Layer/tile keys the readback has delivered since the last checkpoint fold. */
    private val strokeTiles = ConcurrentHashMap.newKeySet<Pair<LayerId, TileKey>>()

    /** True when pixels or metadata changed since the last successful checkpoint. */
    @Volatile
    private var dirty = false

    /** When the document first differed from disk — the ceiling clock's anchor. */
    @Volatile
    private var dirtySinceMs: Long? = null

    private var autosaveJob: Job? = null

    /** One undo/redo applies at a time; requests during an apply are dropped. */
    private var applyBusy = false

    private var session: EngineSession? = null

    private val checkpointMutex = Mutex()

    init {
        viewModelScope.launch(Dispatchers.IO) { open() }
    }

    private fun open() {
        when (val result = store.load(projectId)) {
            is ProjectStore.LoadResult.Loaded -> openLoaded(result)
            is ProjectStore.LoadResult.Failed -> when (result.reason) {
                ProjectStore.FailureReason.NOT_FOUND -> {
                    if (!store.isValidId(projectId)) {
                        _uiState.value = UiState.Failed(R.string.canvas_open_failed)
                        return
                    }
                    val now = System.currentTimeMillis()
                    val fresh = Document(
                        id = projectId,
                        title = "",
                        width = DEFAULT_EDGE,
                        height = DEFAULT_EDGE,
                        paperColor = PAPER_WHITE,
                        stack = LayerStack.initial { LayerId(UUID.randomUUID().toString()) },
                        createdAt = now,
                        updatedAt = now,
                    )
                    document = fresh
                    wireHistory(fresh, HistoryStore.Loaded(emptyList(), 0), HistoryRecord())
                    // A fresh painting has everything unwritten: the leave
                    // checkpoint creates the folder, which is when it first
                    // appears on the shelf.
                    dirty = true
                    _uiState.value = readyState(fresh, warning = null)
                }
                ProjectStore.FailureReason.NEWER_VERSION ->
                    _uiState.value = UiState.Failed(R.string.canvas_newer_version)
                ProjectStore.FailureReason.BAD_ID,
                ProjectStore.FailureReason.UNREADABLE,
                -> _uiState.value = UiState.Failed(R.string.canvas_open_failed)
            }
        }
    }

    private fun openLoaded(result: ProjectStore.LoadResult.Loaded) {
        val loadedHistory = HistoryStore(historyDir(result.document.id))
            .load(result.history)
        // The nextName obligation's replay half (roadmap 3b): the recovered
        // journal can carry default names a stale checkpoint never saw —
        // including on layers the journal itself deletes — and the counter
        // must clear every one of them, or a reopen reissues a live name.
        val floor = highestDefaultNameIn(loadedHistory.entries) + 1
        val doc = result.document.let {
            if (it.stack.nextName >= floor) it
            else it.copy(stack = it.stack.copy(nextName = floor))
        }.copy(historyCursor = loadedHistory.cursor)
        document = doc
        wireHistory(doc, loadedHistory, result.history)
        _uiState.value = readyState(
            doc,
            warningFor(unreadableLayers = result.unreadableLayers, unreadableTiles = 0),
        )
    }

    private fun historyDir(id: String): File = File(store.projectDir(id), "history")

    private fun wireHistory(
        doc: Document,
        loaded: HistoryStore.Loaded,
        record: HistoryRecord,
    ) {
        val history = HistoryStore(historyDir(doc.id))
        historyStore = history
        flusher.historyStore = history
        flusher.diskReader = TileFlusher.DiskReader { layer, key ->
            // §5.6 step 1: the .tile bytes verbatim — already deflated,
            // header included, no inflate/deflate. A corrupt file's bytes
            // stay corrupt in the entry and degrade at apply time, exactly
            // as they would have on screen.
            File(store.layerDir(doc.id, layer), TileStore.fileName(key))
                .takeIf { it.isFile }
                ?.let { runCatching { it.readBytes() }.getOrNull() }
        }
        historyPixels = HistoryPixels(flusher, history)
        val budget = MemoryBudget.compute(
            readDeviceMemory(context),
            CanvasSize(doc.width, doc.height),
        )
        journalLimits = HistoryJournal.Limits(budget.historyMaxSteps, budget.historyMaxBytes)
        journal = HistoryJournal(journalLimits, loaded.entries, loaded.cursor)
        nextSeq.set(maxOf(record.nextSeq, (loaded.entries.lastOrNull()?.seq ?: 0L) + 1L))
    }

    @Volatile
    private var journalLimits = HistoryJournal.Limits(1, 0L)

    private fun readyState(doc: Document, @StringRes warning: Int?): UiState.Ready {
        val j = journal
        return UiState.Ready(
            canvas = CanvasSize(doc.width, doc.height),
            stack = doc.stack,
            paperColor = doc.paperColor,
            warning = warning,
            canUndo = j?.canUndo() == true && !applyBusy,
            canRedo = j?.canRedo() == true && !applyBusy,
            historySteps = j?.stats()?.entries ?: 0,
            historyBytes = j?.stats()?.bytes ?: 0L,
            historyMaxSteps = journalLimits.maxEntries,
            historyMaxBytes = journalLimits.maxBytes,
        )
    }

    /** Main thread: refresh the Ready state's journal-driven fields. */
    private fun updateHistoryUi() {
        val state = _uiState.value
        if (state !is UiState.Ready) return
        val j = journal ?: return
        _uiState.value = state.copy(
            canUndo = j.canUndo() && !applyBusy,
            canRedo = j.canRedo() && !applyBusy,
            historySteps = j.stats().entries,
            historyBytes = j.stats().bytes,
        )
    }

    @StringRes
    private fun warningFor(unreadableLayers: Int, unreadableTiles: Int): Int? = when {
        // The layer message wins when both apply: a lost layer is the larger
        // loss, and one toast per open is the ceiling (06 §4).
        unreadableLayers > 0 -> R.string.err_layers_unreadable
        unreadableTiles > 0 -> R.string.err_tiles_unreadable
        else -> null
    }

    // ----------------------------------------------------------- the stroke

    /**
     * §10.1's sink, called on the GL thread once per merged tile. Copies the
     * mapped bytes into a pooled buffer — the mapping dies when this returns —
     * and hands them to the flusher.
     */
    fun onTileReadback(layer: LayerId, key: TileKey, revision: Int, pixels: ByteBuffer) {
        if (pixels.remaining() != TILE_BYTES) return
        val copy = pool.acquire()
        pixels.get(copy)
        strokeTiles.add(layer to key)
        dirty = true
        flusher.markDirty(CpuTile(layer, key, revision, copy))
    }

    /**
     * §10.2's capture, on the GL thread at commit: the mirror still holds the
     * pre-stroke state for the merged keys (the engine finished every earlier
     * readback and has not mapped this one), so this is the one moment the
     * entry's "before" can be taken without a GPU round trip. Everything
     * slow — disk reads, the entry write, the journal push — rides the job
     * queue and the app scope.
     */
    private fun onStrokeMerged(spec: StrokeSpec, keys: List<TileKey>, @Suppress("UNUSED_PARAMETER") revision: Int) {
        if (keys.isEmpty()) return
        val doc = document ?: return
        val activeId = doc.stack.active.id
        val entry = HistoryEntry.Stroke(
            activeBefore = activeId,
            activeAfter = activeId,
            layerId = spec.layerId,
            tiles = keys,
        )
        val job = TileFlusher.FlushJob.WriteEntry(
            entry = entry,
            seq = nextSeq.getAndIncrement(),
            ts = System.currentTimeMillis(),
            mirrorBefore = flusher.captureMirror(keys.map { spec.layerId to it }),
            awaitReadback = { awaitReadbacks() },
        )
        appScope.launch {
            flusher.enqueue(job)
            val stamped = job.result.await() ?: return@launch
            withContext(Dispatchers.Main) {
                val j = journal ?: return@withContext
                val result = j.push(stamped)
                // §5.6: the files go only after the project.json that no
                // longer references them — deleting now would leave the
                // checkpointed record naming entries that are gone, and the
                // next load would read that as a torn journal.
                pendingDeletes += result.truncated
                pendingDeletes += result.pruned
                document = document?.copy(historyCursor = j.cursor)
                updateHistoryUi()
            }
        }
    }

    /** Called at pen-up on the main thread; arms the autosave clocks. */
    fun onStrokeCommitted() {
        dirty = true
        noteChange()
    }

    // ------------------------------------------------------------ undo/redo

    fun undo() = applyHistory(redo = false)

    fun redo() = applyHistory(redo = true)

    /** Main thread. One apply at a time; a request mid-apply is dropped. */
    private fun applyHistory(redo: Boolean) {
        if (applyBusy) return
        val j = journal ?: return
        val pixels = historyPixels ?: return
        val entry = (if (redo) j.redo() else j.undo()) ?: return
        applyBusy = true
        updateHistoryUi()
        appScope.launch {
            var redoBytes: kotlinx.coroutines.CompletableDeferred<Long?>? = null
            val restores = when (entry) {
                is HistoryEntry.Stroke, is HistoryEntry.Fill ->
                    if (redo) {
                        pixels.beforeRedo(entry)
                    } else {
                        pixels.beforeUndo(entry)?.let { undo ->
                            redoBytes = undo.redoBytes
                            undo.restores
                        }
                    }
                else -> {
                    // No producer for any other kind exists yet (the layer UI
                    // is step 6); a hand-crafted journal degrades to "this
                    // step cannot be applied" rather than guessing.
                    android.util.Log.w(TAG, "cannot apply ${HistoryCodec.kindOf(entry)} yet")
                    null
                }
            }
            if (restores != null) {
                for (restore in restores) {
                    applyRestore(restore)
                }
                pixels.flushRestored(restores)
            }
            // The worker has the WriteRedo ahead of the restore's flush; by
            // the time it answers, the sidecar is on disk (or storage-full
            // ate it, and the byte count is honestly absent).
            val sidecarBytes = redoBytes?.await()
            withContext(Dispatchers.Main) {
                if (sidecarBytes != null) journal?.noteRedoBytes(entry.seq, sidecarBytes)
                if (restores == null) {
                    // Put the cursor back: the step could not be applied, and
                    // a cursor that moved anyway would lie to the next
                    // checkpoint about what is in effect (§5.6: validated
                    // when applied; the journal truncates from here).
                    if (redo) journal?.undo() else journal?.redo()
                } else {
                    document = document?.copy(historyCursor = journal?.cursor ?: 0)
                    dirty = true
                    noteChange()
                }
                applyBusy = false
                updateHistoryUi()
            }
        }
    }

    /** Uploads one restore to the GPU and mirrors it for the flusher. */
    private suspend fun applyRestore(restore: HistoryPixels.Restore) {
        val engine = session
        val uploads = ArrayList<Pair<TileKey, ByteArray>>(restore.tiles.size)
        for ((key, raw) in restore.tiles) {
            // null = the tile becomes empty: zeros on the GPU, a deleted
            // file on disk (TileStore.write's all-zero rule).
            val pixels = raw ?: ByteArray(TILE_BYTES)
            uploads += key to pixels
            // Its own copy: the flusher recycles its buffer into the pool
            // after the write, and the GL upload may still be queued.
            val mirror = pool.acquire()
            pixels.copyInto(mirror)
            strokeTiles.add(restore.layer to key)
            flusher.markDirty(
                CpuTile(restore.layer, key, revisions.incrementAndGet(), mirror),
            )
        }
        if (engine != null) {
            var from = 0
            while (from < uploads.size) {
                val to = minOf(from + UPLOAD_BATCH, uploads.size)
                engine.uploadTiles(
                    restore.layer,
                    uploads.subList(from, to).toList(),
                    last = to == uploads.size,
                )
                from = to
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * The engine for this screen instance arrived (or left, with null). On
     * arrival the painting's tiles are streamed from disk into the pool —
     * §5.7's reopen path — and the commit capture hook is installed.
     */
    fun attachSession(next: EngineSession?) {
        session?.onStrokeMerged = null
        session = next
        val doc = document ?: return
        if (next != null) {
            next.onStrokeMerged = { spec, keys, revision -> onStrokeMerged(spec, keys, revision) }
            viewModelScope.launch(Dispatchers.IO) { streamTiles(next, doc) }
        }
    }

    /**
     * The leave checkpoint (§6.2's Leave row): flush everything, then run
     * [afterWrite] on the main thread — the caller navigates in it, so the
     * Studio never lists a stale shelf.
     */
    fun leave(afterWrite: () -> Unit) {
        appScope.launch {
            withContext(NonCancellable) { checkpoint() }
            withContext(Dispatchers.Main) { afterWrite() }
        }
    }

    /** The `ON_STOP` checkpoint (§6.2): fire-and-forget, cancellation-proof. */
    fun checkpointNow() {
        appScope.launch { withContext(NonCancellable) { checkpoint() } }
    }

    override fun onCleared() {
        // Belt and braces behind [leave]: whatever is still unwritten when the
        // screen is torn down gets one more drain. The session is gone by now,
        // so there is no readback left to wait on — release() already
        // delivered or dropped it.
        session?.onStrokeMerged = null
        session = null
        appScope.launch { withContext(NonCancellable) { checkpoint() } }
    }

    /**
     * §6.2's quiet and ceiling clocks (roadmap 3b): every content change
     * re-arms one delayed checkpoint, one quiet window out but never past
     * what remains of the ceiling. Main thread.
     */
    private fun noteChange() {
        val now = System.currentTimeMillis()
        val since = dirtySinceMs ?: now.also { dirtySinceMs = it }
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AutosavePolicy.delayMs(now - since))
            checkpointNow()
        }
    }

    private suspend fun checkpoint() {
        val doc = document ?: return
        checkpointMutex.withLock {
            if (!dirty && store.exists(doc.id)) return
            // §5.6's order: (readbacks land) → queued jobs and tiles flushed
            // → project.json last, the commit point → only then the files a
            // truncation or pruning dropped.
            awaitReadbacks()
            flusher.checkpointFlush()
            val now = System.currentTimeMillis()
            val folded = fold(document ?: return, now)
            document = folded
            val (record, deletes) = withContext(Dispatchers.Main) {
                val j = journal
                val snapshot = if (j == null) {
                    HistoryRecord(cursor = folded.historyCursor)
                } else {
                    HistoryRecord(
                        cursor = j.cursor,
                        nextSeq = nextSeq.get(),
                        oldestSeq = j.entries.firstOrNull()?.seq ?: nextSeq.get(),
                        entries = j.stats().entries,
                        bytes = j.stats().bytes,
                    )
                }
                snapshot to ArrayList(pendingDeletes)
            }
            try {
                store.checkpoint(folded, record)
                dirty = false
                dirtySinceMs = null
                // Now — and only now — the dropped entries' files (§5.6).
                if (deletes.isNotEmpty()) {
                    historyStore?.delete(deletes)
                    withContext(Dispatchers.Main) { pendingDeletes.removeAll(deletes.toSet()) }
                }
            } catch (_: java.io.IOException) {
                // Same family as a failed tile write: the storage-full state
                // and its retry-on-next-checkpoint own this. `dirty` stays
                // true, so the next trigger tries again.
            }
        }
    }

    /** The model's tile sets catch up with what the readback delivered. */
    private fun fold(doc: Document, now: Long): Document {
        if (strokeTiles.isEmpty()) return doc.copy(updatedAt = if (dirty) now else doc.updatedAt)
        val byLayer = HashMap<LayerId, MutableSet<TileKey>>()
        val iterator = strokeTiles.iterator()
        while (iterator.hasNext()) {
            val (layer, key) = iterator.next()
            byLayer.getOrPut(layer) { HashSet() }.add(key)
            iterator.remove()
        }
        val layers = doc.stack.layers.map { layer ->
            val extra = byLayer[layer.id] ?: return@map layer
            layer.copy(tiles = layer.tiles + extra)
        }
        return doc.copy(
            stack = doc.stack.copy(layers = layers),
            updatedAt = now,
        )
    }

    /**
     * Waits until the engine has mapped every in-flight readback, bounded: a
     * released session runs the callback immediately, and a wedged GPU is
     * capped by the timeout — the checkpoint then writes what has landed,
     * which is still a consistent (merely older) painting.
     */
    private suspend fun awaitReadbacks() {
        val engine = session ?: return
        val done = CompletableDeferred<Unit>()
        engine.finishReadback { done.complete(Unit) }
        withTimeoutOrNull(READBACK_WAIT_MS) { done.await() }
    }

    private suspend fun streamTiles(engine: EngineSession, doc: Document) {
        // Uploads before the first frame would find no context and be
        // dropped; wait out the engine's own probe. An unsupported device
        // never flips isReady, so the wait is bounded and the uploads are
        // skipped — that screen shows no canvas anyway.
        withTimeoutOrNull(READY_WAIT_MS) {
            while (!engine.isEngineReady()) delay(16)
        } ?: return
        var corruptTiles = 0
        val layers = doc.stack.layers
        for ((index, layer) in layers.withIndex()) {
            val tiles = TileStore(store.layerDir(doc.id, layer.id))
            val batch = ArrayList<Pair<TileKey, ByteArray>>(UPLOAD_BATCH)
            for (key in layer.tiles) {
                when (val read = tiles.read(key)) {
                    is TileStore.Read.Pixels -> batch += key to read.pixels
                    TileStore.Read.Corrupt -> corruptTiles += 1
                    TileStore.Read.Empty -> Unit
                }
                if (batch.size >= UPLOAD_BATCH) {
                    engine.uploadTiles(layer.id, ArrayList(batch), last = false)
                    batch.clear()
                }
            }
            engine.uploadTiles(layer.id, batch, last = index == layers.lastIndex)
        }
        if (corruptTiles > 0) {
            val state = _uiState.value
            if (state is UiState.Ready && state.warning == null) {
                _uiState.value = state.copy(
                    warning = warningFor(unreadableLayers = 0, unreadableTiles = corruptTiles),
                )
            }
        }
    }

    private companion object {
        const val TAG = "CanvasViewModel"

        /** A square canvas until the New Canvas dialog lands (roadmap 3c). */
        const val DEFAULT_EDGE = 2048

        /** Opaque white, the paper of a new sketch until that same dialog. */
        const val PAPER_WHITE = 0xFFFFFFFF.toInt()

        /** ≥ [ch.lkmc.bangnidraw.engine.gl.Readback]'s 1 s fence timeout. */
        const val READBACK_WAIT_MS = 2_000L

        const val READY_WAIT_MS = 5_000L

        /** Tiles per GL `execute {}` block on the reopen and restore paths. */
        const val UPLOAD_BATCH = 16
    }
}
