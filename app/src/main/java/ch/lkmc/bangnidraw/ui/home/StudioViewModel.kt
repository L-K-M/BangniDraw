package ch.lkmc.bangnidraw.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.CpuFlatten
import ch.lkmc.bangnidraw.data.GalleryExporter
import ch.lkmc.bangnidraw.data.GalleryExportOutcome
import ch.lkmc.bangnidraw.data.GalleryNames
import ch.lkmc.bangnidraw.data.ImageEncode
import ch.lkmc.bangnidraw.data.Prefs
import ch.lkmc.bangnidraw.data.ProjectStore
import ch.lkmc.bangnidraw.data.ShareCache
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.GallerySyncDecision
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.PenButtonAction
import ch.lkmc.bangnidraw.engine.core.PressurePreference
import ch.lkmc.bangnidraw.engine.core.TouchDrawingMode
import ch.lkmc.bangnidraw.ui.canvas.readDeviceMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Studio's half of persistence (roadmap 3c,
 * `docs/plan/06-document-and-persistence.md` §7, §8;
 * `docs/plan/08-ui-and-layout.md` §2): the shelf, newest first, with its
 * storage readout; creating a painting from the New Canvas dialog's spec;
 * the hold menu's verbs; and — since step 4 — the gallery mirror's
 * Studio-open sweep and the share path.
 */
