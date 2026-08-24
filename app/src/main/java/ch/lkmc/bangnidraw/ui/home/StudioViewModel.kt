package ch.lkmc.bangnidraw.ui.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject

/**
 * The Studio's half of persistence: the shelf of paintings, newest first,
 * and what it costs (docs/plan/06-document-and-persistence.md "Studio
 * listing").
 *
 * Scaffold state (roadmap step 1): there is no [ProjectStore] yet, so the
 * shelf is empty and "new" only mints an id for the Canvas placeholder to
 * open. Step 3 replaces the body of this class, not its shape — one
 * immutable [UiState], one writer.
 */
@HiltViewModel
class StudioViewModel @Inject constructor() : ViewModel() {

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

    /** Mint the id a new painting will live under. */
    fun newPaintingId(): String = UUID.randomUUID().toString()
}
