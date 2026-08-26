package ch.lkmc.bangnidraw.ui.canvas

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.ApplicationScope
import ch.lkmc.bangnidraw.data.CpuTile
import ch.lkmc.bangnidraw.data.ProjectStore
import ch.lkmc.bangnidraw.data.TileBufferPool
import ch.lkmc.bangnidraw.data.TileFlusher
import ch.lkmc.bangnidraw.data.TileStore
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.ui.navigation.CanvasRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * The Canvas screen's persistence half (roadmap 3a): opens or creates the
 * routed project, streams its tiles into the engine, funnels §10.1's readback
 * into the [TileFlusher], and checkpoints `project.json` on leave and
 * `ON_STOP` — the two triggers that need no clock
 * (`docs/plan/06-document-and-persistence.md` §6.2; the quiet/ceiling clocks
 * are roadmap 3b's).
 *
 * Writes run on [appScope] under [NonCancellable], not [viewModelScope]: the
 * leave checkpoint is what makes leaving safe, so the navigation pop that
 * clears this ViewModel must not cancel it.
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
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
        ) : UiState
    }

    private val projectId: String = savedStateHandle.toRoute<CanvasRoute>().projectId

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val pool = TileBufferPool()

    private val flusher = TileFlusher(
        write = { layer, key, pixels ->
            TileStore(store.layerDir(projectId, layer)).write(key, pixels)
        },
        pool = pool,
    ).also {
        @Suppress("OPT_IN_USAGE")
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

    /** Layer/tile keys the readback has delivered since the last checkpoint fold. */
    private val strokeTiles = ConcurrentHashMap.newKeySet<Pair<LayerId, TileKey>>()

    /** True when pixels or metadata changed since the last successful checkpoint. */
    @Volatile
    private var dirty = false

    private var session: EngineSession? = null

    private val checkpointMutex = Mutex()

    init {
        viewModelScope.launch(Dispatchers.IO) { open() }
    }

    private fun open() {
        when (val result = store.load(projectId)) {
            is ProjectStore.LoadResult.Loaded -> {
                document = result.document
                _uiState.value = readyState(
                    result.document,
                    warningFor(unreadableLayers = result.unreadableLayers, unreadableTiles = 0),
                )
            }
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

    private fun readyState(doc: Document, @StringRes warning: Int?): UiState.Ready =
        UiState.Ready(
            canvas = CanvasSize(doc.width, doc.height),
            stack = doc.stack,
            paperColor = doc.paperColor,
            warning = warning,
        )

    @StringRes
    private fun warningFor(unreadableLayers: Int, unreadableTiles: Int): Int? = when {
        // The layer message wins when both apply: a lost layer is the larger
        // loss, and one toast per open is the ceiling (06 §4).
        unreadableLayers > 0 -> R.string.err_layers_unreadable
        unreadableTiles > 0 -> R.string.err_tiles_unreadable
        else -> null
    }

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
     * The engine for this screen instance arrived (or left, with null). On
     * arrival the painting's tiles are streamed from disk into the pool —
     * §5.7's reopen path.
     */
    fun attachSession(next: EngineSession?) {
        session = next
        val doc = document ?: return
        if (next != null) {
            viewModelScope.launch(Dispatchers.IO) { streamTiles(next, doc) }
        }
    }

    /** Called at pen-up; the readback that follows carries the pixels. */
    fun onStrokeCommitted() {
        dirty = true
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
        session = null
        appScope.launch { withContext(NonCancellable) { checkpoint() } }
    }

    private suspend fun checkpoint() {
        val doc = document ?: return
        checkpointMutex.withLock {
            if (!dirty && store.exists(doc.id)) return
            // §5.6's order, without the journal yet: (readbacks land) → tiles
            // flushed → project.json last, the commit point.
            awaitReadbacks()
            flusher.flushAll()
            val now = System.currentTimeMillis()
            val folded = fold(document ?: return, now)
            document = folded
            try {
                store.checkpoint(folded)
                dirty = false
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
        /** A square canvas until the New Canvas dialog lands (roadmap 3c). */
        const val DEFAULT_EDGE = 2048

        /** Opaque white, the paper of a new sketch until that same dialog. */
        const val PAPER_WHITE = 0xFFFFFFFF.toInt()

        /** ≥ [ch.lkmc.bangnidraw.engine.gl.Readback]'s 1 s fence timeout. */
        const val READBACK_WAIT_MS = 2_000L

        const val READY_WAIT_MS = 5_000L

        /** Tiles per GL `execute {}` block on the reopen path. */
        const val UPLOAD_BATCH = 16
    }
}
