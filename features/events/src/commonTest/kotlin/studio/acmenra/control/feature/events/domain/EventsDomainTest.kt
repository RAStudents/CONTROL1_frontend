package studio.acmenra.control.feature.events.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.domain.model.DeviceFilterOption
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventValidityFilter
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.EventsQueryRouteCodec
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
import studio.acmenra.control.feature.events.domain.model.LocationToggleState
import studio.acmenra.control.feature.events.domain.model.locationToggleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class EventsDomainTest {
    @Test
    fun testActiveFiltersCount() {
        val emptyFilter = EventsFilter()
        assertEquals(0, emptyFilter.activeFiltersCount)

        val withSearch = EventsFilter(searchId = " 123 ")
        assertEquals(1, withSearch.activeFiltersCount)

        val withBlankSearch = EventsFilter(searchId = "   ")
        assertEquals(0, withBlankSearch.activeFiltersCount)

        val withAll =
            EventsFilter(
                searchId = "abc",
                dateFrom = Instant.parse("2026-01-01T00:00:00Z"),
                dateTo = Instant.parse("2026-01-02T00:00:00Z"),
                eventTypeIds = setOf("no_helmet"),
                deviceIds = setOf("cam_1"),
                validity = EventValidityFilter.VALID,
            )
        assertEquals(5, withAll.activeFiltersCount)
    }

    @Test
    fun testLocationToggleState() {
        val location =
            LocationFilterGroup(
                id = "loc_1",
                name = "Workshop",
                devices =
                    listOf(
                        DeviceFilterOption("cam_1", "Camera 1"),
                        DeviceFilterOption("cam_2", "Camera 2"),
                    ),
            )

        // Off: no devices selected
        assertEquals(LocationToggleState.OFF, locationToggleState(location, emptySet()))
        assertEquals(LocationToggleState.OFF, locationToggleState(location, setOf("other_cam")))

        // Indeterminate: 1 of 2 selected
        assertEquals(LocationToggleState.INDETERMINATE, locationToggleState(location, setOf("cam_1")))

        // On: all devices selected
        assertEquals(LocationToggleState.ON, locationToggleState(location, setOf("cam_1", "cam_2", "other_cam")))
    }

    @Test
    fun testEventCanAcknowledge() {
        fun createEvent(status: EventAcknowledgementStatus): Event =
            Event(
                id = "ev-1",
                eventVersion = 1L,
                classificationVersion = 1L,
                acknowledgementVersion = 1L,
                eventTypeId = "type-1",
                eventTypeName = "No helmet",
                categoryCode = "safety",
                iconKey = EventIconKey.HELMET,
                locationId = "loc-1",
                locationName = "Zone 1",
                deviceId = "dev-1",
                deviceName = "Camera 1",
                occurredAt = Instant.parse("2026-01-01T12:00:00Z"),
                receivedAt = Instant.parse("2026-01-01T12:00:05Z"),
                status = status,
                acknowledgedAt = null,
                acknowledgedBy = null,
                isErroneous = false,
                previewUrl = "https://cdn.example.com/p.jpg",
                videoUrl = "https://cdn.example.com/v.mp4",
            )

        val fullAccess = EventsAccessCapabilities(eventsRead = true, eventsReview = true, reportsExport = false)
        val readOnlyAccess = EventsAccessCapabilities(eventsRead = true, eventsReview = false, reportsExport = false)

        val newEvent = createEvent(EventAcknowledgementStatus.NEW)
        assertTrue(newEvent.canAcknowledge(fullAccess))
        assertFalse(newEvent.canAcknowledge(readOnlyAccess))

        val legacyEvent = createEvent(EventAcknowledgementStatus.LEGACY_UNKNOWN)
        assertTrue(legacyEvent.canAcknowledge(fullAccess))
        assertFalse(legacyEvent.canAcknowledge(readOnlyAccess))

        val ackedEvent = createEvent(EventAcknowledgementStatus.ACKNOWLEDGED)
        assertFalse(ackedEvent.canAcknowledge(fullAccess))

        val unrecEvent = createEvent(EventAcknowledgementStatus.UNRECOGNIZED)
        assertFalse(unrecEvent.canAcknowledge(fullAccess))
    }

    @Test
    fun testEventVisualReadStatus() {
        val event =
            Event(
                id = "ev-42",
                eventVersion = 1L,
                classificationVersion = 1L,
                acknowledgementVersion = 1L,
                eventTypeId = "type-1",
                eventTypeName = "No helmet",
                categoryCode = "safety",
                iconKey = EventIconKey.HELMET,
                locationId = "loc-1",
                locationName = "Zone 1",
                deviceId = "dev-1",
                deviceName = "Camera 1",
                occurredAt = Instant.parse("2026-01-01T12:00:00Z"),
                receivedAt = Instant.parse("2026-01-01T12:00:05Z"),
                status = EventAcknowledgementStatus.NEW,
                acknowledgedAt = null,
                acknowledgedBy = null,
                isErroneous = false,
                previewUrl = "https://cdn.example.com/p.jpg",
                videoUrl = null,
            )

        assertFalse(event.isVisuallyRead(pendingAckIds = emptySet()))
        assertTrue(event.isVisuallyRead(pendingAckIds = setOf("ev-42")))

        val acked = event.copy(status = EventAcknowledgementStatus.ACKNOWLEDGED)
        assertTrue(acked.isVisuallyRead(emptySet()))

        val unrec = event.copy(status = EventAcknowledgementStatus.UNRECOGNIZED)
        assertTrue(unrec.isVisuallyRead(emptySet()))
    }

    @Test
    fun testDateFilterUtilsInBusinessTimezone() {
        val tz = TimeZone.of("UTC+3")
        val singleDay = DateSelection.Single(LocalDate(2026, 5, 10))

        val (from, to) = DateFilterUtils.dateSelectionToUtcBounds(singleDay, tz)
        assertEquals(Instant.parse("2026-05-09T21:00:00Z"), from) // UTC+3 in summer
        assertEquals(Instant.parse("2026-05-10T21:00:00Z"), to)

        val restored = DateFilterUtils.utcBoundsToDateSelection(from, to, tz)
        assertTrue(restored is DateSelection.Single)
        assertEquals(LocalDate(2026, 5, 10), restored.date)

        val range = DateSelection.Range(LocalDate(2026, 5, 10), LocalDate(2026, 5, 12))
        val (rangeFrom, rangeTo) = DateFilterUtils.dateSelectionToUtcBounds(range, tz)
        assertEquals(Instant.parse("2026-05-09T21:00:00Z"), rangeFrom)
        assertEquals(Instant.parse("2026-05-12T21:00:00Z"), rangeTo)

        val restoredRange = DateFilterUtils.utcBoundsToDateSelection(rangeFrom, rangeTo, tz)
        assertTrue(restoredRange is DateSelection.Range)
        assertEquals(LocalDate(2026, 5, 10), restoredRange.startDate)
        assertEquals(LocalDate(2026, 5, 12), restoredRange.endDate)
    }

    @Test
    fun testEventsQueryRouteCodecRoundTrip() {
        val query =
            EventsQuery(
                filter =
                    EventsFilter(
                        searchId = "674",
                        dateFrom = Instant.parse("2026-01-01T00:00:00Z"),
                        dateTo = Instant.parse("2026-01-02T00:00:00Z"),
                        eventTypeIds = setOf("b_type", "a_type"),
                        deviceIds = setOf("cam_02", "cam_01"),
                        validity = EventValidityFilter.ERRONEOUS,
                    ),
            )

        val encoded = EventsQueryRouteCodec.encodeHash("comp-1", query)
        assertTrue(encoded.startsWith("#events?companyId=comp-1"))
        assertTrue(encoded.contains("search_id=674"))
        assertTrue(encoded.contains("from=2026-01-01T00%3A00%3A00Z") || encoded.contains("from=2026-01-01T00:00:00Z"))
        assertTrue(encoded.contains("event_type_ids=a_type"))
        assertTrue(encoded.contains("event_type_ids=b_type"))
        assertTrue(encoded.contains("validity=ERRONEOUS"))

        val (decodedCompany, decodedQuery) = EventsQueryRouteCodec.decodeHash(encoded)
        assertEquals("comp-1", decodedCompany)
        assertEquals("674", decodedQuery.filter.searchId)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), decodedQuery.filter.dateFrom)
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), decodedQuery.filter.dateTo)
        assertEquals(setOf("a_type", "b_type"), decodedQuery.filter.eventTypeIds)
        assertEquals(setOf("cam_01", "cam_02"), decodedQuery.filter.deviceIds)
        assertEquals(EventValidityFilter.ERRONEOUS, decodedQuery.filter.validity)
    }

    @Test
    fun testEventsQueryRouteCodecSpecialCharactersAndNullCompany() {
        val query =
            EventsQuery(
                filter =
                    EventsFilter(
                        searchId = "Тест_%\\",
                    ),
            )

        val encoded = EventsQueryRouteCodec.encodeHash(null, query)
        assertTrue(encoded.contains("companyId=null"))

        val (decodedCompany, decodedQuery) = EventsQueryRouteCodec.decodeHash(encoded)
        assertNull(decodedCompany)
        assertEquals("Тест_%\\", decodedQuery.filter.searchId)
    }
}
