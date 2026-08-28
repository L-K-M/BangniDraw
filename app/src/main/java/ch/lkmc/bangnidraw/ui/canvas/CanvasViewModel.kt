package ch.lkmc.bangnidraw.ui.canvas

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.ApplicationScope
import ch.lkmc.bangnidraw.data.BrushPresetStore
import ch.lkmc.bangnidraw.data.CpuFlatten
import ch.lkmc.bangnidraw.data.CpuTile
import ch.lkmc.bangnidraw.data.GalleryExporter
import ch.lkmc.bangnidraw.data.GalleryExportOutcome
import ch.lkmc.bangnidraw.data.GalleryNames
import ch.lkmc.bangnidraw.data.ImageEncode
import ch.lkmc.bangnidraw.data.PaletteStore
import ch.lkmc.bangnidraw.data.Prefs
import ch.lkmc.bangnidraw.data.HistoryCodec
import ch.lkmc.bangnidraw.data.HistoryPixels
import ch.lkmc.bangnidraw.data.HistoryRecord
import ch.lkmc.bangnidraw.data.HistoryStore
import ch.lkmc.bangnidraw.data.ProjectStore
import ch.lkmc.bangnidraw.data.ReferenceImageCodec
import ch.lkmc.bangnidraw.data.RmwHistoryCapture
import ch.lkmc.bangnidraw.data.ShareCache
import ch.lkmc.bangnidraw.data.TileBufferPool
import ch.lkmc.bangnidraw.data.TileFlusher
import ch.lkmc.bangnidraw.data.TileStore
import ch.lkmc.bangnidraw.data.Thumbnails
import ch.lkmc.bangnidraw.data.applyTuning
import ch.lkmc.bangnidraw.data.highestDefaultNameIn
import ch.lkmc.bangnidraw.data.persistedTuning
import ch.lkmc.bangnidraw.engine.core.AutosavePolicy
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushMixingPolicy
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.BrushSizeScale
import ch.lkmc.bangnidraw.engine.core.ButtonState
import ch.lkmc.bangnidraw.engine.core.CanvasBackEffect
import ch.lkmc.bangnidraw.engine.core.CanvasChromeState
import ch.lkmc.bangnidraw.engine.core.CanvasDialog
import ch.lkmc.bangnidraw.engine.core.CanvasPanel
import ch.lkmc.bangnidraw.engine.core.CompositionGuideVisibility
import ch.lkmc.bangnidraw.engine.core.CanvasTapEffect
import ch.lkmc.bangnidraw.engine.core.CanvasUiPolicy
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.CheckpointFreshness
import ch.lkmc.bangnidraw.engine.core.CheckpointGeneration
import ch.lkmc.bangnidraw.engine.core.ColorMixer
import ch.lkmc.bangnidraw.engine.core.ColorMixerResolver
import ch.lkmc.bangnidraw.engine.core.ColorPickSession
import ch.lkmc.bangnidraw.engine.core.ColorPickTarget
import ch.lkmc.bangnidraw.engine.core.ColorUiState
import ch.lkmc.bangnidraw.engine.core.DishState
import ch.lkmc.bangnidraw.engine.core.DishWell
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.EraserTogglePolicy
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.GallerySyncDecision
import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.FloodFill
import ch.lkmc.bangnidraw.engine.core.FocusMode
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.HistoryDirection
import ch.lkmc.bangnidraw.engine.core.HistoryEntry
import ch.lkmc.bangnidraw.engine.core.HistoryJournal
import ch.lkmc.bangnidraw.engine.core.HistoryRecovery
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.IdSource
import ch.lkmc.bangnidraw.engine.core.LayerEditPolicy
import ch.lkmc.bangnidraw.engine.core.LayerHistory
import ch.lkmc.bangnidraw.engine.core.LayerHistoryEdit
import ch.lkmc.bangnidraw.engine.core.LayerHistoryResult
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerOpacityGesture
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.LayerThumbnail
import ch.lkmc.bangnidraw.engine.core.LayerThumbnailPolicy
import ch.lkmc.bangnidraw.engine.core.LayerTileUpdates
import ch.lkmc.bangnidraw.engine.core.LatestWriteTracker
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.MixingDish
import ch.lkmc.bangnidraw.engine.core.Palette
import ch.lkmc.bangnidraw.engine.core.PaletteCatalog
import ch.lkmc.bangnidraw.engine.core.PalettePolicy
import ch.lkmc.bangnidraw.engine.core.PaletteSwatchPickSession
import ch.lkmc.bangnidraw.engine.core.PixelOp
import ch.lkmc.bangnidraw.engine.core.PixelHistoryEntry
import ch.lkmc.bangnidraw.engine.core.PixelCommitKind
import ch.lkmc.bangnidraw.engine.core.Refusal
import ch.lkmc.bangnidraw.engine.core.BlurParams
import ch.lkmc.bangnidraw.engine.core.RmwSpec
import ch.lkmc.bangnidraw.engine.core.RmwStrokePolicy
import ch.lkmc.bangnidraw.engine.core.SmudgeParams
import ch.lkmc.bangnidraw.engine.core.SizeAdjustment
import ch.lkmc.bangnidraw.engine.core.WaterParams
import ch.lkmc.bangnidraw.engine.core.StackEdit
import ch.lkmc.bangnidraw.engine.core.StackResult
import ch.lkmc.bangnidraw.engine.core.PenButtonAction
import ch.lkmc.bangnidraw.engine.core.PointerTool
import ch.lkmc.bangnidraw.engine.core.PressurePreference
import ch.lkmc.bangnidraw.engine.core.ReadbackDrainResult
import ch.lkmc.bangnidraw.engine.core.ReferenceImportDecision
import ch.lkmc.bangnidraw.engine.core.ReferenceLayerReserve
import ch.lkmc.bangnidraw.engine.core.ReferenceTransform
import ch.lkmc.bangnidraw.engine.core.ReferenceVisibility
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_BYTES
import ch.lkmc.bangnidraw.engine.core.StrokeSpec
import ch.lkmc.bangnidraw.engine.core.StrokeMode
import ch.lkmc.bangnidraw.engine.core.StrokeLayerDecision
import ch.lkmc.bangnidraw.engine.core.StrokeSource
import ch.lkmc.bangnidraw.engine.core.StylusToolPolicy
import ch.lkmc.bangnidraw.engine.core.TemporaryReason
import ch.lkmc.bangnidraw.engine.core.TemporaryToolTarget
import ch.lkmc.bangnidraw.engine.core.TileKey
import ch.lkmc.bangnidraw.engine.core.TilePresence
import ch.lkmc.bangnidraw.engine.core.presenceOf
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ToolSelection
import ch.lkmc.bangnidraw.engine.core.ToolSwitcher
import ch.lkmc.bangnidraw.engine.core.TouchDrawingMode
import ch.lkmc.bangnidraw.engine.core.TracingReference
import ch.lkmc.bangnidraw.engine.core.TracingReferencePolicy
import ch.lkmc.bangnidraw.ui.navigation.CanvasRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlin.math.floor

private enum class DocumentWork {
    START,
    ALREADY_STARTED,
}

private enum class FillPhase { IDLE, SNAPSHOT, COMPUTE, APPLY }

internal enum class ReferenceImportState { IDLE, IMPORTING }

private enum class ReferenceRecovery { CLEAN, UNREADABLE }

private enum class FillCompletion { ENTRY_PENDING, NO_ENTRY }

private enum class DirtyKind { METADATA, CONTENT, PIXELS }

private enum class ThumbnailWork { SKIP, WRITE }

private data class CheckpointSnapshot(
    val document: Document,
    val history: HistoryRecord,
    val deletes: List<Long>,
    val generation: CheckpointGeneration.Snapshot,
    val timestampMs: Long,
    val thumbnailWork: ThumbnailWork,
)

private data class EncodedPainting(val name: String, val bytes: ByteArray)

internal enum class StrokeColorUsage { RECORD, IGNORE }

internal enum class StrokeEndDisposition { COMPLETE, AWAIT_COMMIT }

internal enum class FillStartResult { STARTED, REFUSED }

