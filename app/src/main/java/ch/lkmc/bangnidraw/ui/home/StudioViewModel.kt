package ch.lkmc.bangnidraw.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.CpuFlatten
import ch.lkmc.bangnidraw.data.GalleryExporter
import ch.lkmc.bangnidraw.data.GalleryNames
import ch.lkmc.bangnidraw.data.ImageEncode
import ch.lkmc.bangnidraw.data.Prefs
import ch.lkmc.bangnidraw.data.ProjectStore
import ch.lkmc.bangnidraw.data.ShareCache
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Document
import ch.lkmc.bangnidraw.engine.core.GallerySyncDecision
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.ui.canvas.readDeviceMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private var staleSyncJob: Job? = null

    data class Painting(
        val id: String,
        val title: String,
        val updatedAtMillis: Long,
        val thumbnail: File?,
        val bytes: Long,
        val galleryUri: String?,
    )

    data class UiState(
        /** Newest first. */
        val paintings: List<Painting> = emptyList(),
        /** Sum of the project folders — 08 §2's "the only question that justifies deleting". */
        val totalBytes: Long = 0L,
        val freeBytes: Long = 0L,
        /** False until the first listing lands, so "empty" is never a flash of a lie. */
        val loaded: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * The device budget the New Canvas dialog annotates its rows with. The
     * reference canvas only seeds the computation; the per-row layer counts
     * come from `MemoryBudget.maxLayersFor` per size (08 §2.1).
     */
    val budget: MemoryBudget.Result by lazy {
        MemoryBudget.compute(readDeviceMemory(context), CanvasSize(2048, 2048))
    }

    /** Re-lists the shelf — on first show and every return from the Canvas. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val listed = store.list()
            _uiState.value = UiState(
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
                freeBytes = store.freeBytes(),
                loaded = true,
            )
            syncStale(listed)
        }
    }

    /**
     * 06 §9.3's Studio-open row: any painting edited since its last gallery
     * sync catches up in the background, one at a time, on the CPU path. A
     * running sweep is left to finish rather than restarted per refresh.
     */
    private fun syncStale(listed: List<ProjectStore.Summary>) {
        if (staleSyncJob?.isActive == true) return
        val stale = listed.filter {
            GallerySyncDecision.isStaleOnDisk(it.updatedAt, it.lastGallerySyncAt)
        }
        if (stale.isEmpty()) return
        staleSyncJob = viewModelScope.launch(Dispatchers.IO) {
            if (!prefs.gallerySync.first()) return@launch
            for (summary in stale) {
                val doc = (store.load(summary.id) as? ProjectStore.LoadResult.Loaded)
                    ?.document ?: continue
                val rgba = CpuFlatten.flatten(doc) { store.layerDir(doc.id, it) }
                val png = ImageEncode.encode(rgba, doc.width, doc.height, ImageEncode.Format.PNG)
                val outcome = exporter.sync(
                    recordedUri = doc.galleryUri,
                    recordedModifiedAt = doc.galleryModifiedAt,
                    recordedBytes = doc.galleryBytes,
                    displayName = GalleryNames.sanitizeDisplayName(
                        doc.title,
                        context.getString(R.string.studio_untitled),
                    ),
                    png = png,
                ) ?: continue
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

    /**
     * §9.5's share: the same flatten, staged in `cacheDir/share` and served
     * through the FileProvider; [onReady] gets the grantable URI and mime on
     * the main thread and fires the chooser.
     */
    fun share(id: String, format: ImageEncode.Format, onReady: (android.net.Uri, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val doc = (store.load(id) as? ProjectStore.LoadResult.Loaded)?.document
                ?: return@launch
            val rgba = CpuFlatten.flatten(doc) { store.layerDir(doc.id, it) }
            val bytes = ImageEncode.encode(rgba, doc.width, doc.height, format, quality = 90)
            val name = GalleryNames.sanitizeDisplayName(
                doc.title,
                context.getString(R.string.studio_untitled),
            )
            val ext = if (format == ImageEncode.Format.PNG) "png" else "jpg"
            val mime = if (format == ImageEncode.Format.PNG) "image/png" else "image/jpeg"
            val uri = try {
                shareCache.stage("$name.$ext", bytes)
            } catch (e: IOException) {
                android.util.Log.w("StudioViewModel", "share staging failed", e)
                return@launch
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
    fun saveAsNewGalleryItem(id: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val doc = (store.load(id) as? ProjectStore.LoadResult.Loaded)?.document
            val ok = if (doc == null) {
                false
            } else {
                val rgba = CpuFlatten.flatten(doc) { store.layerDir(doc.id, it) }
                exporter.saveAs(
                    GalleryNames.sanitizeDisplayName(
                        doc.title,
                        context.getString(R.string.studio_untitled),
                    ),
                    ImageEncode.encode(rgba, doc.width, doc.height, ImageEncode.Format.PNG),
                )
            }
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /**
     * The hold menu's delete, after its confirm dialog (06 §8). The gallery
     * copy goes only when the checkbox said so — it is the user's, and best
     * effort either way.
     */
    fun delete(id: String, alsoGallery: Boolean, galleryUri: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (alsoGallery && galleryUri != null) exporter.delete(galleryUri)
            store.delete(id)
            refresh()
        }
    }

    /**
     * The hold menu's duplicate (06 §8): tiles yes, history no, fresh ids.
     * The localized " copy" suffix is built here — the store never holds
     * display text — and an empty source title gets the localized fallback
     * first, so the copy is never titled just "copy".
     */
    fun duplicate(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.duplicate(id, titleTransform = { old ->
                old.ifEmpty { context.getString(R.string.studio_untitled) } +
                    context.getString(R.string.studio_copy_suffix)
            })
            refresh()
        }
    }

    /** The hold menu's rename (06 §8); a blank title keeps the old name. */
    fun rename(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            store.rename(id, trimmed)
            refresh()
        }
    }
}
