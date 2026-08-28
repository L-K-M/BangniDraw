package ch.lkmc.bangnidraw.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.bangnidraw.data.Prefs
import ch.lkmc.bangnidraw.engine.core.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Owns appearance above navigation so Studio and Canvas switch together. */
@HiltViewModel
internal class AppThemeViewModel @Inject constructor(prefs: Prefs) : ViewModel() {

    /** Null keeps the launch window visible until storage emits. */
    internal data class UiState(val appTheme: AppTheme? = null)

    internal val uiState: StateFlow<UiState> = prefs.appTheme
        .map(::UiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = UiState(),
        )
}
