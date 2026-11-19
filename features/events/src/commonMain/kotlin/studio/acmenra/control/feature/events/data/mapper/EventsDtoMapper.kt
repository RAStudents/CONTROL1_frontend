package studio.acmenra.control.feature.events.data.mapper

import studio.acmenra.control.feature.events.data.model.CategoryScoreDto
import studio.acmenra.control.feature.events.data.model.CreateExportRequestDto
import studio.acmenra.control.feature.events.data.model.DevicesStatusSummaryDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventsBootstrapDto
import studio.acmenra.control.feature.events.data.model.EventsExportQueryDto
import studio.acmenra.control.feature.events.data.model.EventsExportStatusDto
import studio.acmenra.control.feature.events.data.model.EventsFilterOptionsDto
import studio.acmenra.control.feature.events.data.model.EventsPageDto
import studio.acmenra.control.feature.events.data.model.EventsSummaryDto
import studio.acmenra.control.feature.events.domain.model.CategoryScore
import studio.acmenra.control.feature.events.domain.model.DeviceFilterOption
import studio.acmenra.control.feature.events.domain.model.DevicesStatusSummary
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventTypeOption
import studio.acmenra.control.feature.events.domain.model.EventsBootstrap
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsPage
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
import studio.acmenra.control.feature.events.domain.model.ScoreTrend
import kotlin.time.Instant

object EventsDtoMapper {
    fun mapEvent(dto: EventDto): Event {
        val status =
            when (dto.status.uppercase()) {
                "NEW" -> EventAcknowledgementStatus.NEW
                "ACKNOWLEDGED" -> EventAcknowledgementStatus.ACKNOWLEDGED
                "LEGACY_UNKNOWN" -> EventAcknowledgementStatus.LEGACY_UNKNOWN
                else -> EventAcknowledgementStatus.UNRECOGNIZED
            }

        val iconKey =
            when (dto.iconKey?.uppercase()) {
                "HELMET" -> EventIconKey.HELMET
                "PHONE" -> EventIconKey.PHONE
                "SLEEP" -> EventIconKey.SLEEP
                "DEFECT" -> EventIconKey.DEFECT
                "PERSON" -> EventIconKey.PERSON
                else -> EventIconKey.GENERIC_ALERT
            }

        val occurredAt = runCatching { Instant.parse(dto.occurredAt) }.getOrDefault(Instant.fromEpochMilliseconds(0))
        val receivedAt = runCatching { Instant.parse(dto.receivedAt) }.getOrDefault(occurredAt)
        val acknowledgedAt = dto.acknowledgedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

        return Event(
            id = dto.id,
            eventVersion = dto.eventVersion,
            classificationVersion = dto.classificationVersion,
            acknowledgementVersion = dto.acknowledgementVersion,
            eventTypeId = dto.eventTypeId,
            eventTypeName = dto.eventTypeName,
            categoryCode = dto.categoryCode,
            iconKey = iconKey,
            locationId = dto.locationId,
            locationName = dto.locationName,
            deviceId = dto.deviceId,
            deviceName = dto.deviceName,
            occurredAt = occurredAt,
            receivedAt = receivedAt,
            status = status,
            acknowledgedAt = acknowledgedAt,
            acknowledgedBy = dto.acknowledgedBy,
            isErroneous = dto.isErroneous,
            previewUrl = dto.previewUrl,
            videoUrl = dto.videoUrl,
        )
    }

    fun mapPage(dto: EventsPageDto): EventsPage =
        EventsPage(
            items = dto.items.map(::mapEvent),
            nextCursor = dto.nextCursor,
            hasMore = dto.hasMore,
            totalCount = dto.totalCount,
            asOfSequence = dto.asOfSequence,
        )

    fun mapCategoryScore(dto: CategoryScoreDto): CategoryScore {
        val trend =
            when (dto.trend.uppercase()) {
                "IMPROVING" -> ScoreTrend.IMPROVING
                "WORSENING" -> ScoreTrend.WORSENING
                "STABLE" -> ScoreTrend.STABLE
                else -> ScoreTrend.UNKNOWN
            }
        return CategoryScore(
            categoryId = dto.categoryId,
            name = dto.name,
            score = dto.score,
            trend = trend,
        )
    }