/**
 * The Canvas screen's persistence half (roadmap 3a + 3b): opens the routed
 * project, streams its tiles into the engine, funnels §10.1's
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
    private val referenceImageCodec: ReferenceImageCodec,
    private val presetStore: BrushPresetStore,
    private val paletteStore: PaletteStore,
    private val exporter: GalleryExporter,
    private val shareCache: ShareCache,
    private val prefs: Prefs,
    private val availableColorMixer: ColorMixer,
    @ApplicationScope private val appScope: CoroutineScope,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    internal sealed interface UiState {
        data object Loading : UiState

        /** The painting could not be opened; it is never silently replaced. */
        data class Failed(@StringRes val message: Int) : UiState

        data class Ready(
            val title: String,
            val canvas: CanvasSize,
            val stack: LayerStack,
            val paperColor: Int,
            val tracingReference: TracingReference?,
            /** One honest toast about degraded content, or null (06 §4). */
            @StringRes val warning: Int? = null,
            val canUndo: Boolean = false,
            val canRedo: Boolean = false,
            /** The "history capped at N steps / M MB" readout's numbers. */
            val historySteps: Int = 0,
            val historyBytes: Long = 0L,
            val historyMaxSteps: Int = 0,
            val historyMaxBytes: Long = 0L,
            val brushPresets: List<BrushPreset>,
            val paintBrushId: String,
            val eraserBrushId: String,
            val toolSelection: ToolSelection,
            val color: ColorUiState,
            val fillParams: FillParams,
            val penButtonAction: PenButtonAction,
            val eraserEndPreset: String,
            val chrome: CanvasChromeState,
            val handedness: Hand,
            val touchDrawingMode: TouchDrawingMode,
            val hapticsMode: HapticsMode,
            val pressurePreference: PressurePreference,
            val snapRightAngles: Boolean = false,
            val compositionGuideVisibility: CompositionGuideVisibility =
                CompositionGuideVisibility.HIDDEN,
            val debugLatency: Boolean,
            val layerCap: Int,
            val strokeInFlight: Boolean = false,
            val documentBusy: Boolean = false,
            val pendingActionCount: Int = 0,
            val layerRefusal: Refusal? = null,
            val layerFeedbackRevision: Long = 0L,
            @StringRes val strokeLayerNotice: Int? = null,
            val strokeLayerNoticeRevision: Long = 0L,
            val fillProgress: Float? = null,
            /** True while the leave checkpoint runs; the closing scrim shows. */
            val closing: Boolean = false,
            /** Bumped when a leave fails; the screen toasts once per bump. */
            val leaveNoticeRevision: Long = 0L,
            val referenceImportState: ReferenceImportState = ReferenceImportState.IDLE,
            @StringRes val referenceNotice: Int? = null,
            val referenceNoticeRevision: Long = 0L,
        ) : UiState
    }

    private val projectId: String = savedStateHandle.toRoute<CanvasRoute>().projectId

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    internal val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val toolSwitcher = ToolSwitcher(ToolKind.Brush(BrushPresets.DEFAULT))
    private val layerIds = IdSource { LayerId(UUID.randomUUID().toString()) }

    @Volatile
    private var brushPresets: List<BrushPreset> = listOf(BrushPresets.DEFAULT)
    private var paintBrushId = BrushPresets.PENCIL_ID
    private var eraserBrushId = BrushPresets.HARD_ERASER_ID

    private var penButtonAction = PenButtonAction.Eraser
    private var eraserEndPreset = BrushPresets.HARD_ERASER_ID
    private var handedness = Hand.RIGHT
    private var touchDrawingMode = TouchDrawingMode.ENABLED
    private var hapticsMode = HapticsMode.ENABLED
    private var pressurePreference = PressurePreference.LINEAR
    private var snapRightAngles = false
    private var compositionGuideVisibility = CompositionGuideVisibility.HIDDEN
    private var debugLatency = false
    private var chrome = CanvasChromeState()
    private var brushColor = OPAQUE_BLACK
    private var previousBrushColor = OPAQUE_BLACK
    private var userPalettes: List<Palette> = emptyList()
    private val paletteWrites = LatestWriteTracker<String>()
    private val paletteWriteMutex = Mutex()
    private val recentWrites = LatestWriteTracker<Unit>()
    private val recentWriteMutex = Mutex()
    private var recentColors: List<Int> = emptyList()
    private var activePaletteId = PaletteCatalog.PAINTERS_ID
    private var dish = DishState(
        PaletteCatalog.ULTRAMARINE_BLUE_ARGB.toInt(),
        PaletteCatalog.CADMIUM_YELLOW_ARGB.toInt(),
    )
    private var colorPickSession: ColorPickSession? = null
    private var swatchPickSession: PaletteSwatchPickSession? = null
    private var activeColorMixer = ColorMixerResolver.resolve(
        if (availableColorMixer.isPigment) MixerChoice.PIGMENT else MixerChoice.RGB,
        availableColorMixer,
    )
    private var hoverPointer: PointerTool? = null
    private var fillParams = FillParams()

    /** Session-local tool parameters; the settings sheets edit these live. */
    private var smudgeParams = SmudgeParams()
    private var waterParams = WaterParams()
    private var blurParams = BlurParams()
    private var eyedropperParams = EyedropperParams()

    private var fillPhase = FillPhase.IDLE
    @Volatile private var fillGeneration = 0L
    @Volatile private var fillProgressValue = 0f
    private var fillJob: Job? = null
    private var fillIndicatorJob: Job? = null

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

    private val rmwHistoryCapture = RmwHistoryCapture()
    private val rmwRestorePending = AtomicBoolean(false)

    /** §6.3's storage-full state, for the `err_storage_full` banner. */
    val storageFull: StateFlow<Boolean> = flusher.storageFull

    private val _layerThumbnails = MutableStateFlow<Map<LayerId, LayerThumbnail>>(emptyMap())
    internal val layerThumbnails: StateFlow<Map<LayerId, LayerThumbnail>> =
        _layerThumbnails.asStateFlow()

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

    /** Sparse tile outcomes delivered since the last checkpoint fold. */
    private val tileUpdates = ConcurrentHashMap<Pair<LayerId, TileKey>, TilePresence>()

    /** Serialises GL readback dirties with a main-thread checkpoint snapshot. */
    private val checkpointStateLock = Any()

    /** Prevents an older IO write from clearing state owned by a newer edit. */
    private val checkpointGeneration = CheckpointGeneration()

    /** True when pixels or metadata changed since the last successful checkpoint. */
    @Volatile
    private var dirty = false

    /** True when pixels changed since the last thumbnail write (06 §6.4). */
    @Volatile
    private var thumbDirty = false

    /**
     * True when *content* (pixels; a title, once the Canvas can edit one)
     * changed since the last checkpoint — the only thing that moves
     * `updatedAt`. Distinct from [dirty]: a gallery sync outcome dirties the
     * metadata but is looking, not painting (06 §6.1).
     */
    @Volatile
    private var contentDirty = false

    /** The [revisions] value the gallery last mirrored (06 §9.3's counter). */
    @Volatile
    private var lastSyncedRevision = 0

    private var gallerySyncJob: Job? = null

    /** Active leave job; ownership prevents an older grace timer clearing a retry. */
    @Volatile
    private var leaveJob: Job? = null

    /** True once the running leaveJob has handed off to navigation. */
    @Volatile
    private var leaveHandedOff = false

    /** When the document first differed from disk — the ceiling clock's anchor. */
    @Volatile
    private var dirtySinceMs: Long? = null

    private var autosaveJob: Job? = null

    /** One journal mutation at a time; later chrome actions wait in order. */
    private val actionGate = CanvasActionGate()
    private val applyBusy: Boolean get() = actionGate.busy
    private var leaveAfterWrite: (() -> Unit)? = null
    private var layerCap = 1
    private var budgetLayerCap = 1
    private var transientImageBytes = 0L
    private var layerRefusal: Refusal? = null
    private var layerFeedbackRevision = 0L
    private var opacityGesture: LayerOpacityGesture? = null
    @StringRes private var strokeLayerNotice: Int? = null
    private var strokeLayerNoticeRevision = 0L
    private val layerThumbnailPolicy = LayerThumbnailPolicy()
    private var layerPanelOpen = false
    private var layerThumbnailJob: Job? = null
    private var referenceNoticeRevision = 0L

    private var session: EngineSession? = null

    private val checkpointMutex = Mutex()

    init {
        viewModelScope.launch(Dispatchers.IO) { open() }
        viewModelScope.launch {
            prefs.penButtonAction.collect { action ->
                penButtonAction = action
                updateToolUi()
            }
        }
        viewModelScope.launch {
            prefs.eraserEndPreset.collect { id ->
                eraserEndPreset = id
                updateToolUi()
            }
        }
        viewModelScope.launch {
            prefs.handedness.collect { hand ->
                handedness = hand
                updateChromeUi()
            }
        }
        viewModelScope.launch {
            prefs.touchDrawingMode.collect { mode ->
                touchDrawingMode = mode
                updateChromeUi()
            }
        }
        viewModelScope.launch {
            prefs.hapticsMode.collect { mode ->
                hapticsMode = mode
                updateChromeUi()
            }
        }
        viewModelScope.launch {
            prefs.pressurePreference.collect { preference ->
                pressurePreference = preference
                updateChromeUi()
            }
        }
        viewModelScope.launch {
            prefs.snapRightAngles.collect { enabled ->
                snapRightAngles = enabled
                updateChromeUi()
            }
        }
        viewModelScope.launch {
            prefs.compositionGuideVisibility.collect { visibility ->
                compositionGuideVisibility = visibility
                updateChromeUi()
            }
        }
        viewModelScope.launch {
            prefs.debugLatency.collect { enabled ->
                debugLatency = enabled
                updateChromeUi()
            }
        }
        viewModelScope.launch {
            prefs.mixerChoice.collect { choice ->
                activeColorMixer = ColorMixerResolver.resolve(choice, availableColorMixer)
                updateToolUi()
            }
        }
        viewModelScope.launch {
            prefs.activePaletteId.collect { id ->
                activePaletteId = id
                updateToolUi()
            }
        }
        viewModelScope.launch {
            prefs.dish.collect { stored ->
                dish = stored
                updateToolUi()
            }
        }
        viewModelScope.launch {
            prefs.recentColors.collect { stored ->
                recentColors = stored
                updateToolUi()
            }
        }
    }

    private suspend fun open() {
        val hintShown = runCatching { prefs.hintShown.first() }
            .onFailure { android.util.Log.w(TAG, "hint preference could not be loaded", it) }
            .getOrDefault(false)
        if (!hintShown) chrome = CanvasUiPolicy.showHint(chrome)

        val loadedPresets = BrushPresets.railOrder(presetStore.load()).ifEmpty {
            listOf(BrushPresets.DEFAULT)
        }
        val tunings = runCatching { prefs.brushTunings(loadedPresets.map { it.id }) }
            .onFailure { android.util.Log.w(TAG, "brush tuning could not be loaded", it) }
            .getOrDefault(emptyMap())
        brushPresets = loadedPresets.map { preset ->
            preset.applyTuning(tunings[preset.id])
        }
        // Watercolor cannot honor the opacity key written by older builds.
        for (preset in brushPresets) {
            val tuning = tunings[preset.id] ?: continue
            if (preset.watercolor == null || tuning.opacity == null) continue

            runCatching {
                prefs.setBrushTuning(preset.id, preset.persistedTuning())
            }.onFailure {
                android.util.Log.w(TAG, "legacy brush opacity could not be removed", it)
            }
        }
        userPalettes = paletteStore.load()
        val default = brushPresets.firstOrNull { it.id == BrushPresets.PENCIL_ID }
            ?: brushPresets.first()
        paintBrushId = default.id
        eraserBrushId = brushPresets.firstOrNull { it.id == BrushPresets.HARD_ERASER_ID }?.id
            ?: brushPresets.firstOrNull { it.eraseMode }?.id
            ?: BrushPresets.HARD_ERASER_ID
        toolSwitcher.select(ToolKind.Brush(default))

        when (val decision = CanvasOpenPolicy.decide(store.load(projectId))) {
            is CanvasOpenDecision.Open -> openLoaded(decision.project)
            is CanvasOpenDecision.Reject -> _uiState.value = UiState.Failed(decision.message)
        }
    }

    private fun openLoaded(result: ProjectStore.LoadResult.Loaded) {
        val history = HistoryStore(historyDir(result.document.id))
        val loaded = history.load(result.history)
        val checkpointedCount = loaded.entries.indexOfFirst { it.seq >= result.history.nextSeq }
            .let { if (it >= 0) it else loaded.entries.size }
        val recoveredEntries = loaded.entries.drop(checkpointedCount)
        val recovery = HistoryRecovery.replay(result.document, recoveredEntries)
        val validCount = checkpointedCount + recovery.appliedCount
        val loadedHistory = HistoryStore.Loaded(
            entries = loaded.entries.take(validCount),
            cursor = minOf(loaded.cursor, validCount),
        )
        if (recovery.appliedCount < recoveredEntries.size) {
            val invalid = recoveredEntries.drop(recovery.appliedCount).map { it.seq }
            android.util.Log.w(TAG, "discarding ${invalid.size} inconsistent recovered entries")
            history.delete(invalid)
        }
        // The nextName obligation's replay half (roadmap 3b): the recovered
        // journal can carry default names a stale checkpoint never saw —
        // including on layers the journal itself deletes — and the counter
        // must clear every one of them, or a reopen reissues a live name.
        val floor = highestDefaultNameIn(loadedHistory.entries) + 1
        val doc = store.relistTiles(recovery.document).let {
            if (it.stack.nextName >= floor) it
            else it.copy(stack = it.stack.copy(nextName = floor))
        }.copy(historyCursor = loadedHistory.cursor)
        document = doc
        wireHistory(doc, loadedHistory, result.history)
        _uiState.value = readyState(
            doc,
            warningFor(
                unreadableLayers = result.unreadableLayers,
                unreadableTiles = 0,
                referenceRecovery = if (result.unreadableReference) {
                    ReferenceRecovery.UNREADABLE
                } else {
                    ReferenceRecovery.CLEAN
                },
            ),
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
        budgetLayerCap = budget.maxLayers
        transientImageBytes = budget.transientImageBytes
        layerCap = TracingReferencePolicy.layerCap(
            layerCount = doc.stack.layers.size,
            maxLayers = budgetLayerCap,
            reference = doc.tracingReference,
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
            title = doc.title,
            canvas = CanvasSize(doc.width, doc.height),
            stack = doc.stack,
            paperColor = doc.paperColor,
            tracingReference = doc.tracingReference,
            warning = warning,
            canUndo = j?.canUndo() == true && !applyBusy,
            canRedo = j?.canRedo() == true && !applyBusy,
            historySteps = j?.stats()?.entries ?: 0,
            historyBytes = j?.stats()?.bytes ?: 0L,
            historyMaxSteps = journalLimits.maxEntries,
            historyMaxBytes = journalLimits.maxBytes,
            brushPresets = brushPresets,
            paintBrushId = paintBrushId,
            eraserBrushId = eraserBrushId,
            toolSelection = toolSwitcher.selection.value,
            color = colorUiState(),
            fillParams = fillParams,
            penButtonAction = penButtonAction,
            eraserEndPreset = eraserEndPreset,
            chrome = chrome,
            handedness = handedness,
            touchDrawingMode = touchDrawingMode,
            hapticsMode = hapticsMode,
            pressurePreference = pressurePreference,
            snapRightAngles = snapRightAngles,
            compositionGuideVisibility = compositionGuideVisibility,
            debugLatency = debugLatency,
            layerCap = layerCap,
            strokeInFlight = actionGate.strokeInFlight,
            documentBusy = actionGate.busy,
            pendingActionCount = actionGate.pendingCount,
            layerRefusal = layerRefusal,
            layerFeedbackRevision = layerFeedbackRevision,
            strokeLayerNotice = strokeLayerNotice,
            strokeLayerNoticeRevision = strokeLayerNoticeRevision,
        )
    }

    private fun updateToolUi() {
        val state = _uiState.value
        if (state !is UiState.Ready) return

        _uiState.value = state.copy(
            brushPresets = brushPresets,
            paintBrushId = paintBrushId,
            eraserBrushId = eraserBrushId,
            toolSelection = toolSwitcher.selection.value,
            color = colorUiState(),
            penButtonAction = penButtonAction,
            eraserEndPreset = eraserEndPreset,
            fillParams = fillParams,
        )
    }

    private fun updateChromeUi() {
        val state = _uiState.value
        if (state !is UiState.Ready) return

        _uiState.value = state.copy(
            chrome = chrome,
            handedness = handedness,
            touchDrawingMode = touchDrawingMode,
            hapticsMode = hapticsMode,
            pressurePreference = pressurePreference,
            snapRightAngles = snapRightAngles,
            compositionGuideVisibility = compositionGuideVisibility,
            debugLatency = debugLatency,
        )
    }

    private fun colorUiState(): ColorUiState {
        val palettes = buildList {
            add(PaletteCatalog.Painters)
            add(PaletteCatalog.Basic)
            add(PaletteCatalog.recent(recentColors))
            addAll(userPalettes)
        }
        val resolvedId = activePaletteId.takeIf { id -> palettes.any { it.id == id } }
            ?: PaletteCatalog.PAINTERS_ID
        return ColorUiState(
            current = brushColor,
            previous = previousBrushColor,
            palettes = palettes,
            activePaletteId = resolvedId,
            dish = dish,
            mixerChoice = if (activeColorMixer.isPigment) MixerChoice.PIGMENT else MixerChoice.RGB,
            pigmentMixerAvailable = availableColorMixer.isPigment,
        )
    }

    private fun updateInteractionUi() {
        val state = _uiState.value
        if (state !is UiState.Ready) return

        _uiState.value = state.copy(
            chrome = chrome,
            strokeInFlight = actionGate.strokeInFlight,
            documentBusy = actionGate.busy,
            pendingActionCount = actionGate.pendingCount,
            layerRefusal = layerRefusal,
            layerFeedbackRevision = layerFeedbackRevision,
            strokeLayerNotice = strokeLayerNotice,
            strokeLayerNoticeRevision = strokeLayerNoticeRevision,
        )
    }

    internal fun togglePanel(panel: CanvasPanel) {
        applyChrome(CanvasUiPolicy.togglePanel(chrome, panel))
    }

    internal fun dismissPanel() {
        applyChrome(CanvasUiPolicy.dismissPanel(chrome))
    }

    internal fun toggleFocus() {
        val next = if (chrome.focusMode == FocusMode.FOCUSED) {
            CanvasUiPolicy.exitFocus(chrome)
        } else {
            CanvasUiPolicy.enterFocus(chrome)
        }
        applyChrome(next)
    }

    internal fun showChrome() {
        applyChrome(CanvasUiPolicy.exitFocus(chrome))
    }

    /** Called only by an overlay that has already consumed the whole gesture. */
    internal fun dismissCanvasOverlay() {
        val result = CanvasUiPolicy.canvasTap(chrome)
        if (result.effect == CanvasTapEffect.DRAW) return

        val dismissedHint = chrome.hint != result.state.hint
        applyChrome(result.state)
        if (dismissedHint) appScope.launch { prefs.markHintShown() }
    }

    internal fun handleBack(afterWrite: () -> Unit) {
        val result = CanvasUiPolicy.back(chrome)
        if (result.effect == CanvasBackEffect.LEAVE) {
            requestLeave(afterWrite)
            return
        }
        applyChrome(result.state)
    }

    /** Parks navigation behind the active stroke and every earlier action. */
    internal fun requestLeave(afterWrite: () -> Unit) {
        if (leaveAfterWrite != null) {
            if (!leaveHandedOff) return

            releaseLeaveGate()
        }

        leaveAfterWrite = afterWrite
        requestAction(CanvasDocumentAction.Leave)
    }

    internal fun requestRename() {
        requestDialog(CanvasDialog.RenamePainting)
    }

    internal fun requestDialog(dialog: CanvasDialog) {
        applyChrome(CanvasUiPolicy.requestDialog(chrome, dialog))
    }

    internal fun dismissDialog() {
        applyChrome(CanvasUiPolicy.dismissDialog(chrome))
    }

    internal fun renamePainting(title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val doc = document ?: return
        if (doc.title == clean) {
            dismissDialog()
            return
        }

        val next = doc.copy(title = clean)
        document = next
        markDirty(DirtyKind.CONTENT)
        val state = _uiState.value
        if (state is UiState.Ready) _uiState.value = state.copy(title = clean)
        dismissDialog()
        noteChange()
    }

    internal fun importTracingReference(uri: Uri) {
        val doc = document ?: return
        val ready = _uiState.value
        if (ready !is UiState.Ready ||
            ready.referenceImportState == ReferenceImportState.IMPORTING
        ) {
            return
        }

        val decision = referenceImportDecision(doc)
        if (decision != ReferenceImportDecision.ACCEPT) {
            showReferenceRefusal(decision)
            return
        }
        updateReferenceImporting(ReferenceImportState.IMPORTING)

        viewModelScope.launch(Dispatchers.IO) {
            val assetName = "reference-${UUID.randomUUID()}.png"
            val imported = try {
                referenceImageCodec.importAsset(
                    projectId = doc.id,
                    assetName = assetName,
                    uri = uri,
                    canvas = CanvasSize(doc.width, doc.height),
                    maxPixelBytes = transientImageBytes,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "tracing reference import failed", e)
                null
            }
            if (imported == null) {
                withContext(Dispatchers.Main) {
                    updateReferenceImporting(ReferenceImportState.IDLE)
                    showReferenceNotice(R.string.err_reference_import)
                }
                return@launch
            }

            val reference = TracingReference(
                assetName = assetName,
                imageWidth = imported.width,
                imageHeight = imported.height,
                transform = ReferenceTransform.fit(
                    imageWidth = imported.width,
                    imageHeight = imported.height,
                    canvasWidth = doc.width,
                    canvasHeight = doc.height,
                ),
            )
            val accepted = withContext(Dispatchers.Main) {
                updateReferenceImporting(ReferenceImportState.IDLE)
                val current = document ?: return@withContext false
                val currentDecision = referenceImportDecision(current)
                if (currentDecision != ReferenceImportDecision.ACCEPT) {
                    showReferenceRefusal(currentDecision)
                    return@withContext false
                }

                // Queue metadata before its IO uploader so the GL thread
                // cannot reject the first batches as stale.
                session?.setTracingReference(reference)
                applyTracingReference(reference)
                if (chrome.openPanel != CanvasPanel.REFERENCE) {
                    togglePanel(CanvasPanel.REFERENCE)
                }
                session?.let { engine ->
                    viewModelScope.launch(Dispatchers.IO) {
                        streamTracingReference(engine, doc.id, reference)
                    }
                }

                true
            }
            if (!accepted) referenceImageCodec.discardAsset(doc.id, assetName)
        }
    }

    private fun referenceImportDecision(doc: Document): ReferenceImportDecision {
        val reserve = if (doc.tracingReference == null) {
            ReferenceLayerReserve.REQUIRED
        } else {
            ReferenceLayerReserve.HELD
        }

        return TracingReferencePolicy.importDecision(
            layerCount = doc.stack.layers.size,
            maxLayers = budgetLayerCap,
            transientImageBytes = transientImageBytes,
            layerReserve = reserve,
        )
    }

    private fun showReferenceRefusal(decision: ReferenceImportDecision) {
        val notice = when (decision) {
            ReferenceImportDecision.ACCEPT -> return
            ReferenceImportDecision.REFUSE_LAYER_BUDGET -> R.string.err_reference_layer_budget
            ReferenceImportDecision.REFUSE_TRANSIENT_BUDGET -> R.string.err_reference_memory
        }

        showReferenceNotice(notice)
    }

    internal fun setTracingReferenceOpacity(opacity: Float) {
        val reference = document?.tracingReference ?: return
        applyTracingReference(reference.copy(opacity = opacity.coerceIn(0f, 1f)))
    }

    internal fun toggleTracingReferenceVisibility() {
        val reference = document?.tracingReference ?: return
        val visibility = when (reference.visibility) {
            ReferenceVisibility.VISIBLE -> ReferenceVisibility.HIDDEN
            ReferenceVisibility.HIDDEN -> ReferenceVisibility.VISIBLE
        }
        applyTracingReference(reference.copy(visibility = visibility))
    }

    internal fun resetTracingReference() {
        val doc = document ?: return
        val reference = doc.tracingReference ?: return
        applyTracingReference(
            reference.copy(
                transform = ReferenceTransform.fit(
                    imageWidth = reference.imageWidth,
                    imageHeight = reference.imageHeight,
                    canvasWidth = doc.width,
                    canvasHeight = doc.height,
                ),
            ),
        )
    }

    internal fun transformTracingReference(
        pivotX: Float,
        pivotY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        rotationDelta: Float,
    ) {
        val reference = document?.tracingReference ?: return
        applyTracingReference(
            reference.copy(
                transform = reference.transform.gesture(
                    pivotX = pivotX,
                    pivotY = pivotY,
                    panX = panX,
                    panY = panY,
                    zoom = zoom,
                    rotationDelta = rotationDelta,
                ),
            ),
        )
    }

    internal fun removeTracingReference() {
        applyTracingReference(null)
        dismissPanel()
        dismissDialog()
        dismissCanvasOverlay()
    }

    private fun applyTracingReference(reference: TracingReference?) {
        val doc = document ?: return
        if (doc.tracingReference == reference) return

        val next = doc.copy(tracingReference = reference)
        document = next
        layerCap = TracingReferencePolicy.layerCap(
            layerCount = next.stack.layers.size,
            maxLayers = budgetLayerCap,
            reference = reference,
        )
        val state = _uiState.value
        if (state is UiState.Ready) {
            _uiState.value = state.copy(
                tracingReference = reference,
                layerCap = layerCap,
            )
        }
        markDirty(DirtyKind.METADATA)
        noteChange()
    }

    private fun updateReferenceImporting(state: ReferenceImportState) {
        val ready = _uiState.value
        if (ready !is UiState.Ready) return

        _uiState.value = ready.copy(referenceImportState = state)
    }

    private fun showReferenceNotice(@StringRes notice: Int) {
        referenceNoticeRevision += 1
        val ready = _uiState.value
        if (ready !is UiState.Ready) return

        _uiState.value = ready.copy(
            referenceNotice = notice,
            referenceNoticeRevision = referenceNoticeRevision,
        )
    }

    internal fun share(
        format: ImageEncode.Format,
        onReady: (android.net.Uri, String) -> Unit,
        onFailure: () -> Unit,
    ) {
        appScope.launch(Dispatchers.IO) {
            val image = encodeCurrent(format)
            if (image == null) {
                withContext(Dispatchers.Main) { onFailure() }
                return@launch
            }

            val uri = runCatching {
                shareCache.stage("${image.name}.${format.extension}", image.bytes)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (uri == null) onFailure() else onReady(uri, format.mimeType)
            }
        }
    }

    internal fun export(
        format: ImageEncode.Format,
        onDone: (GalleryExportOutcome) -> Unit,
    ) {
        appScope.launch(Dispatchers.IO) {
            val image = encodeCurrent(format)
            val ok = image != null && exporter.saveAs(image.name, image.bytes, format)
            val outcome = if (ok) GalleryExportOutcome.SUCCESS else GalleryExportOutcome.FAILURE
            withContext(Dispatchers.Main) { onDone(outcome) }
        }
    }

    private fun applyChrome(next: CanvasChromeState) {
        if (next == chrome) return

        val wasLayers = chrome.openPanel == CanvasPanel.LAYERS
        chrome = next
        val hasLayers = chrome.openPanel == CanvasPanel.LAYERS
        if (wasLayers != hasLayers) setLayerPanelOpen(hasLayers)
        updateChromeUi()
    }

    private suspend fun encodeCurrent(format: ImageEncode.Format): EncodedPainting? {
        checkpoint(GallerySyncDecision.Trigger.CHECKPOINT)
        if (contentDirty) return null

        val doc = document ?: return null
        val rgba = CpuFlatten.flatten(doc) { store.layerDir(doc.id, it) }
        val bytes = ImageEncode.encode(rgba, doc.width, doc.height, format)
        val name = GalleryNames.sanitizeDisplayName(
            doc.title,
            context.getString(R.string.studio_untitled),
        )
        return EncodedPainting(name, bytes)
    }

    fun selectBrush(id: String) {
        val preset = brushPresets.firstOrNull { it.id == id } ?: return

        if (preset.eraseMode) eraserBrushId = id else paintBrushId = id
        clearColorPick()
        toolSwitcher.select(ToolKind.Brush(preset))
        updateToolUi()
    }

    internal fun selectPaintBrush() {
        selectBrush(paintBrushId)
    }

    internal fun selectEraser() {
        selectBrush(eraserBrushId)
    }

    /**
     * The rail eraser slot's long-press: swap the rail eraser between the
     * two shipped erasers. Session state like [selectBrush]; a preset set
     * with fewer than two erasers has nothing to swap to.
     */
    internal fun toggleEraserPreset() {
        val next = EraserTogglePolicy.next(eraserBrushId, brushPresets) ?: return
        selectBrush(next)
    }

    fun selectSmudge() {
        clearColorPick()
        toolSwitcher.select(ToolKind.Smudge(smudgeParams))
        updateToolUi()
    }

    internal fun selectWater() {
        clearColorPick()
        toolSwitcher.select(ToolKind.Water(waterParams))
        updateToolUi()
    }

    fun selectBlur() {
        clearColorPick()
        toolSwitcher.select(ToolKind.Blur(blurParams))
        updateToolUi()
    }

    fun selectFill() {
        clearColorPick()
        toolSwitcher.select(ToolKind.Fill(fillParams))
        updateToolUi()
    }

    internal fun updateFillParams(params: FillParams) {
        fillParams = params
        if (toolSwitcher.selection.value.kind is ToolKind.Fill) {
            toolSwitcher.select(ToolKind.Fill(params))
        }
        updateToolUi()
    }

    internal fun updateSmudgeParams(params: SmudgeParams) {
        smudgeParams = params
        if (toolSwitcher.selection.value.kind is ToolKind.Smudge) {
            toolSwitcher.select(ToolKind.Smudge(params))
        }
        updateToolUi()
    }

    internal fun updateWaterParams(params: WaterParams) {
        waterParams = params
        if (toolSwitcher.selection.value.kind is ToolKind.Water) {
            toolSwitcher.select(ToolKind.Water(params))
        }
        updateToolUi()
    }

    internal fun updateBlurParams(params: BlurParams) {
        blurParams = params
        if (toolSwitcher.selection.value.kind is ToolKind.Blur) {
            toolSwitcher.select(ToolKind.Blur(params))
        }
        updateToolUi()
    }

    internal fun updateEyedropperParams(params: EyedropperParams) {
        eyedropperParams = params
        val selection = toolSwitcher.selection.value
        if (selection.kind is ToolKind.Eyedropper) {
            val reason = selection.temporaryReason
            if (reason == null) {
                toolSwitcher.select(ToolKind.Eyedropper(params))
            } else {
                toolSwitcher.pushTemporary(ToolKind.Eyedropper(params), reason)
            }
        }
        updateToolUi()
    }

    /** The parameters the next eyedropper stroke samples with. */
    internal fun currentEyedropperParams(): EyedropperParams = eyedropperParams

    fun selectEyedropper() {
        clearColorPick()
        colorPickSession = newColorPick(ColorPickTarget.Current)
        // The tool itself is selected in selectEyedropperTool, which carries
        // the session's eyedropperParams — not the defaults.
        selectEyedropperTool()
    }

    internal fun selectDishEyedropper(well: DishWell) {
        clearColorPick()
        colorPickSession = newColorPick(
            when (well) {
                DishWell.A -> ColorPickTarget.WellA
                DishWell.B -> ColorPickTarget.WellB
            },
        )
        selectEyedropperTool()
    }

    internal fun selectPaletteSwatchEyedropper(index: Int) {
        val palette = colorUiState().activePalette
        if (palette.builtIn || index !in palette.swatches.indices) return

        clearColorPick()
        swatchPickSession = PaletteSwatchPickSession(
            paletteId = palette.id,
            index = index,
            colorBefore = palette.swatches[index],
        )
        selectEyedropperTool()
    }

    internal fun beginKeyboardEyedropper() {
        clearColorPick()
        colorPickSession = newColorPick(ColorPickTarget.Current)
        toolSwitcher.pushTemporary(ToolKind.Eyedropper(eyedropperParams), TemporaryReason.Keyboard)
        updateToolUi()
    }

    internal fun endKeyboardEyedropper() {
        clearColorPick()
        toolSwitcher.popTemporary(TemporaryReason.Keyboard)
        updateToolUi()
    }

    internal fun prepareColorPick() {
        if (colorPickSession == null && swatchPickSession == null) {
            colorPickSession = newColorPick(ColorPickTarget.Current)
        }
    }

    private fun selectEyedropperTool() {
        // Rail selection can arrive while the eraser end hovers. Remove that
        // top entry before Rail so hover exit cannot release out of order.
        hoverPointer = null
        toolSwitcher.popTemporary(TemporaryReason.Hover)
        toolSwitcher.pushTemporary(ToolKind.Eyedropper(eyedropperParams), TemporaryReason.Rail)
        updateToolUi()
    }

    private fun clearColorPick() {
        colorPickSession?.cancel()?.let(::applyColorPick)
        colorPickSession = null
        swatchPickSession?.let(::restoreSwatchPick)
        swatchPickSession = null
        toolSwitcher.popTemporary(TemporaryReason.Rail)
    }

    internal fun selectBrushColor(argb: Int) {
        val opaque = argb or OPAQUE_ALPHA
        if (opaque == brushColor) return
        previousBrushColor = brushColor
        brushColor = opaque
        updateToolUi()
    }

    internal fun previewPickedColor(argb: Int) {
        val opaque = argb or OPAQUE_ALPHA
        swatchPickSession?.let { session ->
            editPickedSwatch { session.preview(it, opaque) }
            updateToolUi()
            return
        }
        val session = colorPickSession ?: newColorPick(ColorPickTarget.Current).also {
            colorPickSession = it
        }
        applyColorPick(session.preview(opaque, brushColor, dish))
        updateToolUi()
    }

    internal fun commitPickedColor() {
        swatchPickSession?.let { session ->
            userPalettes.firstOrNull { it.id == session.paletteId }?.let(::persistPalette)
            swatchPickSession = null
            return
        }
        val session = colorPickSession ?: return
        if (session.changesDish) {
            viewModelScope.launch { prefs.setDishWells(dish.a, dish.b) }
        } else if (brushColor != session.currentBefore) {
            previousBrushColor = session.currentBefore
            updateToolUi()
        }
        colorPickSession = null
    }

    internal fun cancelPickedColor() {
        clearColorPick()
        updateToolUi()
    }

    private fun newColorPick(target: ColorPickTarget): ColorPickSession =
        ColorPickSession(target, currentBefore = brushColor, dishBefore = dish)

    private fun applyColorPick(result: ColorPickSession.Result) {
        brushColor = result.current
        dish = result.dish
    }

    private fun restoreSwatchPick(session: PaletteSwatchPickSession) {
        editPickedSwatch(session::cancel)
    }

    private fun editPickedSwatch(edit: (Palette) -> Palette) {
        val session = swatchPickSession ?: return
        val palette = userPalettes.firstOrNull { it.id == session.paletteId } ?: return
        userPalettes = PalettePolicy.upsert(userPalettes, edit(palette))
    }

    internal fun swapBrushColors() {
        val swap = brushColor
        brushColor = previousBrushColor
        previousBrushColor = swap
        updateToolUi()
    }

    fun currentBrushColor(): Int = brushColor

    /** Starts one cancellable CPU fill after the input gate accepted its touch. */
    internal fun startFill(
        engine: EngineSession,
        x: Float,
        y: Float,
        params: FillParams,
        color: Int,
    ): FillStartResult {
        if (fillPhase != FillPhase.IDLE || session !== engine) return FillStartResult.REFUSED
        val doc = document ?: return FillStartResult.REFUSED
        val active = doc.stack.active
        val canvas = CanvasSize(doc.width, doc.height)
        val generation = ++fillGeneration

        actionGate.beginWork()
        fillPhase = FillPhase.SNAPSHOT
        fillProgressValue = 0f
        updateInteractionUi()
        armFillIndicator(generation)

        engine.requestFillReference(params.reference) { reference ->
            if (generation != fillGeneration || fillPhase != FillPhase.SNAPSHOT) return@requestFillReference
            if (reference == null) {
                finishFill(generation, FillCompletion.NO_ENTRY)
                return@requestFillReference
            }

            fillPhase = FillPhase.COMPUTE
            fillJob = viewModelScope.launch(Dispatchers.Default) {
                val context = coroutineContext
                val coverage = FloodFill(canvas.width, canvas.height, reference, params).run(
                    seedX = floor(x).toInt(),
                    seedY = floor(y).toInt(),
                    progress = { fillProgressValue = it },
                    isCancelled = { generation != fillGeneration || !context.isActive },
                )
                withContext(Dispatchers.Main) {
                    if (generation != fillGeneration || fillPhase != FillPhase.COMPUTE) return@withContext
                    if (coverage == null || coverage.bounds.isEmpty) {
                        finishFill(generation, FillCompletion.NO_ENTRY)
                        return@withContext
                    }

                    fillJob = null
                    fillPhase = FillPhase.APPLY
                    val spec = StrokeSpec(
                        layerId = active.id,
                        mode = StrokeMode.PAINT,
                        opacity = params.opacity,
                        alphaLock = active.props.alphaLock,
                        commitKind = PixelCommitKind.Fill,
                    )
                    engine.applyFill(spec, coverage, color) { applied ->
                        if (generation != fillGeneration || fillPhase != FillPhase.APPLY) return@applyFill
                        if (applied) onStrokeCommitted(StrokeColorUsage.RECORD, color)
                        val completion = if (applied) {
                            FillCompletion.ENTRY_PENDING
                        } else {
                            FillCompletion.NO_ENTRY
                        }
                        finishFill(generation, completion)
                    }
                }
            }
        }
        return FillStartResult.STARTED
    }

    /** Cancels only pre-commit fill work; an APPLY is already atomic. */
    internal fun cancelFill() {
        if (fillPhase == FillPhase.IDLE || fillPhase == FillPhase.APPLY) return

        fillGeneration++
        fillJob?.cancel()
        fillJob = null
        fillIndicatorJob?.cancel()
        fillIndicatorJob = null
        fillPhase = FillPhase.IDLE
        updateFillProgress(null)
        finishStrokeTransaction()
        finishDocumentWork()
    }

    private fun armFillIndicator(generation: Long) {
        fillIndicatorJob?.cancel()
        fillIndicatorJob = viewModelScope.launch {
            delay(FILL_INDICATOR_DELAY_MS)
            while (generation == fillGeneration && fillPhase != FillPhase.IDLE) {
                updateFillProgress(fillProgressValue)
                delay(FILL_PROGRESS_POLL_MS)
            }
        }
    }

    private fun finishFill(generation: Long, completion: FillCompletion) {
        if (generation != fillGeneration || fillPhase == FillPhase.IDLE) return

        fillJob?.cancel()
        fillJob = null
        fillIndicatorJob?.cancel()
        fillIndicatorJob = null
        fillPhase = FillPhase.IDLE
        updateFillProgress(null)
        if (completion == FillCompletion.NO_ENTRY) finishStrokeTransaction()
        finishDocumentWork()
    }

    private fun updateFillProgress(progress: Float?) {
        val state = _uiState.value
        if (state is UiState.Ready) _uiState.value = state.copy(fillProgress = progress)
    }

    internal fun mixingDish(a: Int, b: Int): IntArray = MixingDish.gradient(a, b, activeColorMixer)

    internal fun mixingColor(a: Int, b: Int, t: Float): Int = activeColorMixer.mix(a, b, t)

    fun setMixerChoice(choice: MixerChoice) {
        viewModelScope.launch { prefs.setMixerChoice(choice) }
    }

    /** Persists the overflow menu's composition-guide choice. */
    internal fun setCompositionGuideVisibility(visibility: CompositionGuideVisibility) {
        viewModelScope.launch { prefs.setCompositionGuideVisibility(visibility) }
    }

    internal fun selectPalette(id: String) {
        if (colorUiState().palettes.none { it.id == id }) return
        activePaletteId = id
        updateToolUi()
        viewModelScope.launch { prefs.setActivePalette(id) }
    }

    internal fun createUserPalette(name: String) {
        val source = colorUiState().activePalette
        val created = Palette(
            id = UUID.randomUUID().toString(),
            name = PalettePolicy.createdName(name),
            swatches = source.swatches,
        )
        userPalettes = userPalettes + created
        activePaletteId = created.id
        updateToolUi()
        persistPalette(created)
        viewModelScope.launch { prefs.setActivePalette(created.id) }
    }

    internal fun addColorToPalette(color: Int) {
        val active = colorUiState().activePalette
        val editable = if (active.builtIn) {
            Palette(
                id = UUID.randomUUID().toString(),
                name = PaletteCatalog.MY_PALETTE_NAME,
                swatches = active.swatches,
            )
        } else {
            active
        }
        val updated = PalettePolicy.append(editable, color or OPAQUE_ALPHA)
        replaceUserPalette(updated)
    }

    internal fun replacePaletteSwatch(index: Int) = editActivePalette { palette ->
        PalettePolicy.replace(palette, index, brushColor)
    }

    internal fun deletePaletteSwatch(index: Int) = editActivePalette { palette ->
        PalettePolicy.remove(palette, index)
    }

    internal fun movePaletteSwatch(from: Int, to: Int) = editActivePalette { palette ->
        PalettePolicy.move(palette, from, to)
    }

    internal fun setDishWell(well: DishWell, color: Int) {
        val opaque = color or OPAQUE_ALPHA
        dish = when (well) {
            DishWell.A -> dish.copy(a = opaque)
            DishWell.B -> dish.copy(b = opaque)
        }
        updateToolUi()
        viewModelScope.launch { prefs.setDishWells(dish.a, dish.b) }
    }

    private fun editActivePalette(edit: (Palette) -> Palette) {
        val active = colorUiState().activePalette
        if (active.builtIn) return
        replaceUserPalette(edit(active))
    }

    private fun replaceUserPalette(palette: Palette) {
        userPalettes = PalettePolicy.upsert(userPalettes, palette)
        activePaletteId = palette.id
        updateToolUi()
        persistPalette(palette)
        viewModelScope.launch { prefs.setActivePalette(palette.id) }
    }

    private fun persistPalette(palette: Palette) {
        val revision = paletteWrites.issue(palette.id)
        appScope.launch(Dispatchers.IO) {
            paletteWriteMutex.withLock {
                if (!paletteWrites.isCurrent(palette.id, revision)) return@withLock
                runCatching { paletteStore.save(palette) }
                    .onFailure { android.util.Log.w(TAG, "palette ${palette.id} could not be saved", it) }
            }
            paletteWrites.complete(palette.id, revision)
        }
    }

    internal fun strokeMode(preset: BrushPreset): StrokeMode =
        BrushMixingPolicy.mode(preset, activeColorMixer)

    internal fun rmwSpec(kind: ToolKind): RmwSpec? =
        RmwStrokePolicy.spec(kind, activeColorMixer)

    /**
     * The rail and ledge sliders (`08` §3.2): they edit the *active tool*,
     * whichever kind it is. Rail tuning is session state; the brush settings
     * sheet persists explicitly, and RMW parameters are session-only like
     * [fillParams]. The size for an RMW tool is raw px within its own
     * sizeMin..sizeMax — the slider maps it through [BrushSizeScale].
     */
    fun updateActiveToolSize(value: Float) {
        when (val kind = toolSwitcher.selection.value.kind) {
            is ToolKind.Brush -> updateActiveBrush { it.withSize(value) }
            is ToolKind.Smudge -> updateSmudgeParams(kind.params.copy(size = value))
            is ToolKind.Water -> updateWaterParams(kind.params.withSize(value))
            is ToolKind.Blur -> updateBlurParams(kind.params.copy(size = value))
            is ToolKind.Fill, is ToolKind.Eyedropper -> Unit
        }
    }

    fun updateActiveToolSecondary(value: Float) {
        when (val kind = toolSwitcher.selection.value.kind) {
            is ToolKind.Brush -> updateActiveBrush { preset ->
                if (preset.watercolor == null) {
                    preset.withOpacity(value)
                } else {
                    preset.copy(
                        flow = if (value.isNaN()) preset.flow else value.coerceIn(0f, 1f),
                    )
                }
            }
            is ToolKind.Smudge -> updateSmudgeParams(kind.params.copy(strength = value))
            is ToolKind.Water -> updateWaterParams(kind.params.withWaterLoad(value))
            is ToolKind.Blur -> updateBlurParams(kind.params.copy(strength = value))
            is ToolKind.Fill, is ToolKind.Eyedropper -> Unit
        }
    }

    internal fun adjustBrushSize(adjustment: SizeAdjustment) {
        when (val kind = toolSwitcher.selection.value.kind) {
            is ToolKind.Brush -> {
                updateActiveBrush { preset ->
                    preset.withSize(
                        BrushSizeScale.adjust(
                            preset.size,
                            preset.sizeMin,
                            preset.sizeMax,
                            adjustment,
                        ),
                    )
                }
                persistBrushTuning()
            }
            is ToolKind.Smudge -> updateSmudgeParams(
                kind.params.copy(
                    size = BrushSizeScale.adjust(
                        kind.params.size,
                        kind.params.sizeMin,
                        kind.params.sizeMax,
                        adjustment,
                    ),
                ),
            )
            is ToolKind.Water -> updateWaterParams(
                kind.params.withSize(
                    BrushSizeScale.adjust(
                        kind.params.size,
                        kind.params.sizeMin,
                        kind.params.sizeMax,
                        adjustment,
                    ),
                ),
            )
            is ToolKind.Blur -> updateBlurParams(
                kind.params.copy(
                    size = BrushSizeScale.adjust(
                        kind.params.size,
                        kind.params.sizeMin,
                        kind.params.sizeMax,
                        adjustment,
                    ),
                ),
            )
            is ToolKind.Fill, is ToolKind.Eyedropper -> Unit
        }
    }

    internal fun updateActiveBrush(updated: BrushPreset) {
        val selection = toolSwitcher.selection.value
        val kind = selection.kind
        if (kind !is ToolKind.Brush) return
        if (kind.preset.id != updated.id) return

        brushPresets = brushPresets.map { if (it.id == updated.id) updated else it }
        val reason = selection.temporaryReason
        if (reason == null) {
            toolSwitcher.select(ToolKind.Brush(updated))
        } else {
            toolSwitcher.pushTemporary(ToolKind.Brush(updated), reason)
        }
        updateToolUi()
    }

    internal fun persistActiveBrush() {
        val preset = (toolSwitcher.selection.value.kind as? ToolKind.Brush)?.preset ?: return
        appScope.launch(Dispatchers.IO) {
            runCatching {
                presetStore.save(preset)
                prefs.setBrushTuning(preset.id, preset.persistedTuning())
            }
                .onFailure { android.util.Log.w(TAG, "brush ${preset.id} could not be saved", it) }
        }
    }

    internal fun persistBrushTuning() {
        val preset = (toolSwitcher.selection.value.kind as? ToolKind.Brush)?.preset ?: return
        appScope.launch(Dispatchers.IO) {
            runCatching { prefs.setBrushTuning(preset.id, preset.persistedTuning()) }
                .onFailure { android.util.Log.w(TAG, "brush ${preset.id} tuning could not be saved", it) }
        }
    }

    internal fun resetActiveBrush() {
        val id = (toolSwitcher.selection.value.kind as? ToolKind.Brush)?.preset?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!presetStore.reset(id)) return@launch
            runCatching { prefs.clearBrushTuning(id) }
                .onFailure { android.util.Log.w(TAG, "brush $id tuning could not be reset", it) }
            val reset = presetStore.load().firstOrNull { it.id == id } ?: return@launch

            withContext(Dispatchers.Main) {
                brushPresets = brushPresets.map { if (it.id == id) reset else it }
                val current = toolSwitcher.selection.value
                val kind = current.kind as? ToolKind.Brush
                if (kind?.preset?.id == id) {
                    if (current.temporaryReason == null) toolSwitcher.select(ToolKind.Brush(reset))
                    else toolSwitcher.pushTemporary(ToolKind.Brush(reset), current.temporaryReason)
                }
                updateToolUi()
            }
        }
    }

    private fun updateActiveBrush(transform: (BrushPreset) -> BrushPreset) {
        val preset = (toolSwitcher.selection.value.kind as? ToolKind.Brush)?.preset ?: return
        updateActiveBrush(transform(preset))
    }

    internal fun beginHoverTool(pointer: PointerTool) {
        if (hoverPointer == pointer) return
        hoverPointer = pointer
        if (pointer == PointerTool.ERASER) {
            toolSwitcher.pushTemporary(
                ToolKind.Brush(resolveEraserPreset()),
                TemporaryReason.Hover,
            )
        } else {
            toolSwitcher.popTemporary(TemporaryReason.Hover)
        }
        updateToolUi()
    }

    internal fun endHoverTool() {
        if (hoverPointer == null) return
        hoverPointer = null
        toolSwitcher.popTemporary(TemporaryReason.Hover)
        updateToolUi()
    }

    fun beginStrokeTool(source: StrokeSource, button: ButtonState): ToolSelection? {
        if (!actionGate.beginStroke()) return null

        chrome = CanvasUiPolicy.onStrokeBegin(chrome)
        updateInteractionUi()

        // Contact replaces hover before stroke-specific overrides are pushed.
        if (hoverPointer != null) {
            hoverPointer = null
            toolSwitcher.popTemporary(TemporaryReason.Hover)
            updateToolUi()
        }

        val pointer = when (source) {
            StrokeSource.STYLUS -> PointerTool.STYLUS
            StrokeSource.ERASER_END -> PointerTool.ERASER
            StrokeSource.FINGER -> PointerTool.FINGER
            StrokeSource.MOUSE -> PointerTool.MOUSE
        }
        val request = StylusToolPolicy.resolve(pointer, button, penButtonAction)
            ?: return toolSwitcher.selection.value
        val kind = when (request.target) {
            TemporaryToolTarget.Eraser -> ToolKind.Brush(resolveEraserPreset())
            TemporaryToolTarget.Eyedropper -> ToolKind.Eyedropper()
        }
        toolSwitcher.pushTemporary(kind, request.reason)
        updateToolUi()
        return toolSwitcher.selection.value
    }

    internal fun endStrokeTool(reason: TemporaryReason?, disposition: StrokeEndDisposition) {
        if (reason != null) {
            toolSwitcher.popTemporary(reason)
            updateToolUi()
        }
        val inputWasOpen = actionGate.strokeInputInFlight
        val inputAction = actionGate.endStrokeInput()
        val completionAction = if (
            inputWasOpen && disposition == StrokeEndDisposition.COMPLETE
        ) {
            actionGate.completeStroke()
        } else {
            null
        }
        val nextAction = inputAction ?: completionAction
        chrome = CanvasUiPolicy.onStrokeEnd(chrome)
        updateInteractionUi()
        if (nextAction != null) executeAction(nextAction)
    }

    /** Main thread: releases one stroke only after its history outcome is final. */
    private fun finishStrokeTransaction() {
        val nextAction = actionGate.completeStroke()
        updateHistoryUi()
        updateInteractionUi()
        if (nextAction != null) executeAction(nextAction)
    }

    private fun resolveEraserPreset(): BrushPreset {
        brushPresets.firstOrNull { it.id == eraserEndPreset && it.eraseMode }?.let { return it }
        brushPresets.firstOrNull { it.id == BrushPresets.HARD_ERASER_ID }?.let { return it }
        brushPresets.firstOrNull { it.eraseMode }?.let { return it }

        val active = (toolSwitcher.selection.value.kind as? ToolKind.Brush)?.preset
            ?: BrushPresets.DEFAULT
        return active.copy(
            id = BrushPresets.HARD_ERASER_ID,
            name = BrushPresets.HARD_ERASER_NAME,
            eraseMode = true,
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
    private fun warningFor(
        unreadableLayers: Int,
        unreadableTiles: Int,
        referenceRecovery: ReferenceRecovery = ReferenceRecovery.CLEAN,
    ): Int? = when {
        // The layer message wins when both apply: a lost layer is the larger
        // loss, and one toast per open is the ceiling (06 §4).
        unreadableLayers > 0 -> R.string.err_layers_unreadable
        unreadableTiles > 0 -> R.string.err_tiles_unreadable
        referenceRecovery == ReferenceRecovery.UNREADABLE -> R.string.err_reference_unreadable
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
        val presence = presenceOf(copy)
        if (!flusher.markDirty(CpuTile(layer, key, revision, copy))) return

        // Publish the model update only after the mirror owns these bytes.
        synchronized(checkpointStateLock) {
            tileUpdates[layer to key] = presence
            markDirtyLocked(DirtyKind.PIXELS)
        }
    }

    private fun onRmwStarted(spec: StrokeSpec) {
        rmwHistoryCapture.begin(spec.layerId)
    }

    private fun onRmwTilesTouched(spec: StrokeSpec, packedKeys: IntArray, count: Int) {
        val keys = ArrayList<Pair<LayerId, TileKey>>(count)
        for (index in 0 until count) keys += spec.layerId to TileKey(packedKeys[index])
        val captured = flusher.captureMirror(keys)
        if (!rmwHistoryCapture.touch(spec.layerId, keys, captured)) {
            android.util.Log.e(TAG, "RMW before-image arrived without an open capture")
        }
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
        val rmwSnapshot = if (spec.rmw != null) rmwHistoryCapture.finish(spec.layerId) else null
        if (keys.isEmpty()) {
            appScope.launch(Dispatchers.Main) { finishStrokeTransaction() }
            return
        }
        val payloadKeys = keys.map { spec.layerId to it }
        val mirrorBefore = flusher.captureMirror(payloadKeys).toMutableMap()
        rmwSnapshot?.mirrorBefore?.let(mirrorBefore::putAll)
        val doc = document
        if (doc == null) {
            appScope.launch(Dispatchers.Main) { finishStrokeTransaction() }
            return
        }
        val activeId = doc.stack.active.id
        val entry = PixelHistoryEntry.create(
            kind = spec.commitKind,
            active = activeId,
            layer = spec.layerId,
            tiles = keys,
        )
        val job = TileFlusher.FlushJob.WriteEntry(
            entry = entry,
            seq = nextSeq.getAndIncrement(),
            ts = System.currentTimeMillis(),
            mirrorBefore = mirrorBefore,
            awaitReadback = { awaitReadbacks() },
        )
        if (!flusher.enqueueNow(job)) {
            appScope.launch(Dispatchers.Main) {
                try {
                    truncateRedoAfterUnjournaledEdit()
                } finally {
                    finishStrokeTransaction()
                }
            }
            return
        }
        appScope.launch {
            val stamped = job.result.await()
            withContext(Dispatchers.Main) {
                try {
                    if (stamped == null) truncateRedoAfterUnjournaledEdit()
                    else pushHistory(stamped)
                } finally {
                    finishStrokeTransaction()
                }
            }
        }
    }

    private fun onStrokeNotMerged() {
        appScope.launch(Dispatchers.Main) {
            finishStrokeTransaction()
        }
    }

    /** Called at pen-up on the main thread; arms the autosave clocks. */
    internal fun onStrokeCommitted(colorUsage: StrokeColorUsage, strokeColor: Int) {
        markDirty(DirtyKind.CONTENT)
        document?.stack?.active?.id?.let { markLayerThumbnailsDirty(listOf(it)) }
        if (colorUsage == StrokeColorUsage.RECORD) {
            recentColors = PalettePolicy.noteRecent(recentColors, strokeColor)
            updateToolUi()
            persistRecentColors(recentColors)
        }
        noteChange()
    }

    private fun persistRecentColors(colors: List<Int>) {
        val revision = recentWrites.issue(Unit)
        appScope.launch {
            recentWriteMutex.withLock {
                if (!recentWrites.isCurrent(Unit, revision)) return@withLock
                prefs.setRecentColors(colors)
            }
            recentWrites.complete(Unit, revision)
        }
    }

    internal fun prepareStrokeCancel(mode: StrokeCancelMode) {
        if (mode != StrokeCancelMode.READ_MODIFY_WRITE) return
        if (!rmwRestorePending.compareAndSet(false, true)) return

        actionGate.beginWork()
        updateInteractionUi()
    }

    private fun onRmwCancelled(
        engine: EngineSession,
        spec: StrokeSpec,
        rendererKeys: List<TileKey>,
    ) {
        val snapshot = rmwHistoryCapture.finish(spec.layerId)
        val keys = LinkedHashSet<TileKey>()
        snapshot?.keys?.let(keys::addAll)
        keys.addAll(rendererKeys)
        if (keys.isEmpty() || session !== engine) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (session === engine) engine.completeCancelledRmwRestore()
                finishRmwRestore()
            }
            return
        }

        val payloadKeys = keys.map { spec.layerId to it }
        val mirrorBefore = flusher.captureMirror(payloadKeys).toMutableMap()
        snapshot?.mirrorBefore?.let(mirrorBefore::putAll)

        appScope.launch(Dispatchers.IO) {
            val current = flusher.resolveCurrent(payloadKeys)
            val restored = LinkedHashMap<TileKey, ByteArray?>(keys.size)
            for (key in keys) {
                val mapKey = spec.layerId to key
                restored[key] = mirrorBefore[mapKey] ?: current[mapKey]
            }
            withContext(Dispatchers.Main) {
                if (session !== engine) {
                    finishRmwRestore()
                    return@withContext
                }
                engine.restoreCancelledRmw(spec.layerId, restored) { success ->
                    if (!success) android.util.Log.e(TAG, "cancelled RMW pixels could not be restored")
                    finishRmwRestore()
                }
            }
        }
    }

    private fun finishRmwRestore() {
        if (!rmwRestorePending.compareAndSet(true, false)) return
        finishDocumentWork()
    }

    // ------------------------------------------------------------ undo/redo

    fun undo() = requestAction(CanvasDocumentAction.Undo)

    fun redo() = requestAction(CanvasDocumentAction.Redo)

    fun selectLayer(index: Int) = requestAction(CanvasDocumentAction.SelectLayer(index))

    fun addLayer() = requestAction(CanvasDocumentAction.AddLayer)

    fun deleteLayer(index: Int) = requestAction(CanvasDocumentAction.DeleteLayer(index))

    fun duplicateLayer(index: Int) = requestAction(CanvasDocumentAction.DuplicateLayer(index))

    fun moveLayer(from: Int, to: Int) =
        requestAction(CanvasDocumentAction.MoveLayer(from, to))

    fun mergeLayerDown(index: Int) = requestAction(CanvasDocumentAction.MergeDown(index))

    fun flattenLayers() = requestAction(CanvasDocumentAction.Flatten)

    fun clearLayer(index: Int) = requestAction(CanvasDocumentAction.ClearLayer(index))

    fun renameLayer(index: Int, name: String) =
        requestAction(CanvasDocumentAction.RenameLayer(index, name))

    fun setLayerOpacity(index: Int, opacity: Float) =
        requestAction(CanvasDocumentAction.SetLayerOpacity(index, opacity))

    /** Previews against the GPU stack; the document and journal stay unchanged. */
    fun previewLayerOpacity(index: Int, opacity: Float): Boolean {
        val doc = document ?: return false
        val engine = session ?: return false
        var gesture = opacityGesture
        if (gesture == null) {
            if (actionGate.busy || actionGate.strokeInFlight) return false

            gesture = LayerOpacityGesture.begin(doc.stack, index) ?: return false
            actionGate.beginWork()
            updateHistoryUi()
            updateInteractionUi()
        }

        val nextGesture = gesture.withValue(opacity)
        val preview = nextGesture.preview(doc.stack) ?: return false
        opacityGesture = nextGesture
        engine.setStack(preview)
        return true
    }

    /** Commits every preview from one drag as one `LayerProps` entry. */
    fun finishLayerOpacity() {
        val gesture = opacityGesture ?: return
        opacityGesture = null
        val doc = document
        if (doc == null) {
            finishDocumentWork()
            return
        }
        if (session == null) {
            finishDocumentWork()
            return
        }

        when (val result = gesture.finish(doc.stack)) {
            is StackResult.Ok -> applyStackEdit(result.edit, DocumentWork.ALREADY_STARTED)
            is StackResult.Refused -> {
                session?.setStack(doc.stack)
                finishDocumentWork()
            }
        }
    }

    fun toggleLayerVisibility(index: Int) =
        requestAction(CanvasDocumentAction.ToggleLayerVisibility(index))

    fun setLayerBlendMode(index: Int, mode: BlendMode) =
        requestAction(CanvasDocumentAction.SetLayerBlendMode(index, mode))

    fun toggleLayerAlphaLock(index: Int) =
        requestAction(CanvasDocumentAction.ToggleLayerAlphaLock(index))

    fun toggleLayerLock(index: Int) =
        requestAction(CanvasDocumentAction.ToggleLayerLock(index))

    fun setPaperColor(color: Int) = requestAction(CanvasDocumentAction.SetPaperColor(color))

    internal fun noteStrokeLayerDecision(decision: StrokeLayerDecision) {
        strokeLayerNotice = when (decision) {
            StrokeLayerDecision.DRAW -> return
            StrokeLayerDecision.DRAW_HIDDEN -> R.string.layer_hidden
            StrokeLayerDecision.REFUSE_LOCKED -> R.string.layer_locked
        }
        strokeLayerNoticeRevision += 1
        updateInteractionUi()
    }

    fun setLayerPanelOpen(open: Boolean) {
        if (layerPanelOpen == open) return

        layerPanelOpen = open
        if (!open) {
            layerThumbnailJob?.cancel()
            layerThumbnailJob = null
            return
        }

        val missing = document?.stack?.layers
            ?.map(Layer::id)
            ?.filterNot(_layerThumbnails.value::containsKey)
            .orEmpty()
        markLayerThumbnailsDirty(missing)
        startLayerThumbnailLoop()
    }

    private fun startLayerThumbnailLoop() {
        if (!layerPanelOpen || layerThumbnailJob?.isActive == true) return

        layerThumbnailJob = viewModelScope.launch {
            while (layerPanelOpen) {
                requestLayerThumbnails()
                delay(LAYER_THUMBNAIL_POLL_MS)
            }
        }
    }

    private fun requestLayerThumbnails() {
        val doc = document ?: return
        val live = doc.stack.layers.mapTo(LinkedHashSet(), Layer::id)
        layerThumbnailPolicy.retain(live)
        val requests = layerThumbnailPolicy.due(
            nowMs = SystemClock.uptimeMillis(),
            panelOpen = layerPanelOpen,
            strokeInFlight = actionGate.strokeInFlight || actionGate.busy,
        )
        if (requests.isEmpty()) return

        val engine = session
        if (engine == null) {
            requests.forEach(layerThumbnailPolicy::fail)
            return
        }
        val byLayer = requests.associateBy(LayerThumbnailPolicy.Request::layer)
        engine.requestLayerThumbnails(byLayer.keys) { layer, thumbnail ->
            val request = byLayer[layer] ?: return@requestLayerThumbnails
            if (thumbnail == null) {
                layerThumbnailPolicy.fail(request)
                return@requestLayerThumbnails
            }

            if (!layerThumbnailPolicy.complete(request)) return@requestLayerThumbnails
            val stillLive = document?.stack?.layers?.any { it.id == layer } == true
            if (stillLive) _layerThumbnails.value = _layerThumbnails.value + (layer to thumbnail)
        }
    }

    private fun markLayerThumbnailsDirty(layers: Collection<LayerId>) {
        if (layers.isEmpty()) return

        layerThumbnailPolicy.markDirty(layers)
        startLayerThumbnailLoop()
    }

    private fun updateLayerThumbnailState(
        before: LayerStack,
        after: LayerStack,
        pixelLayers: Collection<LayerId> = emptyList(),
    ) {
        val live = after.layers.mapTo(LinkedHashSet(), Layer::id)
        layerThumbnailPolicy.retain(live)
        _layerThumbnails.value = _layerThumbnails.value.filterKeys(live::contains)
        val changed = LinkedHashSet<LayerId>()
        changed += LayerThumbnailPolicy.changedLayers(before, after)
        changed += pixelLayers.filter(live::contains)
        markLayerThumbnailsDirty(changed)
    }

    private fun requestAction(action: CanvasDocumentAction) {
        layerRefusal = null
        updateInteractionUi()
        when (val decision = actionGate.request(action)) {
            is CanvasActionDecision.Run -> executeAction(decision.action)
            CanvasActionDecision.Parked -> Unit
            CanvasActionDecision.Rejected -> Unit
        }
    }

    private fun runNextPendingAction() {
        val action = actionGate.next() ?: return
        updateInteractionUi()
        executeAction(action)
    }

    private fun executeAction(action: CanvasDocumentAction) {
        layerRefusal = null
        updateInteractionUi()
        val stack = document?.stack
        when (action) {
            CanvasDocumentAction.Undo -> applyHistory(HistoryDirection.UNDO)
            CanvasDocumentAction.Redo -> applyHistory(HistoryDirection.REDO)
            is CanvasDocumentAction.SelectLayer -> selectLayerNow(action.index)
            CanvasDocumentAction.AddLayer -> applyStackResult(stack?.add(layerIds, layerCap))
            is CanvasDocumentAction.DeleteLayer -> applyStackResult(stack?.delete(action.index))
            is CanvasDocumentAction.DuplicateLayer ->
                applyStackResult(stack?.duplicate(action.index, layerIds, layerCap))
            is CanvasDocumentAction.MoveLayer ->
                applyStackResult(stack?.move(action.from, action.to))
            is CanvasDocumentAction.MergeDown -> applyStackResult(stack?.mergeDown(action.index))
            CanvasDocumentAction.Flatten -> applyStackResult(stack?.flatten(layerIds))
            is CanvasDocumentAction.ClearLayer -> applyStackResult(stack?.clear(action.index))
            is CanvasDocumentAction.RenameLayer ->
                applyStackResult(stack?.rename(action.index, action.name))
            is CanvasDocumentAction.SetLayerOpacity ->
                applyStackResult(stack?.setOpacity(action.index, action.opacity))
            is CanvasDocumentAction.ToggleLayerVisibility -> {
                val visible = stack?.layers?.getOrNull(action.index)?.props?.visible
                applyStackResult(visible?.let { stack.setVisible(action.index, !it) })
            }
            is CanvasDocumentAction.SetLayerBlendMode ->
                applyStackResult(stack?.setBlendMode(action.index, action.mode))
            is CanvasDocumentAction.ToggleLayerAlphaLock -> {
                val locked = stack?.layers?.getOrNull(action.index)?.props?.alphaLock
                applyStackResult(locked?.let { stack.setAlphaLock(action.index, !it) })
            }
            is CanvasDocumentAction.ToggleLayerLock -> {
                val locked = stack?.layers?.getOrNull(action.index)?.props?.locked
                applyStackResult(locked?.let { stack.setLocked(action.index, !it) })
            }
            is CanvasDocumentAction.SetPaperColor -> setPaperColorNow(action.color)
            CanvasDocumentAction.Leave -> beginLeave()
        }
        if (!applyBusy) runNextPendingAction()
    }

    private fun selectLayerNow(index: Int) {
        val doc = document ?: return
        val next = doc.stack.select(index)
        if (next == doc.stack) return
        val engine = session ?: return

        engine.setStack(next)
        document = doc.copy(stack = next)
        val state = _uiState.value
        if (state is UiState.Ready) _uiState.value = state.copy(stack = next)
        markDirty(DirtyKind.METADATA)
        noteChange()
    }

    private fun setPaperColorNow(color: Int) {
        val doc = document ?: return
        if (doc.paperColor == color) return
        val active = doc.stack.active.id
        applyStackEdit(
            StackEdit(
                stack = doc.stack,
                pixels = null,
                entry = HistoryEntry.PaperColor(
                    activeBefore = active,
                    activeAfter = active,
                    before = doc.paperColor,
                    after = color,
                ),
            ),
        )
    }

    private fun applyStackResult(result: StackResult?) {
        when (result) {
            is StackResult.Ok -> applyStackEdit(result.edit)
            is StackResult.Refused -> {
                emitLayerFeedback(result.reason)
            }
            null -> Unit
        }
    }

    private fun applyStackEdit(
        edit: StackEdit,
        work: DocumentWork = DocumentWork.START,
    ) {
        // An ALREADY_STARTED caller (the opacity gesture) holds the action
        // gate open; every early return here must hand it back or every
        // later document action parks forever.
        val doc = document ?: return abortStartedWork(work)
        val engine = session ?: return abortStartedWork(work)
        val invalidation = LayerEditPolicy.invalidation(doc.stack, edit.entry)
            ?: return abortStartedWork(work)
        val deleted = LayerEditPolicy.deletedLayers(doc.stack, edit.stack)
        val jobRef = AtomicReference<TileFlusher.FlushJob.WriteEntry?>()
        val pixelOps = listOfNotNull(edit.pixels)
        val changedKeys = LinkedHashSet<Pair<LayerId, TileKey>>().apply {
            addAll(HistoryCodec.payloadKeys(edit.entry))
            addAll(LayerEditPolicy.changedTiles(doc.stack, edit.pixels))
        }.toList()

        if (work == DocumentWork.START) actionGate.beginWork()
        updateHistoryUi()
        engine.applyLayerEdit(
            stack = edit.stack,
            pixelOps = pixelOps,
            invalidation = invalidation,
            beforeCommit = {
                val keys = HistoryCodec.payloadKeys(edit.entry)
                val job = TileFlusher.FlushJob.WriteEntry(
                    entry = edit.entry,
                    seq = nextSeq.getAndIncrement(),
                    ts = System.currentTimeMillis(),
                    mirrorBefore = flusher.captureMirror(keys),
                    changedKeys = changedKeys,
                    awaitReadback = { awaitReadbacks() },
                )
                if (!flusher.enqueueNow(job)) return@applyLayerEdit false
                jobRef.set(job)
                true
            },
        ) { result ->
            if (result == LayerEditResult.REFUSED) {
                engine.setStack(doc.stack)
                emitLayerFeedback(Refusal.NOOP)
                finishDocumentWork()
                return@applyLayerEdit
            }

            val paperColor = (edit.entry as? HistoryEntry.PaperColor)?.after ?: doc.paperColor
            layerCap = TracingReferencePolicy.layerCap(
                layerCount = edit.stack.layers.size,
                maxLayers = budgetLayerCap,
                reference = doc.tracingReference,
            )
            document = doc.copy(stack = edit.stack, paperColor = paperColor)
            val state = _uiState.value
            if (state is UiState.Ready) {
                _uiState.value = state.copy(
                    stack = edit.stack,
                    paperColor = paperColor,
                    layerCap = layerCap,
                )
            }
            updateLayerThumbnailState(
                before = doc.stack,
                after = edit.stack,
                pixelLayers = changedKeys.map { it.first },
            )
            emitLayerFeedback(refusal = null)
            if (paperColor != doc.paperColor) engine.setPaperColor(paperColor)
            markDirty(DirtyKind.PIXELS)
            noteChange()

            val job = jobRef.get()
            if (job == null) {
                finishDocumentWork()
                return@applyLayerEdit
            }
            appScope.launch {
                val stamped = job.result.await()
                val completion = job.completion.await()
                if (stamped != null && completion == TileFlusher.StepResult.COMPLETE) {
                    for (layer in deleted) {
                        flusher.enqueue(
                            TileFlusher.FlushJob.DeleteLayerDir(store.layerDir(projectId, layer)),
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    if (stamped == null) truncateRedoAfterUnjournaledEdit()
                    else pushHistory(stamped)
                    finishDocumentWork()
                }
            }
        }
    }

    private fun pushHistory(entry: HistoryEntry) {
        val j = journal ?: return
        val result = j.push(entry)
        // Files remain until a checkpoint no longer references them.
        pendingDeletes += result.truncated
        pendingDeletes += result.pruned
        document = document?.copy(historyCursor = j.cursor)
    }

    private fun truncateRedoAfterUnjournaledEdit() {
        val j = journal ?: return
        pendingDeletes += j.truncateRedo()
        document = document?.copy(historyCursor = j.cursor)
    }

    private fun finishDocumentWork() {
        val next = actionGate.finishWork()
        updateHistoryUi()
        updateInteractionUi()
        if (next != null) executeAction(next)
    }

    private fun abortStartedWork(work: DocumentWork) {
        if (work == DocumentWork.ALREADY_STARTED) finishDocumentWork()
    }

    private fun emitLayerFeedback(refusal: Refusal?) {
        layerRefusal = refusal
        layerFeedbackRevision += 1
        updateInteractionUi()
    }

    /** Main thread. One apply at a time; later requests are parked. */
    private fun applyHistory(direction: HistoryDirection) {
        if (applyBusy) return
        val j = journal ?: return
        val pixels = historyPixels ?: return
        val doc = document ?: return
        val entry = when (direction) {
            HistoryDirection.UNDO -> j.undo()
            HistoryDirection.REDO -> j.redo()
        } ?: return
        val historyEdit = when (val result = LayerHistory.apply(doc.stack, entry, direction)) {
            is LayerHistoryResult.Applied -> result.edit
            LayerHistoryResult.Corrupt -> {
                restoreHistoryCursor(direction)
                android.util.Log.w(TAG, "cannot apply ${HistoryCodec.kindOf(entry)}")
                return
            }
        }
        actionGate.beginWork()
        updateHistoryUi()
        appScope.launch {
            // The redo capture must see the post-edit pixels, including the
            // last stroke whose fence may still be pending.
            if (awaitReadbacks() == TileFlusher.ReadbackResult.PENDING) {
                withContext(Dispatchers.Main) { failHistoryApply(direction) }
                return@launch
            }
            var redoBytes: kotlinx.coroutines.CompletableDeferred<Long?>? = null
            val restores = when (direction) {
                HistoryDirection.UNDO -> pixels.beforeUndo(entry)?.let { undo ->
                    redoBytes = undo.redoBytes
                    undo.restores
                }
                HistoryDirection.REDO -> pixels.beforeRedo(entry)
            }
            if (restores == null) {
                withContext(Dispatchers.Main) { failHistoryApply(direction) }
                return@launch
            }
            val capturedRedoBytes = redoBytes?.await()
            if (redoBytes != null && capturedRedoBytes == null) {
                // Undo must not destroy the only post-edit pixels when the
                // redo sidecar could not be persisted.
                withContext(Dispatchers.Main) { failHistoryApply(direction) }
                return@launch
            }
            withContext(Dispatchers.Main) {
                applyPreparedHistory(
                    doc,
                    entry,
                    direction,
                    historyEdit,
                    restores,
                    capturedRedoBytes,
                    pixels,
                )
            }
        }
    }

    private fun applyPreparedHistory(
        doc: Document,
        entry: HistoryEntry,
        direction: HistoryDirection,
        historyEdit: LayerHistoryEdit,
        restores: List<HistoryPixels.Restore>,
        capturedRedoBytes: Long?,
        pixels: HistoryPixels,
    ) {
        val engine = session
        if (engine == null) {
            failHistoryApply(direction)
            return
        }

        val restoreOps = restores.map { PixelOp.Restore(it.layer, it.tiles) }
        val pixelOps = historyEdit.pixelOps + restoreOps
        val deleted = LayerEditPolicy.deletedLayers(doc.stack, historyEdit.stack)
        engine.applyLayerEdit(
            stack = historyEdit.stack,
            pixelOps = pixelOps,
            invalidation = ch.lkmc.bangnidraw.engine.core.SandwichPolicy.Op.UndoRedo,
            beforeCommit = { true },
        ) { result ->
            if (result == LayerEditResult.REFUSED) {
                failHistoryApply(direction)
                return@applyLayerEdit
            }

            val paperColor = historyEdit.paperColor ?: doc.paperColor
            // Restores know their exact outcome — null emptied a key, bytes
            // painted one — so the model takes them now rather than waiting
            // for the checkpoint fold. The later fold re-applies the same
            // outcomes from the readback sink, so the two never disagree, and
            // the GL side's exact key checks (a duplicate redo's source set,
            // for one) stop seeing fold lag (AGENTS.md).
            val foldedStack = LayerTileUpdates.apply(
                historyEdit.stack,
                restoreOutcomes(restores),
            )
            layerCap = TracingReferencePolicy.layerCap(
                layerCount = foldedStack.layers.size,
                maxLayers = budgetLayerCap,
                reference = doc.tracingReference,
            )
            document = doc.copy(
                stack = foldedStack,
                paperColor = paperColor,
                historyCursor = journal?.cursor ?: doc.historyCursor,
            )
            val state = _uiState.value
            if (state is UiState.Ready) {
                _uiState.value = state.copy(
                    stack = foldedStack,
                    paperColor = paperColor,
                    layerCap = layerCap,
                )
            }
            updateLayerThumbnailState(
                before = doc.stack,
                after = foldedStack,
                pixelLayers = foldedStack.layers.map(Layer::id),
            )
            if (paperColor != doc.paperColor) engine.setPaperColor(paperColor)
            markDirty(DirtyKind.PIXELS)
            noteChange()

            appScope.launch {
                val keys = historyFlushKeys(entry, pixelOps)
                pixels.flushChanged(keys)
                for (layer in deleted) {
                    flusher.enqueue(TileFlusher.FlushJob.DeleteLayerDir(store.layerDir(projectId, layer)))
                }
                withContext(Dispatchers.Main) {
                    if (capturedRedoBytes != null) {
                        accountRedoBytes(entry.seq, capturedRedoBytes)
                    }
                    finishDocumentWork()
                }
            }
        }
    }

    private fun accountRedoBytes(seq: Long, redoBytes: Long) {
        val j = journal ?: return
        // Accounting can now prune and move the main-thread-confined cursor.
        pendingDeletes += j.noteRedoBytes(seq, redoBytes)
        document = document?.copy(historyCursor = j.cursor)
    }

    private fun historyFlushKeys(
        entry: HistoryEntry,
        pixelOps: List<PixelOp>,
    ): List<Pair<LayerId, TileKey>> {
        val keys = LinkedHashSet<Pair<LayerId, TileKey>>()
        keys += HistoryCodec.payloadKeys(entry)
        for (op in pixelOps) {
            if (op !is PixelOp.Restore) continue
            keys += op.tiles.keys.map { op.layer to it }
        }
        return keys.toList()
    }

    /** Restore outcomes in the checkpoint fold's vocabulary. */
    private fun restoreOutcomes(
        restores: List<HistoryPixels.Restore>,
    ): Map<Pair<LayerId, TileKey>, TilePresence> = buildMap {
        // One entry's restores are per-layer maps whose keys are distinct
        // (payloadKeys), so no (layer, key) can repeat and order cannot matter.
        for (restore in restores) {
            for ((key, bytes) in restore.tiles) {
                put(restore.layer to key, presenceOf(bytes))
            }
        }
    }

    private fun failHistoryApply(direction: HistoryDirection) {
        restoreHistoryCursor(direction)
        finishDocumentWork()
    }

    private fun restoreHistoryCursor(direction: HistoryDirection) {
        when (direction) {
            HistoryDirection.UNDO -> journal?.redo()
            HistoryDirection.REDO -> journal?.undo()
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
        session?.onStrokeNotMerged = null
        session?.onRmwStarted = null
        session?.onRmwTilesTouched = null
        session?.onRmwCancelled = null
        session = next
        if (next == null) {
            cancelFill()
            // A dead context discards every uncommitted RMW pixel. Release its
            // capture and any action barrier that was awaiting GPU restore.
            rmwHistoryCapture.reset()
            finishRmwRestore()
        }
        val doc = document ?: return
        if (next != null) {
            next.onStrokeMerged = { spec, keys, revision -> onStrokeMerged(spec, keys, revision) }
            next.onStrokeNotMerged = ::onStrokeNotMerged
            next.onRmwStarted = ::onRmwStarted
            next.onRmwTilesTouched = ::onRmwTilesTouched
            next.onRmwCancelled = { spec, keys -> onRmwCancelled(next, spec, keys) }
            viewModelScope.launch(Dispatchers.IO) {
                streamTiles(next, doc)
                withContext(Dispatchers.Main) {
                    markLayerThumbnailsDirty(doc.stack.layers.map(Layer::id))
                }
            }
        }
    }

    /** Flushes the final committed stroke before navigation. */
    private fun beginLeave() {
        val afterWrite = checkNotNull(leaveAfterWrite) {
            "Leave dispatched without requestLeave"
        }

        actionGate.beginWork()
        updateInteractionUi()
        leaveHandedOff = false
        setClosing(true)
        leaveJob = appScope.launch {
            // The app scope has no exception handler: an uncaught failure
            // here would crash the process on its way out the door. A failed
            // flush keeps the canvas open — logged and toasted, not fatal —
            // and only a successful handoff to navigation keeps the scrim up
            // (it covers the exit transition); a swallowed navigation gets a
            // grace-period reset instead of a stranded scrim.
            var handedOff = false
            var flushed = false
            try {
                withContext(NonCancellable) { checkpoint(GallerySyncDecision.Trigger.LEAVE) }
                flushed = true
                withContext(Dispatchers.Main) { afterWrite() }
                handedOff = true
                leaveHandedOff = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "leave failed; canvas stays open", e)
                // Only a failed flush is a failed save; a navigation-only
                // failure owes no alarming toast. `handedOff` cannot tell the
                // two apart here — it is only set after afterWrite() — so the
                // flush carries its own flag.
                if (!flushed) withContext(Dispatchers.Main) { noteLeaveFailure() }
            } finally {
                val self = coroutineContext[Job]
                if (!handedOff) {
                    // Ownership-guarded like the success branch: on a
                    // rethrown cancellation this coroutine is already
                    // inactive while its finally runs, so a newer request
                    // may have started and only that job may clear. Runs on
                    // the main thread so the check and the clear cannot
                    // interleave with beginLeave() reassigning leaveJob.
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (leaveJob === self) finishLeaveAttempt()
                    }
                } else {
                    // If navigation was swallowed (a cancelled predictive-back
                    // gesture, an uncollected event), lift the scrim rather
                    // than stranding it. Harmless when navigation succeeded:
                    // the cleared ViewModel has no observers left. The delay
                    // runs NonCancellable because the rethrown cancellation
                    // above makes this a cancelling coroutine — a bare delay
                    // would throw and skip the reset.
                    withContext(NonCancellable) { delay(LEAVE_HANDOFF_GRACE_MS) }
                    // A newer leave may have started during the grace window;
                    // only the latest job owns clearing the scrim. Main-thread
                    // for the same atomicity reason as the failure branch.
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (leaveJob === self) finishLeaveAttempt()
                    }
                }
            }
        }
    }

    private fun finishLeaveAttempt() {
        setClosing(false)
        releaseLeaveGate()
    }

    private fun releaseLeaveGate() {
        leaveJob = null
        leaveAfterWrite = null
        leaveHandedOff = false
        actionGate.finishLeave()
        updateInteractionUi()
    }

    private fun noteLeaveFailure() {
        _uiState.update { state ->
            if (state is UiState.Ready) {
                state.copy(leaveNoticeRevision = state.leaveNoticeRevision + 1)
            } else {
                state
            }
        }
    }

    private fun setClosing(closing: Boolean) {
        // Atomic update, not read-copy-write: the finally above can run off
        // the main thread while the flusher's tickers emit state.
        _uiState.update { state ->
            if (state is UiState.Ready) state.copy(closing = closing) else state
        }
    }

    /**
     * The `ON_STOP` checkpoint (§6.2): fire-and-forget, cancellation-proof.
     * Gallery-wise it counts as a leave (06 §9.3's ON_STOP row) — the CPU
     * flatten has no GL thread to fail to get.
     */
    fun checkpointNow() {
        appScope.launch {
            withContext(NonCancellable) { checkpoint(GallerySyncDecision.Trigger.LEAVE) }
        }
    }

    override fun onCleared() {
        // Belt and braces behind [requestLeave]: whatever is still unwritten when the
        // screen is torn down gets one more drain. The session is gone by now,
        // so there is no readback left to wait on — release() already
        // delivered or dropped it.
        session?.onStrokeMerged = null
        session?.onStrokeNotMerged = null
        session?.onRmwStarted = null
        session?.onRmwTilesTouched = null
        session?.onRmwCancelled = null
        session = null
        appScope.launch {
            withContext(NonCancellable) { checkpoint(GallerySyncDecision.Trigger.LEAVE) }
        }
    }

    private fun markDirty(kind: DirtyKind) {
        synchronized(checkpointStateLock) { markDirtyLocked(kind) }
    }

    /** Caller owns [checkpointStateLock]. */
    private fun markDirtyLocked(kind: DirtyKind) {
        dirty = true
        when (kind) {
            DirtyKind.METADATA -> Unit
            DirtyKind.CONTENT -> contentDirty = true
            DirtyKind.PIXELS -> {
                contentDirty = true
                thumbDirty = true
            }
        }
        checkpointGeneration.noteChange()
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
            // A quiet/ceiling checkpoint, not a leave: the gallery debounce
            // gets the 30 s floor (06 §9.3).
            appScope.launch {
                withContext(NonCancellable) {
                    checkpoint(GallerySyncDecision.Trigger.CHECKPOINT)
                }
            }
        }
    }

    private suspend fun checkpoint(trigger: GallerySyncDecision.Trigger) {
        val doc = document ?: return
        checkpointMutex.withLock {
            if (!dirty && store.exists(doc.id)) return
            // §5.6's order: (readbacks land) → queued jobs and tiles flushed
            // → project.json last, the commit point → only then the files a
            // truncation or pruning dropped.
            if (awaitReadbacks() == TileFlusher.ReadbackResult.PENDING) return
            val generation = synchronized(checkpointStateLock) {
                checkpointGeneration.capture()
            }
            flusher.checkpointFlush()
            val now = System.currentTimeMillis()
            val snapshot = captureCheckpoint(generation, now) ?: return
            try {
                store.checkpoint(snapshot.document, snapshot.history)
                // Now — and only now — the dropped entries' files (§5.6).
                if (snapshot.deletes.isNotEmpty()) {
                    historyStore?.delete(snapshot.deletes)
                }
                // The thumbnail follows the checkpoint (06 §6.4): the tiles
                // it reads are on disk by the flush above, and only when
                // pixels actually changed — never per stroke.
                if (snapshot.thumbnailWork == ThumbnailWork.WRITE) {
                    Thumbnails.write(
                        snapshot.document,
                        layerDirFor = { store.layerDir(snapshot.document.id, it) },
                        target = File(store.projectDir(snapshot.document.id), "thumb.png"),
                    )
                }
                maybeSyncGallery(snapshot.document, trigger, snapshot.timestampMs)
                withContext(Dispatchers.Main) { finishCheckpoint(snapshot) }
            } catch (_: java.io.IOException) {
                // Same family as a failed tile write: the storage-full state
                // and its retry-on-next-checkpoint own this. `dirty` stays
                // true, so the next trigger tries again.
            }
        }
    }

    /** Captures and installs one immutable model revision on the main thread. */
    private suspend fun captureCheckpoint(
        generation: CheckpointGeneration.Snapshot,
        now: Long,
    ): CheckpointSnapshot? = withContext(Dispatchers.Main) {
        synchronized(checkpointStateLock) {
            if (checkpointGeneration.freshness(generation) == CheckpointFreshness.STALE) {
                return@synchronized null
            }
            val current = document ?: return@synchronized null
            val folded = fold(current, now)
            document = folded
            CheckpointSnapshot(
                document = folded,
                history = historyRecordForCheckpoint(folded),
                deletes = ArrayList(pendingDeletes),
                generation = generation,
                timestampMs = now,
                thumbnailWork = if (thumbDirty) ThumbnailWork.WRITE else ThumbnailWork.SKIP,
            )
        }
    }

    /** Main thread: journal metadata from the same model revision as the document. */
    private fun historyRecordForCheckpoint(doc: Document): HistoryRecord {
        val j = journal ?: return HistoryRecord(cursor = doc.historyCursor)
        val next = nextSeq.get()
        val stats = j.stats()
        return HistoryRecord(
            cursor = j.cursor,
            nextSeq = next,
            oldestSeq = j.entries.firstOrNull()?.seq ?: next,
            entries = stats.entries,
            bytes = stats.bytes,
        )
    }

    /** Clears only work still owned by this checkpoint's model revision. */
    private fun finishCheckpoint(snapshot: CheckpointSnapshot) {
        if (snapshot.deletes.isNotEmpty()) {
            pendingDeletes.removeAll(snapshot.deletes.toSet())
        }
        synchronized(checkpointStateLock) {
            if (checkpointGeneration.freshness(snapshot.generation) == CheckpointFreshness.STALE) {
                return@synchronized
            }
            dirty = false
            contentDirty = false
            thumbDirty = false
            dirtySinceMs = null
        }
    }

    /**
     * §9's mirror, after a checkpoint: the tiles it flattens are on disk by
     * the flush that just ran. The §9.3 debounce is [GallerySyncDecision]'s;
     * a newer sync cancels a running one (conflated), and a failed sync
     * changes nothing — the next trigger retries.
     */
    private suspend fun maybeSyncGallery(
        doc: Document,
        trigger: GallerySyncDecision.Trigger,
        now: Long,
    ) {
        if (!prefs.gallerySync.first()) return
        val pixelRevision = revisions.get()
        val due = GallerySyncDecision.isDue(
            trigger = trigger,
            pixelRevision = pixelRevision,
            lastSyncedRevision = lastSyncedRevision,
            nowMs = now,
            lastSyncAtMs = doc.lastGallerySyncAt,
        )
        if (!due) return
        gallerySyncJob?.cancel()
        gallerySyncJob = appScope.launch {
            val rgba = CpuFlatten.flatten(doc) { store.layerDir(doc.id, it) }
            coroutineContext.ensureActive()
            val png = ImageEncode.encode(rgba, doc.width, doc.height, ImageEncode.Format.PNG)
            coroutineContext.ensureActive()
            val name = GalleryNames.sanitizeDisplayName(
                doc.title,
                context.getString(R.string.studio_untitled),
            )
            val outcome = exporter.sync(
                recordedUri = doc.galleryUri,
                recordedModifiedAt = doc.galleryModifiedAt,
                recordedBytes = doc.galleryBytes,
                displayName = name,
                png = png,
            ) ?: return@launch
            withContext(Dispatchers.Main) {
                lastSyncedRevision = pixelRevision
                document = document?.copy(
                    galleryUri = outcome.galleryUri,
                    lastGallerySyncAt = outcome.syncedAt,
                    galleryModifiedAt = outcome.modifiedAt,
                    galleryBytes = outcome.bytes,
                )
                // Metadata only: the next checkpoint persists it, and
                // updatedAt stays put — a sync is looking, not painting.
                markDirty(DirtyKind.METADATA)
            }
        }
    }

    /**
     * The model's tile sets catch up with what readback delivered. Caller
     * owns [checkpointStateLock], pairing updates with the captured generation.
     */
    private fun fold(doc: Document, now: Long): Document {
        if (tileUpdates.isEmpty()) {
            return doc.copy(updatedAt = if (contentDirty) now else doc.updatedAt)
        }
        val updates = HashMap<Pair<LayerId, TileKey>, TilePresence>()
        tileUpdates.forEach { (subject, presence) ->
            if (tileUpdates.remove(subject, presence)) updates[subject] = presence
        }
        return doc.copy(
            stack = LayerTileUpdates.apply(doc.stack, updates),
            updatedAt = if (contentDirty) now else doc.updatedAt,
        )
    }

    /** Refuses persistence when bounded fence waits leave GPU pixels pending. */
    private suspend fun awaitReadbacks(): TileFlusher.ReadbackResult {
        val engine = session ?: return TileFlusher.ReadbackResult.COMPLETE
        val done = CompletableDeferred<ReadbackDrainResult>()
        engine.finishReadback(done::complete)
        return when (withTimeoutOrNull(READBACK_WAIT_MS) { done.await() }) {
            ReadbackDrainResult.COMPLETE -> TileFlusher.ReadbackResult.COMPLETE
            ReadbackDrainResult.PENDING,
            null,
            -> TileFlusher.ReadbackResult.PENDING
        }
    }

    private suspend fun streamTiles(engine: EngineSession, doc: Document) {
        // Uploads before the first frame would find no context and be
        // dropped; wait out the engine's own probe. An unsupported device
        // never flips isReady, so the wait is bounded and the uploads are
        // skipped — that screen shows no canvas anyway.
        withTimeoutOrNull(READY_WAIT_MS) {
            while (!engine.isEngineReady()) delay(16)
        } ?: run {
            android.util.Log.w(TAG, "engine not ready; document tiles not streamed")
            return
        }
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
        doc.tracingReference?.let { streamTracingReference(engine, doc.id, it) }
        if (corruptTiles > 0) {
            val state = _uiState.value
            if (state is UiState.Ready && state.warning == null) {
                _uiState.value = state.copy(
                    warning = warningFor(unreadableLayers = 0, unreadableTiles = corruptTiles),
                )
            }
        }
    }

    private suspend fun streamTracingReference(
        engine: EngineSession,
        projectId: String,
        reference: TracingReference,
    ) {
        withTimeoutOrNull(READY_WAIT_MS) {
            while (!engine.isEngineReady()) delay(16)
        } ?: run {
            android.util.Log.w(TAG, "engine not ready; tracing reference tiles not streamed")
            return
        }

        val read = referenceImageCodec.streamTiles(
            projectId = projectId,
            reference = reference,
            batchSize = UPLOAD_BATCH,
        ) { batch ->
            engine.uploadReferenceTiles(reference.assetName, batch)
        }
        if (read) return

        withContext(Dispatchers.Main) {
            if (document?.tracingReference?.assetName != reference.assetName) return@withContext

            applyTracingReference(null)
            dismissPanel()
            showReferenceNotice(R.string.err_reference_unreadable)
        }
    }

    private companion object {
        const val TAG = "CanvasViewModel"

        const val OPAQUE_ALPHA = 0xFF000000.toInt()
        const val OPAQUE_BLACK = OPAQUE_ALPHA

        /** ≥ [ch.lkmc.bangnidraw.engine.gl.Readback]'s 1 s fence timeout. */
        const val READBACK_WAIT_MS = 2_000L

        /** Well past the ~300 ms exit transition; lifts a stranded scrim. */
        const val LEAVE_HANDOFF_GRACE_MS = 3_000L

        const val READY_WAIT_MS = 5_000L

        const val LAYER_THUMBNAIL_POLL_MS = 100L

        const val FILL_INDICATOR_DELAY_MS = 150L
        const val FILL_PROGRESS_POLL_MS = 50L

        /** Tiles per GL `execute {}` block on the reopen and restore paths. */
        const val UPLOAD_BATCH = 16
    }
}
