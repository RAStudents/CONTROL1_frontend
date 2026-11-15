package studio.acmenra.control.navigation

import androidx.compose.runtime.Composable
import studio.acmenra.control.core.ui.components.TopBarState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TopBarStateTest {
    @Test
    fun defaultTopBarStateIsEmpty() {
        val state = TopBarState()
        assertNull(state.title)
        assertNull(state.leadingAction)
        assertNotNull(state.actions)
    }

    @Test
    fun stateTitleCanBeUpdatedAndCleared() {
        val state = TopBarState()
        state.title = "Events"
        assertEquals("Events", state.title)

        state.title = null
        assertNull(state.title)
    }

    @Test
    fun stateLeadingActionCanBeUpdatedAndCleared() {
        val state = TopBarState()
        val dummyAction: @Composable () -> Unit = {}
        state.leadingAction = dummyAction
        assertNotNull(state.leadingAction)

        state.leadingAction = null
        assertNull(state.leadingAction)
    }

    @Test
    fun stateActionsCanBeUpdatedAndReset() {
        val state = TopBarState()
        val initialActions = state.actions

        state.actions = {}
        assertNotNull(state.actions)

        state.actions = initialActions
        assertEquals(initialActions, state.actions)
    }
}
