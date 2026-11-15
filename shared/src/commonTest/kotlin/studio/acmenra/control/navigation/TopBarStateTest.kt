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
    fun titleDisposalOnlyClearsIfMatchingCurrentTitle() {
        val state = TopBarState()
        val titleA = "Screen A"
        state.title = titleA
        assertEquals("Screen A", state.title)

        // Screen B enters before Screen A disposes
        val titleB = "Screen B"
        state.title = titleB
        assertEquals("Screen B", state.title)

        // Screen A disposes (matching ProvideTopBar onDispose contract)
        if (state.title == titleA) {
            state.title = null
        }
        // Must remain Screen B
        assertEquals("Screen B", state.title)

        // Screen B disposes
        if (state.title == titleB) {
            state.title = null
        }
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
