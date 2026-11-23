package studio.acmenra.control.feature.events.presentation

import androidx.compose.ui.input.key.Key
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.presentation.util.EventsKeyboardNavigationHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class EventsKeyboardNavigationTest {
    private fun createTestEvent(id: String): Event =
        Event(
            id = id,
            eventVersion = 1L,
            classificationVersion = 1L,
            acknowledgementVersion = 1L,
            eventTypeId = "type-1",
            eventTypeName = "Test Event",
            categoryCode = "safety",
            iconKey = EventIconKey.GENERIC_ALERT,
            locationId = "loc-1",
            locationName = "Location A",
            deviceId = "dev-1",
            deviceName = "Device 1",
            occurredAt = Instant.parse("2026-03-14T12:00:00Z"),
            receivedAt = Instant.parse("2026-03-14T12:00:01Z"),
            status = EventAcknowledgementStatus.NEW,
            acknowledgedAt = null,
            acknowledgedBy = null,
            isErroneous = false,
            previewUrl = "https://example.com/preview.jpg",
            videoUrl = null,
        )

    @Test
    fun testDownArrow_Navigation() {
        val events = listOf(createTestEvent("ev-1"), createTestEvent("ev-2"), createTestEvent("ev-3"))
        var selectedId: String? = null

        // 1. Initial Down arrow with no selection -> selects first item
        val handled1 =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionDown,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertTrue(handled1)
        assertEquals("ev-1", selectedId)

        // 2. Down arrow from ev-1 -> moves to ev-2
        val handled2 =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionDown,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertTrue(handled2)
        assertEquals("ev-2", selectedId)

        // 3. Down arrow from ev-2 -> moves to ev-3
        val handled3 =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionDown,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertTrue(handled3)
        assertEquals("ev-3", selectedId)

        // 4. Boundary condition: Down arrow at last item retains focus and returns true
        val handled4 =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionDown,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertTrue(handled4)
        assertEquals("ev-3", selectedId)
    }

    @Test
    fun testUpArrow_Navigation() {
        val events = listOf(createTestEvent("ev-1"), createTestEvent("ev-2"), createTestEvent("ev-3"))
        var selectedId: String? = "ev-3"

        // 1. Up arrow from ev-3 -> moves to ev-2
        val handled1 =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionUp,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertTrue(handled1)
        assertEquals("ev-2", selectedId)

        // 2. Up arrow from ev-2 -> moves to ev-1
        val handled2 =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionUp,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertTrue(handled2)
        assertEquals("ev-1", selectedId)

        // 3. Boundary condition: Up arrow at first item retains focus and returns true
        val handled3 =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionUp,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertTrue(handled3)
        assertEquals("ev-1", selectedId)
    }

    @Test
    fun testEscape_DeselectsOnlyInWideAndMedium() {
        val events = listOf(createTestEvent("ev-1"))
        var selectedId: String? = "ev-1"

        // 1. Wide / Medium (!isCompact): Escape clears selection
        val handledWide =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.Escape,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertTrue(handledWide)
        assertEquals(null, selectedId)

        // 2. Compact (isCompact = true): Escape is ignored by list
        selectedId = "ev-1"
        val handledCompact =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.Escape,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = true,
                onSelectEvent = { selectedId = it },
            )
        assertFalse(handledCompact)
        assertEquals("ev-1", selectedId)
    }

    @Test
    fun testModifiersAndKeyUp_AreIgnored() {
        val events = listOf(createTestEvent("ev-1"), createTestEvent("ev-2"))
        var selectedId: String? = "ev-1"

        // Ctrl + Down
        val handledCtrl =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionDown,
                isCtrlPressed = true,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertFalse(handledCtrl)
        assertEquals("ev-1", selectedId)

        // Alt + Up
        val handledAlt =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionUp,
                isCtrlPressed = false,
                isAltPressed = true,
                isMetaPressed = false,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertFalse(handledAlt)
        assertEquals("ev-1", selectedId)

        // Meta + Down
        val handledMeta =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionDown,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = true,
                isKeyDown = true,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertFalse(handledMeta)
        assertEquals("ev-1", selectedId)

        // KeyUp event
        val handledKeyUp =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionDown,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = false,
                events = events,
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertFalse(handledKeyUp)
        assertEquals("ev-1", selectedId)
    }

    @Test
    fun testEmptyList_ReturnsFalse() {
        var selectedId: String? = null
        val handled =
            EventsKeyboardNavigationHelper.handleNavigationKey(
                key = Key.DirectionDown,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                isKeyDown = true,
                events = emptyList(),
                selectedEventId = selectedId,
                isCompact = false,
                onSelectEvent = { selectedId = it },
            )
        assertFalse(handled)
    }
}
