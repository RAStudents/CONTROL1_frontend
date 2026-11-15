package studio.acmenra.control.feature.events.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class EventsViewModel : ViewModel() {
    private val _state = MutableStateFlow(EventsState())
    val state: StateFlow<EventsState> = _state.asStateFlow()

    fun toggleExportMode() {
        _state.update { it.copy(isExportModeActive = !it.isExportModeActive) }
    }
}
