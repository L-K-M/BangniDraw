package ch.lkmc.bangnidraw.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.bangnidraw.data.ProjectStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Studio's half of persistence: the shelf of paintings, newest first
 * (docs/plan/06-document-and-persistence.md §7).
 *
 * Roadmap 3a: the shelf lists what `ProjectStore` holds, so a painting made
 * on the Canvas is reopenable. The real grid — thumbnails, hold menu, the
 * storage readout — is 3c's; this list is what 3a's "paint, leave, reopen"
 * acceptance stands on.
 */
@HiltViewModel
class StudioViewModel @Inject constructor(
    private val store: ProjectStore,
) : ViewModel() {

    data class Painting(
        val id: String,
        val title: String,
        val updatedAtMillis: Long,
    )

    data class UiState(
        /** Newest first. */
        val paintings: List<Painting> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Re-lists the shelf — on first show and every return from the Canvas. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val paintings = store.list().map {
                Painting(id = it.id, title = it.title, updatedAtMillis = it.updatedAt)
            }
            _uiState.value = UiState(paintings)
        }
    }

    /** Mint the id a new painting will live under. */
    fun newPaintingId(): String = UUID.randomUUID().toString()
}
