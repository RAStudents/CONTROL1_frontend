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
    fun titleDisposalOnlyClearsIfMatchingOwnerToken() {
        val state = TopBarState()
        val ownerA = Any()
        val ownerB = Any()

        state.updateTitle("Events", ownerA)
        assertEquals("Events", state.title)

        // Screen B enters with the SAME title "Events" but its own owner token
        state.updateTitle("Events", ownerB)
        assertEquals("Events", state.title)

        // Screen A disposes: must NOT clear Screen B's title even though titles are identical
        state.clearTitle(ownerA)
        assertEquals("Events", state.title)

        // Screen B disposes: now title clears
        state.clearTitle(ownerB)
        assertNull(state.title)
    }

    @Test
    fun leadingActionDisposalOnlyClearsIfMatchingCurrentAction() {
        val state = TopBarState()
        val actionA: @Composable () -> Unit = {}
        val actionB: @Composable () -> Unit = {}

        state.leadingAction = actionA
        state.leadingAction = actionB

        if (state.leadingAction === actionA) {
            state.leadingAction = null
        }
        assertEquals(actionB, state.leadingAction)

        if (state.leadingAction === actionB) {
            state.leadingAction = null
        }
        assertNull(state.leadingAction)
    }
}
