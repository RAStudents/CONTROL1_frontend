package studio.acmenra.control.navigation

import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.ic_nav_events
import studio.acmenra.control.core.ui.generated.resources.nav_item_events
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppNavigationItemTest {
    @Test
    fun testDefaultItemsContainEvents() {
        assertEquals(1, AppNavigationItem.DEFAULT_ITEMS.size)
        assertEquals(AppNavigationItem.Events, AppNavigationItem.DEFAULT_ITEMS.first())
    }

    @Test
    fun testEventsItemProperties() {
        val item = AppNavigationItem.Events
        assertEquals(Res.string.nav_item_events, item.labelRes)
        assertEquals(Res.drawable.ic_nav_events, item.iconRes)
    }

    @Test
    fun testIsSelectedWithNullDestination() {
        assertFalse(AppNavigationItem.Events.isSelected(null))
    }
}
