package studio.acmenra.control.navigation

import studio.acmenra.control.core.ui.components.TopBarState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TopBarStateTest {
    @Test
    fun defaultTopBarStateIsEmpty() {
        val state = TopBarState()
        assertNull(state.title)
        assertNull(state.leadingAction)
    }

    @Test
    fun stateCanBeUpdatedAndCleared() {
        val state = TopBarState()
        state.title = "Events"
        assertEquals("Events", state.title)

        state.title = null
        assertNull(state.title)
    }
}