@HiltViewModel
class StudioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ProjectStore,
    private val prefs: Prefs,
    private val exporter: GalleryExporter,
    private val shareCache: ShareCache,
) : ViewModel() {

    private val projectWorkLock = Any()
    private val projectWorkMutex = Mutex()
    private val projectWorkPolicy = StudioProjectWorkPolicy()
    private val projectJobs = mutableMapOf<Job, StudioProjectJobKind>()

    internal data class Painting(
        val id: String,
        val title: String,
        val updatedAtMillis: Long,
        val thumbnail: File?,
        val bytes: Long,
        val galleryUri: String?,
    )

    internal data class UiState(
        /** Newest first. */
        val paintings: List<Painting> = emptyList(),
        /** Sum of the project folders — 08 §2's "the only question that justifies deleting". */
        val totalBytes: Long = 0L,
        val freeBytes: Long = 0L,
        /** False until the first listing lands, so "empty" is never a flash of a lie. */
        val loaded: Boolean = false,
        val handedness: Hand = Hand.RIGHT,
        val touchDrawingMode: TouchDrawingMode = TouchDrawingMode.ENABLED,
        val penButtonAction: PenButtonAction = PenButtonAction.Eraser,
        val pressurePreference: PressurePreference = PressurePreference.LINEAR,
        val snapRightAngles: Boolean = false,
        val hapticsMode: HapticsMode = HapticsMode.ENABLED,
        val gallerySync: Boolean = true,
        val mixerChoice: MixerChoice = MixerChoice.PIGMENT,
        val debugLatency: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    internal val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * The device budget the New Canvas dialog annotates its rows with. The
     * reference canvas only seeds the computation; the per-row layer counts
     * come from `MemoryBudget.maxLayersFor` per size (08 §2.1).
     */
    internal val budget: MemoryBudget.Result by lazy {
        MemoryBudget.compute(readDeviceMemory(context), CanvasSize(2048, 2048))
    }

    init {
        collectPreferences()
    }

    private fun collectPreferences() {
        viewModelScope.launch {
            prefs.handedness.collect { value -> _uiState.update { it.copy(handedness = value) } }
        }
        viewModelScope.launch {
            prefs.touchDrawingMode.collect { value ->
                _uiState.update { it.copy(touchDrawingMode = value) }
            }
        }
        viewModelScope.launch {
            prefs.penButtonAction.collect { value ->
                _uiState.update { it.copy(penButtonAction = value) }
            }
        }
        viewModelScope.launch {
            prefs.pressurePreference.collect { value ->
                _uiState.update { it.copy(pressurePreference = value) }
            }
        }
        viewModelScope.launch {
            prefs.snapRightAngles.collect { value ->
                _uiState.update { it.copy(snapRightAngles = value) }
            }
        }
        viewModelScope.launch {
            prefs.hapticsMode.collect { value -> _uiState.update { it.copy(hapticsMode = value) } }
        }
        viewModelScope.launch {
            prefs.gallerySync.collect { value -> _uiState.update { it.copy(gallerySync = value) } }
        }
        viewModelScope.launch {
            prefs.mixerChoice.collect { value -> _uiState.update { it.copy(mixerChoice = value) } }
        }
        viewModelScope.launch {
            prefs.debugLatency.collect { value -> _uiState.update { it.copy(debugLatency = value) } }
        }
    }

    /** Re-lists the shelf — on first show and every return from the Canvas. */
    fun refresh() {
        synchronized(projectWorkLock) {
            projectWorkPolicy.resumeStudio()
        }

        refreshShelf()
    }

    private fun refreshShelf() {
        launchProjectJob(
            kind = StudioProjectJobKind.REFRESH,
            admit = projectWorkPolicy::beginRead,
        ) {
            val listed = store.list()
            currentCoroutineContext().ensureActive()
            val freeBytes = store.freeBytes()
            currentCoroutineContext().ensureActive()

            _uiState.update { current ->
                current.copy(
                    paintings = listed.map {
                        Painting(
                            id = it.id,
                            title = it.title,
                            updatedAtMillis = it.updatedAt,
                            thumbnail = it.thumbnail,
                            bytes = it.bytes,
                            galleryUri = it.galleryUri,
                        )
                    },
                    totalBytes = listed.sumOf { it.bytes },
                    freeBytes = freeBytes,
                    loaded = true,
                )
            }

            syncStale(listed)
        }
    }

    internal fun setHandedness(value: Hand) {
        viewModelScope.launch { prefs.setHandedness(value) }
    }

    internal fun setTouchDrawingMode(value: TouchDrawingMode) {
        viewModelScope.launch { prefs.setTouchDrawingMode(value) }
    }

    internal fun setPenButtonAction(value: PenButtonAction) {
        viewModelScope.launch { prefs.setPenButtonAction(value) }
    }

    internal fun setPressurePreference(value: PressurePreference) {
        viewModelScope.launch { prefs.setPressurePreference(value) }
    }

    internal fun setSnapRightAngles(value: Boolean) {
        viewModelScope.launch { prefs.setSnapRightAngles(value) }
    }

    internal fun setHapticsMode(value: HapticsMode) {
        viewModelScope.launch { prefs.setHapticsMode(value) }
    }

    internal fun setGallerySync(value: Boolean) {
        if (!value) {
            synchronized(projectWorkLock) {
                projectJobs.filterValues { it == StudioProjectJobKind.SWEEP }.keys.toList()
            }.forEach(Job::cancel)
        }

        viewModelScope.launch {
            prefs.setGallerySync(value)
            if (value) refreshShelf()
        }
    }

    internal fun setMixerChoice(value: MixerChoice) {
        viewModelScope.launch { prefs.setMixerChoice(value) }
    }

    internal fun setDebugLatency(value: Boolean) {
        viewModelScope.launch { prefs.setDebugLatency(value) }
    }

    /**
     * 06 §9.3's Studio-open row: any painting edited since its last gallery
     * sync catches up in the background, one at a time, on the CPU path. A
     * running sweep is left to finish rather than restarted per refresh.
     */
    private fun syncStale(listed: List<ProjectStore.Summary>) {
        val stale = listed.filter {
            GallerySyncDecision.isStaleOnDisk(it.updatedAt, it.lastGallerySyncAt)
        }
        if (stale.isEmpty()) return

        launchProjectJob(
            kind = StudioProjectJobKind.SWEEP,
            admit = projectWorkPolicy::beginSweep,
        ) sweep@{
            if (!prefs.gallerySync.first()) return@sweep

            for (summary in stale) {
                currentCoroutineContext().ensureActive()
                val doc = (store.load(summary.id) as? ProjectStore.LoadResult.Loaded)
                    ?.document ?: continue
                currentCoroutineContext().ensureActive()

                val rgba = CpuFlatten.flatten(doc) { store.layerDir(doc.id, it) }
                currentCoroutineContext().ensureActive()
                val png = ImageEncode.encode(rgba, doc.width, doc.height, ImageEncode.Format.PNG)
                currentCoroutineContext().ensureActive()

                // Once MediaStore changes, record its outcome before an open
                // can observe the project.
                withContext(NonCancellable) {
                    val outcome = exporter.sync(
                        recordedUri = doc.galleryUri,
                        recordedModifiedAt = doc.galleryModifiedAt,
                        recordedBytes = doc.galleryBytes,
                        displayName = GalleryNames.sanitizeDisplayName(
                            doc.title,
                            context.getString(R.string.studio_untitled),
                        ),
                        png = png,
                    ) ?: return@withContext
                    store.updateGalleryFields(
                        doc.id,
                        galleryUri = outcome.galleryUri,
                        lastGallerySyncAt = outcome.syncedAt,
                        galleryModifiedAt = outcome.modifiedAt,
                        galleryBytes = outcome.bytes,
                    )
                }
            }
        }
    }

    /** Opens only after every Studio reader has released the project files. */
    fun openPainting(id: String, onReady: (String) -> Unit) {
        val jobs = synchronized(projectWorkLock) {
            if (!projectWorkPolicy.beginOpen()) return

            projectJobs.map { (job, kind) -> TrackedProjectJob(job, kind) }
        }

        viewModelScope.launch {
            cancelAndJoinBackgroundJobs(jobs)
            projectWorkMutex.withLock { onReady(id) }
        }
    }

    private fun launchProjectJob(
        kind: StudioProjectJobKind,
        admit: () -> Boolean,
        block: suspend () -> Unit,
    ): Job? {
        lateinit var job: Job
        synchronized(projectWorkLock) {
            if (!admit()) return null

            job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    projectWorkMutex.withLock { block() }
                } finally {
                    finishProjectJob(job, kind)
                }
            }
            projectJobs[job] = kind
        }
        job.start()

        return job
    }

    private fun finishProjectJob(job: Job, kind: StudioProjectJobKind) {
        synchronized(projectWorkLock) {
            projectJobs.remove(job)
            when (kind) {
                StudioProjectJobKind.SWEEP -> projectWorkPolicy.finishSweep()
                StudioProjectJobKind.MUTATION -> projectWorkPolicy.finishMutation()
                StudioProjectJobKind.REFRESH,
                StudioProjectJobKind.READ,
                -> Unit
            }
        }
    }

    private suspend fun cancelAndJoinBackgroundJobs(jobs: List<TrackedProjectJob>) {
        jobs.filter { it.kind != StudioProjectJobKind.MUTATION }
            .forEach { it.job.cancel() }

        for (tracked in jobs) {
            if (tracked.kind == StudioProjectJobKind.MUTATION) {
                tracked.job.join()
                continue
            }

            tracked.job.cancelAndJoin()
        }
    }

    /**
     * §9.5's share: the same flatten, staged in `cacheDir/share` and served
     * through the FileProvider; [onReady] gets the grantable URI and mime on
     * the main thread and fires the chooser.
     */
    fun share(id: String, format: ImageEncode.Format, onReady: (android.net.Uri, String) -> Unit) {
        launchProjectJob(
            kind = StudioProjectJobKind.READ,
            admit = projectWorkPolicy::beginRead,
        ) share@{
            val doc = (store.load(id) as? ProjectStore.LoadResult.Loaded)?.document
                ?: return@share
            val rgba = CpuFlatten.flatten(doc) { store.layerDir(doc.id, it) }
            val bytes = ImageEncode.encode(rgba, doc.width, doc.height, format, quality = 90)
            val name = GalleryNames.sanitizeDisplayName(
                doc.title,
                context.getString(R.string.studio_untitled),
            )
            val ext = if (format == ImageEncode.Format.PNG) "png" else "jpg"
            val mime = if (format == ImageEncode.Format.PNG) "image/png" else "image/jpeg"
            currentCoroutineContext().ensureActive()
            val uri = try {
                shareCache.stage("$name.$ext", bytes)
            } catch (e: IOException) {
                android.util.Log.w("StudioViewModel", "share staging failed", e)
                return@share
            }
            withContext(Dispatchers.Main) { onReady(uri, mime) }
        }
    }

    /**
     * Creates the painting the dialog specified and navigates once its folder
     * exists (08 §2.1: create, then open — the Canvas always finds a folder).
     * The title is minted here — "Sketch N", localized, numbers never reused
     * (06 §10).
     */
    fun createPainting(size: CanvasSize, paperColor: Int, onCreated: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val number = prefs.nextSketchNumber()
            val title = context.getString(R.string.studio_untitled) + " " + number
            val now = System.currentTimeMillis()
            val document = Document(
                id = id,
                title = title,
                width = size.width,
                height = size.height,
                paperColor = paperColor,
                stack = LayerStack.initial { LayerId(UUID.randomUUID().toString()) },
                createdAt = now,
                updatedAt = now,
            )
            try {
                store.create(document)
            } catch (e: IOException) {
                android.util.Log.w("StudioViewModel", "create failed", e)
                return@launch
            }
            withContext(Dispatchers.Main) { onCreated(id) }
        }
    }

    /**
     * §9.5's export "Save as…": a fresh gallery item, not the mirror.
     * [onDone] reports success on the main thread for the toast.
     */
    internal fun saveAsNewGalleryItem(id: String, onDone: (GalleryExportOutcome) -> Unit) {
        launchProjectJob(
            kind = StudioProjectJobKind.READ,
            admit = projectWorkPolicy::beginRead,
        ) {
            val doc = (store.load(id) as? ProjectStore.LoadResult.Loaded)?.document
            val ok = if (doc == null) {
                false
            } else {
                val rgba = CpuFlatten.flatten(doc) { store.layerDir(doc.id, it) }
                val png = ImageEncode.encode(
                    rgba,
                    doc.width,
                    doc.height,
                    ImageEncode.Format.PNG,
                )
                currentCoroutineContext().ensureActive()
                exporter.saveAs(
                    GalleryNames.sanitizeDisplayName(
                        doc.title,
                        context.getString(R.string.studio_untitled),
                    ),
                    png,
                    ImageEncode.Format.PNG,
                )
            }
            val outcome = if (ok) GalleryExportOutcome.SUCCESS else GalleryExportOutcome.FAILURE
            withContext(Dispatchers.Main) { onDone(outcome) }
        }
    }

    private fun launchProjectMutation(block: suspend () -> Unit) {
        var backgroundJobs = emptyList<TrackedProjectJob>()
        lateinit var job: Job
        synchronized(projectWorkLock) {
            if (!projectWorkPolicy.beginMutation()) return

            backgroundJobs = projectJobs
                .filterValues { it != StudioProjectJobKind.MUTATION }
                .map { (activeJob, kind) -> TrackedProjectJob(activeJob, kind) }
            job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    cancelAndJoinBackgroundJobs(backgroundJobs)
                    projectWorkMutex.withLock { block() }
                } finally {
                    finishProjectJob(job, StudioProjectJobKind.MUTATION)
                }

                refreshShelf()
            }
            projectJobs[job] = StudioProjectJobKind.MUTATION
        }
        job.start()
    }

    /**
     * The hold menu's delete, after its confirm dialog (06 §8). The gallery
     * copy goes only when the checkbox said so — it is the user's, and best
     * effort either way.
     */
    fun delete(id: String, alsoGallery: Boolean, galleryUri: String?) {
        launchProjectMutation {
            val currentGalleryUri = if (alsoGallery) {
                (store.load(id) as? ProjectStore.LoadResult.Loaded)
                    ?.document?.galleryUri ?: galleryUri
            } else {
                null
            }
            if (currentGalleryUri != null) exporter.delete(currentGalleryUri)
            store.delete(id)
        }
    }

    /**
     * The hold menu's duplicate (06 §8): tiles yes, history no, fresh ids.
     * The localized " copy" suffix is built here — the store never holds
     * display text — and an empty source title gets the localized fallback
     * first, so the copy is never titled just "copy".
     */
    fun duplicate(id: String) {
        launchProjectMutation {
            store.duplicate(id, titleTransform = { old ->
                old.ifEmpty { context.getString(R.string.studio_untitled) } +
                    context.getString(R.string.studio_copy_suffix)
            })
        }
    }

    /** The hold menu's rename (06 §8); a blank title keeps the old name. */
    fun rename(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        launchProjectMutation {
            store.rename(id, trimmed)
        }
    }
}

private enum class StudioProjectJobKind { REFRESH, SWEEP, READ, MUTATION }

private data class TrackedProjectJob(
    val job: Job,
    val kind: StudioProjectJobKind,
)

/** Pure admission rule for Studio jobs that touch project files. */
internal class StudioProjectWorkPolicy {
    private var openingPainting = false
    private var mutationCount = 0
    private var sweepRunning = false

    fun resumeStudio() {
        openingPainting = false
    }

    fun beginRead(): Boolean {
        if (openingPainting || mutationCount > 0) return false

        return true
    }

    fun beginSweep(): Boolean {
        if (!beginRead() || sweepRunning) return false

        sweepRunning = true
        return true
    }

    fun finishSweep() {
        sweepRunning = false
    }

    fun beginOpen(): Boolean {
        if (openingPainting) return false

        openingPainting = true
        return true
    }

    fun beginMutation(): Boolean {
        if (openingPainting) return false

        mutationCount += 1
        return true
    }

    fun finishMutation() {
        check(mutationCount > 0)
        mutationCount -= 1
    }
}
