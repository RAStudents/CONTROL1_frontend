package studio.acmenra.control.feature.events.data.mapper

import studio.acmenra.control.feature.events.data.model.CategoryScoreDto
import studio.acmenra.control.feature.events.data.model.CreateExportRequestDto
import studio.acmenra.control.feature.events.data.model.DevicesStatusSummaryDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventsBootstrapDto
import studio.acmenra.control.feature.events.data.model.EventsExportFiltersDto
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

        val occurredAt =
            try {
                Instant.parse(dto.occurredAt)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Invalid occurred_at timestamp '${dto.occurredAt}' for event ${dto.id}",
                    e,
                )
            }
        val receivedAt =
            try {
                Instant.parse(dto.receivedAt)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Invalid received_at timestamp '${dto.receivedAt}' for event ${dto.id}",
                    e,
                )
            }
        val acknowledgedAt =
            dto.acknowledgedAt?.let { raw ->
                try {
                    Instant.parse(raw)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid acknowledged_at timestamp '$raw' for event ${dto.id}", e)
                }
            }

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
            snapshotId = dto.snapshotId ?: "",
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

    fun mapBootstrap(dto: EventsBootstrapDto): EventsBootstrap {
        val pagination = dto.events.pagination
        return EventsBootstrap(
            page =
                EventsPage(
                    items = dto.events.items.map(::mapEvent),
                    nextCursor = pagination.nextCursor,
                    hasMore = pagination.hasMore,
                    totalCount = pagination.totalCount,
                    asOfSequence = pagination.asOfSequence,
                    snapshotId = pagination.snapshotId,
                ),
            summary = mapSummary(dto.summary),
            streamCursor = dto.streamCursor,
        )
    }

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
                else -> EventsExportStatusCode.UNKNOWN
            }
        val createdAt =
            try {
                Instant.parse(dto.createdAt)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Invalid created_at timestamp '${dto.createdAt}' for export ${dto.exportId}",
                    e,
                )
            }
        val updatedAt =
            dto.updatedAt?.let { raw ->
                try {
                    Instant.parse(raw)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid updated_at timestamp '$raw' for export ${dto.exportId}", e)
                }
            }
        val finishedAt =
            dto.finishedAt?.let { raw ->
                try {
                    Instant.parse(raw)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid finished_at timestamp '$raw' for export ${dto.exportId}", e)
                }
            }
        val expiresAt =
            dto.expiresAt?.let { raw ->
                try {
                    Instant.parse(raw)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid expires_at timestamp '$raw' for export ${dto.exportId}", e)
                }
            }

        val calculatedProgress =
            dto.progressPercent
                ?: if (dto.totalRecords != null && dto.totalRecords > 0L && dto.processedRecords != null) {
                    ((dto.processedRecords.toDouble() / dto.totalRecords.toDouble()) * 100).toInt().coerceIn(0, 100)
                } else {
                    0
                }

        return EventsExportStatus(
            exportId = dto.exportId,
            status = status,
            progressPercent = calculatedProgress,
            processedRecords = dto.processedRecords,
            totalRecords = dto.totalRecords,
            createdAt = createdAt,
            updatedAt = updatedAt,
            finishedAt = finishedAt,
            expiresAt = expiresAt,
            failureCode = dto.failureCode,
            downloadUrl = dto.downloadUrl,
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
            filters =
                EventsExportFiltersDto(
                    searchId =
                        query.filter.searchId
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                    from = query.filter.dateFrom?.toString(),
                    to = query.filter.dateTo?.toString(),
                    eventTypeIds = query.filter.eventTypeIds.sorted(),
                    deviceIds = query.filter.deviceIds.sorted(),
                    validity = query.filter.validity.name,
                ),
        )
    }
}
