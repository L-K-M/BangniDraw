package ch.lkmc.bangnidraw.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.Prefs
import ch.lkmc.bangnidraw.data.ProjectStore
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Document
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Studio's half of persistence (roadmap 3c,
 * `docs/plan/06-document-and-persistence.md` §7, §8;
 * `docs/plan/08-ui-and-layout.md` §2): the shelf, newest first, with its
 * storage readout; creating a painting from the New Canvas dialog's spec;
 * delete and rename from the hold menu. Duplicate and share stay stubs until
 * roadmap step 4.
 */
@HiltViewModel
class StudioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ProjectStore,
    private val prefs: Prefs,
) : ViewModel() {

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

    /** The hold menu's delete, after its confirm dialog (06 §8). */
    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
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