    fun mapDevicesStatus(dto: DevicesStatusSummaryDto): DevicesStatusSummary =
        DevicesStatusSummary(
            totalCount = dto.totalCount,
            onlineOkCount = dto.onlineOkCount,
            unknownCount = dto.unknownCount,
            errorCount = dto.errorCount,
        )

    fun mapSummary(dto: EventsSummaryDto): EventsSummary =
        EventsSummary(
            businessTimezone = dto.businessTimezone,
            businessDate = dto.businessDate,
            summaryAsOfSequence = dto.summaryAsOfSequence,
            todayEventsCount = dto.todayEventsCount,
            categories = dto.categories.map(::mapCategoryScore),
            devices = mapDevicesStatus(dto.devices),
        )

    fun mapBootstrap(dto: EventsBootstrapDto): EventsBootstrap =
        EventsBootstrap(
            page = mapPage(dto.events),
            summary = mapSummary(dto.summary),
            streamCursor = dto.streamCursor,
        )

    fun mapFilterOptions(dto: EventsFilterOptionsDto): EventsFilterOptions =
        EventsFilterOptions(
            revision = dto.revision,
            eventTypes =
                dto.eventTypes.map { item ->
                    val iconKey =
                        when (item.iconKey?.uppercase()) {
                            "HELMET" -> EventIconKey.HELMET
                            "PHONE" -> EventIconKey.PHONE
                            "SLEEP" -> EventIconKey.SLEEP
                            "DEFECT" -> EventIconKey.DEFECT
                            "PERSON" -> EventIconKey.PERSON
                            else -> EventIconKey.GENERIC_ALERT
                        }
                    EventTypeOption(id = item.id, name = item.name, iconKey = iconKey)
                },
            locations =
                dto.locations.map { loc ->
                    LocationFilterGroup(
                        id = loc.id,
                        name = loc.name,
                        devices = loc.devices.map { dev -> DeviceFilterOption(id = dev.id, name = dev.name) },
                    )
                },
        )

    fun mapExportStatus(dto: EventsExportStatusDto): EventsExportStatus {
        val status =
            when (dto.status.uppercase()) {
                "QUEUED" -> EventsExportStatusCode.QUEUED
                "PROCESSING" -> EventsExportStatusCode.PROCESSING
                "COMPLETED" -> EventsExportStatusCode.COMPLETED
                "FAILED" -> EventsExportStatusCode.FAILED
                "EXPIRED" -> EventsExportStatusCode.EXPIRED
                else -> EventsExportStatusCode.FAILED
            }
        val createdAt = runCatching { Instant.parse(dto.createdAt) }.getOrDefault(Instant.fromEpochMilliseconds(0))
        val expiresAt = dto.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

        return EventsExportStatus(
            exportId = dto.exportId,
            status = status,
            progressPercent = dto.progressPercent,
            processedRecords = dto.processedRecords,
            totalRecords = dto.totalRecords,
            createdAt = createdAt,
            expiresAt = expiresAt,
            failureCode = dto.failureCode,
        )
    }

    fun mapExportRequestToDto(request: EventsExportRequest): CreateExportRequestDto {
        val formatPreset =
            when (request.formatPreset) {
                EventsExportFormatPreset.EXCEL_SEMICOLON_BOM -> "EXCEL_SEMICOLON_BOM"
                EventsExportFormatPreset.STANDARD_COMMA_UTF8 -> "STANDARD_COMMA_UTF8"
            }
        val query = request.query
        return CreateExportRequestDto(
            formatPreset = formatPreset,
            includeMediaLinks = request.includeMediaLinks,
            query =
                EventsExportQueryDto(
                    searchId =
                        query.filter.searchId
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                    from = query.filter.dateFrom?.toString(),
                    to = query.filter.dateTo?.toString(),
                    eventTypeIds = query.filter.eventTypeIds.sorted(),
                    deviceIds = query.filter.deviceIds.sorted(),
                    validity = query.filter.validity.name,
                    sort = query.sort.name,
                ),
        )
    }
}
