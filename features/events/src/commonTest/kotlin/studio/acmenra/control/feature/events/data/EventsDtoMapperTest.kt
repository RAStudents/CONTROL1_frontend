package studio.acmenra.control.feature.events.data

import studio.acmenra.control.feature.events.data.mapper.EventsDtoMapper
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventsExportStatusDto
import studio.acmenra.control.feature.events.data.model.EventsFilterOptionsDto
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventsDtoMapperTest {
    @Test
    fun testEventDtoMappingKnownAndUnknownEnums() {
        val dto =
            EventDto(
                id = "ev-100",
                eventVersion = 2L,
                classificationVersion = 1L,
                acknowledgementVersion = 1L,
                eventTypeId = "type-helmet",
                eventTypeName = "No Helmet",
                categoryCode = "SAFETY",
                iconKey = "helmet",
                locationId = "loc-1",
                locationName = "Main Hall",
                deviceId = "dev-1",
                deviceName = "Cam 1",
                occurredAt = "2026-03-01T10:00:00Z",
                receivedAt = "2026-03-01T10:00:02Z",
                status = "NEW",
                isErroneous = false,
                previewUrl = "https://cdn.example.com/p.jpg",
                videoUrl = "https://cdn.example.com/v.mp4",
            )

        val domain = EventsDtoMapper.mapEvent(dto)
        assertEquals("ev-100", domain.id)
        assertEquals(EventIconKey.HELMET, domain.iconKey)
        assertEquals(EventAcknowledgementStatus.NEW, domain.status)
        assertFalse(domain.isErroneous)
        assertEquals("https://cdn.example.com/p.jpg", domain.previewUrl)
        assertEquals("https://cdn.example.com/v.mp4", domain.videoUrl)

        // Unknown enum fallback
        val unknownDto = dto.copy(status = "UNKNOWN_STATUS_CODE", iconKey = "unseen_icon")
        val unknownDomain = EventsDtoMapper.mapEvent(unknownDto)
        assertEquals(EventAcknowledgementStatus.UNRECOGNIZED, unknownDomain.status)
        assertEquals(EventIconKey.GENERIC_ALERT, unknownDomain.iconKey)

        // Legacy unknown status mapping
        val legacyDto = dto.copy(status = "LEGACY_UNKNOWN")
        val legacyDomain = EventsDtoMapper.mapEvent(legacyDto)
        assertEquals(EventAcknowledgementStatus.LEGACY_UNKNOWN, legacyDomain.status)
    }

    @Test
    fun testEventsExportStatusMapping() {
        val dto =
            EventsExportStatusDto(
                exportId = "exp-1",
                status = "PROCESSING",
                progressPercent = 45,
                processedRecords = 450,
                totalRecords = 1000,
                createdAt = "2026-03-01T10:00:00Z",
                expiresAt = null,
                failureCode = null,
            )

        val domain = EventsDtoMapper.mapExportStatus(dto)
        assertEquals("exp-1", domain.exportId)
        assertEquals(EventsExportStatusCode.PROCESSING, domain.status)
        assertEquals(45, domain.progressPercent)
        assertEquals(450, domain.processedRecords)
        assertEquals(1000, domain.totalRecords)
        assertNull(domain.expiresAt)
        assertNull(domain.failureCode)
    }

    @Test
    fun testEmptyFilterOptionsMapping() {
        val dto =
            EventsFilterOptionsDto(
                revision = "rev-1",
                eventTypes = emptyList(),
                locations = emptyList(),
            )

        val domain = EventsDtoMapper.mapFilterOptions(dto)
        assertEquals("rev-1", domain.revision)
        assertTrue(domain.eventTypes.isEmpty())
        assertTrue(domain.locations.isEmpty())
    }
}
