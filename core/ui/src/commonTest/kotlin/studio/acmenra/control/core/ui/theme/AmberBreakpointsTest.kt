package studio.acmenra.control.core.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class AmberBreakpointsTest {
    @Test
    fun testMediumLayoutInvariant() {
        val totalMediumWidth = AmberDimens.EventsListMedium + AmberDimens.GapMedium + AmberDimens.EventsDetailMin
        assertEquals(760.dp, totalMediumWidth)
        assertEquals(AmberDimens.EventsMediumWorkspaceMin, totalMediumWidth)
    }

    @Test
    fun testBreakpointsDefinitions() {
        assertEquals(840.dp, AmberBreakpoints.EventsCompact)
        assertEquals(AmberBreakpoints.EventsCompact, AmberBreakpoints.EventsMedium)
        assertEquals(1120.dp, AmberBreakpoints.EventsWide)
        assertEquals(AmberDimens.NavBarHeight, AmberDimens.NavigationBarHeight)
    }
}
