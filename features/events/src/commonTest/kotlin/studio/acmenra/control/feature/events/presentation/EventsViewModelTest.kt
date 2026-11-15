package studio.acmenra.control.feature.events.presentation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventsViewModelTest {
    @Test
    fun defaultStateHasExportModeInactive() {
        val viewModel = EventsViewModel()
        assertFalse(viewModel.state.value.isExportModeActive)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun toggleExportModeTogglesState() {
        val viewModel = EventsViewModel()
        assertFalse(viewModel.state.value.isExportModeActive)

        viewModel.toggleExportMode()
        assertTrue(viewModel.state.value.isExportModeActive)

        viewModel.toggleExportMode()
        assertFalse(viewModel.state.value.isExportModeActive)
    }
}
