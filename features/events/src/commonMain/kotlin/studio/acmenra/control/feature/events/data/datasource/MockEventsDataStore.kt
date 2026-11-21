package studio.acmenra.control.feature.events.data.datasource

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.feature.events.data.model.BootstrapEventsDto
import studio.acmenra.control.feature.events.data.model.CategoryScoreDto
import studio.acmenra.control.feature.events.data.model.CreateExportRequestDto
import studio.acmenra.control.feature.events.data.model.DeviceFilterOptionDto
import studio.acmenra.control.feature.events.data.model.DevicesStatusSummaryDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventTypeOptionDto
import studio.acmenra.control.feature.events.data.model.EventsBootstrapDto
import studio.acmenra.control.feature.events.data.model.EventsExportStatusDto
import studio.acmenra.control.feature.events.data.model.EventsFilterOptionsDto
import studio.acmenra.control.feature.events.data.model.EventsPageDto
import studio.acmenra.control.feature.events.data.model.EventsPaginationDto
import studio.acmenra.control.feature.events.data.model.EventsSummaryDto
import studio.acmenra.control.feature.events.data.model.LocationFilterGroupDto
import studio.acmenra.control.feature.events.data.model.TenantCommitFrameDto
import studio.acmenra.control.feature.events.domain.model.EventValidityFilter
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

const val MOCK_VIDEO_URL =
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4"
const val MOCK_PREVIEW_URL =
    "https://24ai.tech/wp-content/uploads/sites/4/2023/10/01_clothes_1_foto-walmart-2-scaled.jpg"

class MockEventsDataStore {
    private val mutex = Mutex()
    private var currentSequence = 100L
    private var todayEventsCount = 20
    private var generatedCount = 0
    private var exportIdCounter = 0
    private val exportRequests = mutableMapOf<String, CreateExportRequestDto>()

    val mockEventTypes =
        listOf(
            EventTypeOptionDto(id = "type_helmet", name = "Отсутствие каски", iconKey = "HELMET"),
            EventTypeOptionDto(id = "type_phone", name = "Использование телефона", iconKey = "PHONE"),
            EventTypeOptionDto(id = "type_sleep", name = "Сон на рабочем месте", iconKey = "SLEEP"),
            EventTypeOptionDto(id = "type_defect", name = "Обнаружен дефект", iconKey = "DEFECT"),
            EventTypeOptionDto(id = "type_person", name = "Человек в опасной зоне", iconKey = "PERSON"),
            EventTypeOptionDto(id = "type_alert", name = "Экстренная тревога", iconKey = "GENERIC_ALERT"),
        )

    val mockLocations =
        listOf(
            LocationFilterGroupDto(
                id = "loc_workshop_1",
                name = "Цех №1 (Механический)",
                devices =
                    listOf(
                        DeviceFilterOptionDto(id = "dev_cam_01", name = "Камера 01 — Станок ЧПУ"),
                        DeviceFilterOptionDto(id = "dev_cam_02", name = "Камера 02 — Зона погрузки"),
                        DeviceFilterOptionDto(id = "dev_cam_03", name = "Камера 03 — Главный проход"),
                    ),
            ),
            LocationFilterGroupDto(
                id = "loc_warehouse",
                name = "Центральный склад",
                devices =
                    listOf(
                        DeviceFilterOptionDto(id = "dev_cam_04", name = "Камера 04 — Ворота приемки"),
                        DeviceFilterOptionDto(id = "dev_cam_05", name = "Камера 05 — Стеллажи A-D"),
                    ),
            ),
            LocationFilterGroupDto(
                id = "loc_assembly",
                name = "Участок сборки",
                devices =
                    listOf(
                        DeviceFilterOptionDto(id = "dev_cam_06", name = "Камера 06 — Линия сборки 1"),
                        DeviceFilterOptionDto(id = "dev_cam_07", name = "Камера 07 — Выходной контроль"),
                    ),
            ),
        )

    val mockCategories =
        listOf(
            CategoryScoreDto(categoryId = "SAFETY", name = "Охрана труда", score = 94, trend = "IMPROVING"),
            CategoryScoreDto(categoryId = "QUALITY", name = "Контроль качества", score = 88, trend = "STABLE"),
            CategoryScoreDto(categoryId = "DISCIPLINE", name = "Трудовая дисциплина", score = 76, trend = "WORSENING"),
        )

    val mockDevicesSummary =
        DevicesStatusSummaryDto(
            totalCount = 7,
            onlineOkCount = 5,
            unknownCount = 1,
            errorCount = 1,
        )

    private val allDevicesWithLocations =
        mockLocations.flatMap { loc ->
            loc.devices.map { dev -> loc to dev }
        }

    private val events = mutableListOf<EventDto>()

    init {
        val now = Clock.System.now()
        for (i in 1..20) {
            val type = mockEventTypes[(i - 1) % mockEventTypes.size]
            val (loc, dev) = allDevicesWithLocations[(i - 1) % allDevicesWithLocations.size]
            val occurredAt = now - (i * 4).minutes
            val receivedAt = occurredAt + 5.seconds

            val isAck = (i == 3 || i == 7 || i == 12)
            val isErr = (i == 5 || i == 14)

            val categoryCode =
                when (type.id) {
                    "type_helmet", "type_person" -> "SAFETY"
                    "type_defect" -> "QUALITY"
                    "type_phone", "type_sleep" -> "DISCIPLINE"
                    else -> "CRITICAL"
                }

            events.add(
                EventDto(
                    id = "ev_mock_$i",
                    eventVersion = 1L,
                    classificationVersion = 1L,
                    acknowledgementVersion = 1L,
                    eventTypeId = type.id,
                    eventTypeName = type.name,
                    categoryCode = categoryCode,
                    iconKey = type.iconKey,
                    locationId = loc.id,
                    locationName = loc.name,
                    deviceId = dev.id,
                    deviceName = dev.name,
                    occurredAt = occurredAt.toString(),
                    receivedAt = receivedAt.toString(),
                    status = if (isAck) "ACKNOWLEDGED" else "NEW",
                    acknowledgedAt = if (isAck) (occurredAt + 2.minutes).toString() else null,
                    acknowledgedBy = if (isAck) "Оператор Иванов" else null,
                    isErroneous = isErr,
                    erroneousMarkedAt = if (isErr) (occurredAt + 3.minutes).toString() else null,
                    erroneousMarkedBy = if (isErr) "Контролер Смирнов" else null,
                    previewUrl = MOCK_PREVIEW_URL,
                    videoUrl = MOCK_VIDEO_URL,
                ),
            )
        }
    }

    private fun getSummaryDtoLocked(seq: Long): EventsSummaryDto =
        EventsSummaryDto(
            businessTimezone = "Europe/Moscow",
            businessDate = "2026-11-21",
            summaryAsOfSequence = seq,
            todayEventsCount = todayEventsCount,
            categories = mockCategories,
            devices = mockDevicesSummary,
        )

    private fun filterEventsLocked(
        list: List<EventDto>,
        query: EventsQuery,
    ): List<EventDto> {
        val filter = query.filter
        return list
            .filter { event ->
                filter.searchId?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
                    if (s.length < 3) {
                        if (!event.id.equals(s, ignoreCase = true)) return@filter false
                    } else {
                        if (!event.id.startsWith(s, ignoreCase = true)) return@filter false
                    }
                }

                val occurredInstant = runCatching { Instant.parse(event.occurredAt) }.getOrNull()
                if (occurredInstant != null) {
                    filter.dateFrom?.let { if (occurredInstant < it) return@filter false }
                    filter.dateTo?.let { if (occurredInstant >= it) return@filter false }
                }

                if (filter.eventTypeIds.isNotEmpty() && event.eventTypeId !in filter.eventTypeIds) {
                    return@filter false
                }

                if (filter.deviceIds.isNotEmpty() && event.deviceId !in filter.deviceIds) {
                    return@filter false
                }

                when (filter.validity) {
                    EventValidityFilter.ALL -> {}
                    EventValidityFilter.VALID -> if (event.isErroneous) return@filter false
                    EventValidityFilter.ERRONEOUS -> if (!event.isErroneous) return@filter false
                }

                true
            }.sortedByDescending { Instant.parse(it.occurredAt) }
    }

    suspend fun getBootstrap(
        companyId: String,
        query: EventsQuery,
        limit: Int = 50,
    ): EventsBootstrapDto =
        mutex.withLock {
            val filtered = filterEventsLocked(events, query)
            val paged = filtered.take(limit)
            EventsBootstrapDto(
                streamCursor = currentSequence,
                events =
                    BootstrapEventsDto(
                        items = paged,
                        pagination =
                            EventsPaginationDto(
                                snapshotId = "mock_snap_$currentSequence",
                                asOfSequence = currentSequence,
                                nextCursor = if (filtered.size > limit) "mock_cur_$limit" else null,
                                hasMore = filtered.size > limit,
                                totalCount = filtered.size,
                            ),
                    ),
                summary = getSummaryDtoLocked(currentSequence),
            )
        }

    suspend fun getEventsPage(
        companyId: String,
        cursor: String,
        limit: Int = 50,
    ): EventsPageDto =
        mutex.withLock {
            val offset = cursor.removePrefix("mock_cur_").toIntOrNull() ?: limit
            val filtered = filterEventsLocked(events, EventsQuery())
            val paged = filtered.drop(offset).take(limit)
            val nextOffset = offset + paged.size
            val hasMore = filtered.size > nextOffset
            EventsPageDto(
                items = paged,
                nextCursor = if (hasMore) "mock_cur_$nextOffset" else null,
                hasMore = hasMore,
                totalCount = filtered.size,
                asOfSequence = currentSequence,
                snapshotId = "mock_snap_$currentSequence",
            )
        }

    fun getFilterOptions(
        companyId: String,
        ifNoneMatchEtag: String? = null,
    ): FilterOptionsFetchResult =
        FilterOptionsFetchResult.Success(
            options =
                EventsFilterOptionsDto(
                    revision = "v1",
                    eventTypes = mockEventTypes,
                    locations = mockLocations,
                ),
            etag = "\"mock-filter-v1\"",
        )

    suspend fun acknowledgeEvent(
        companyId: String,
        eventId: String,
    ): EventDto =
        mutex.withLock {
            val index = events.indexOfFirst { it.id == eventId }
            val now = Clock.System.now().toString()
            val updated =
                if (index >= 0) {
                    val cur = events[index]
                    val up =
                        cur.copy(
                            status = "ACKNOWLEDGED",
                            acknowledgedAt = now,
                            acknowledgedBy = "Мок-оператор",
                            eventVersion = cur.eventVersion + 1,
                            acknowledgementVersion = cur.acknowledgementVersion + 1,
                        )
                    events[index] = up
                    up
                } else {
                    EventDto(
                        id = eventId,
                        eventVersion = 2L,
                        classificationVersion = 1L,
                        acknowledgementVersion = 2L,
                        eventTypeId = mockEventTypes.first().id,
                        eventTypeName = mockEventTypes.first().name,
                        categoryCode = "SAFETY",
                        iconKey = "HELMET",
                        locationId = mockLocations.first().id,
                        locationName = mockLocations.first().name,
                        deviceId =
                            mockLocations
                                .first()
                                .devices
                                .first()
                                .id,
                        deviceName =
                            mockLocations
                                .first()
                                .devices
                                .first()
                                .name,
                        occurredAt = now,
                        receivedAt = now,
                        status = "ACKNOWLEDGED",
                        acknowledgedAt = now,
                        acknowledgedBy = "Мок-оператор",
                        isErroneous = false,
                        previewUrl = MOCK_PREVIEW_URL,
                        videoUrl = MOCK_VIDEO_URL,
                    )
                }
            updated
        }

    suspend fun markEventErroneous(
        companyId: String,
        eventId: String,
        expectedClassificationVersion: Long,
        idempotencyKey: String,
    ): EventDto =
        mutex.withLock {
            val index = events.indexOfFirst { it.id == eventId }
            val now = Clock.System.now().toString()
            val updated =
                if (index >= 0) {
                    val cur = events[index]
                    val up =
                        cur.copy(
                            isErroneous = true,
                            erroneousMarkedAt = now,
                            erroneousMarkedBy = "Мок-оператор",
                            eventVersion = cur.eventVersion + 1,
                            classificationVersion = cur.classificationVersion + 1,
                        )
                    events[index] = up
                    up
                } else {
                    EventDto(
                        id = eventId,
                        eventVersion = 2L,
                        classificationVersion = 2L,
                        acknowledgementVersion = 1L,
                        eventTypeId = mockEventTypes.first().id,
                        eventTypeName = mockEventTypes.first().name,
                        categoryCode = "SAFETY",
                        iconKey = "HELMET",
                        locationId = mockLocations.first().id,
                        locationName = mockLocations.first().name,
                        deviceId =
                            mockLocations
                                .first()
                                .devices
                                .first()
                                .id,
                        deviceName =
                            mockLocations
                                .first()
                                .devices
                                .first()
                                .name,
                        occurredAt = now,
                        receivedAt = now,
                        status = "NEW",
                        isErroneous = true,
                        erroneousMarkedAt = now,
                        erroneousMarkedBy = "Мок-оператор",
                        previewUrl = MOCK_PREVIEW_URL,
                        videoUrl = MOCK_VIDEO_URL,
                    )
                }
            updated
        }

    fun createExport(
        companyId: String,
        request: CreateExportRequestDto,
        idempotencyKey: String,
    ): EventsExportStatusDto {
        val exportId = "mock-export-${++exportIdCounter}"
        exportRequests[exportId] = request
        val now = Clock.System.now()
        val expiresAt = now + 1.hours
        val query =
            EventsQuery(
                filter =
                    studio.acmenra.control.feature.events.domain.model.EventsFilter(
                        searchId = request.filters.searchId,
                        dateFrom = request.filters.from?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        dateTo = request.filters.to?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        eventTypeIds = request.filters.eventTypeIds.toSet(),
                        deviceIds = request.filters.deviceIds.toSet(),
                        validity =
                            when (request.filters.validity.uppercase()) {
                                "VALID" -> EventValidityFilter.VALID
                                "ERRONEOUS" -> EventValidityFilter.ERRONEOUS
                                else -> EventValidityFilter.ALL
                            },
                    ),
            )
        val matchingEvents = filterEventsLocked(events, query)
        return EventsExportStatusDto(
            exportId = exportId,
            status = "COMPLETED",
            createdAt = now.toString(),
            finishedAt = now.toString(),
            expiresAt = expiresAt.toString(),
            processedRecords = matchingEvents.size.toLong(),
            totalRecords = matchingEvents.size.toLong(),
            progressPercent = 100,
            downloadUrl = "https://example.com/$exportId.csv",
        )
    }

    fun getExportStatus(
        companyId: String,
        exportId: String,
    ): EventsExportStatusDto {
        val now = Clock.System.now()
        val expiresAt = now + 1.hours
        val req = exportRequests[exportId]
        val total =
            if (req != null) {
                val query =
                    EventsQuery(
                        filter =
                            studio.acmenra.control.feature.events.domain.model.EventsFilter(
                                searchId = req.filters.searchId,
                                dateFrom = req.filters.from?.let { runCatching { Instant.parse(it) }.getOrNull() },
                                dateTo = req.filters.to?.let { runCatching { Instant.parse(it) }.getOrNull() },
                                eventTypeIds = req.filters.eventTypeIds.toSet(),
                                deviceIds = req.filters.deviceIds.toSet(),
                                validity =
                                    when (req.filters.validity.uppercase()) {
                                        "VALID" -> EventValidityFilter.VALID
                                        "ERRONEOUS" -> EventValidityFilter.ERRONEOUS
                                        else -> EventValidityFilter.ALL
                                    },
                            ),
                    )
                filterEventsLocked(events, query).size.toLong()
            } else {
                events.size.toLong()
            }
        return EventsExportStatusDto(
            exportId = exportId,
            status = "COMPLETED",
            createdAt = now.toString(),
            finishedAt = now.toString(),
            expiresAt = expiresAt.toString(),
            processedRecords = total,
            totalRecords = total,
            progressPercent = 100,
            downloadUrl = "https://example.com/$exportId.csv",
        )
    }

    fun downloadExport(
        companyId: String,
        exportId: String,
    ): EventsExportDownload {
        val req = exportRequests[exportId]
        val includeMedia = req?.includeMediaLinks ?: false
        val isSemicolon = req?.formatPreset != "STANDARD_COMMA_UTF8"
        val sep = if (isSemicolon) ";" else ","
        val query =
            if (req != null) {
                EventsQuery(
                    filter =
                        studio.acmenra.control.feature.events.domain.model.EventsFilter(
                            searchId = req.filters.searchId,
                            dateFrom = req.filters.from?.let { runCatching { Instant.parse(it) }.getOrNull() },
                            dateTo = req.filters.to?.let { runCatching { Instant.parse(it) }.getOrNull() },
                            eventTypeIds = req.filters.eventTypeIds.toSet(),
                            deviceIds = req.filters.deviceIds.toSet(),
                            validity =
                                when (req.filters.validity.uppercase()) {
                                    "VALID" -> EventValidityFilter.VALID
                                    "ERRONEOUS" -> EventValidityFilter.ERRONEOUS
                                    else -> EventValidityFilter.ALL
                                },
                        ),
                )
            } else {
                EventsQuery()
            }
        val targetEvents = filterEventsLocked(events, query)

        val bytes =
            buildString {
                if (isSemicolon) {
                    append("\uFEFF")
                }
                if (includeMedia) {
                    appendLine(
                        "ID${sep}Type${sep}Location${sep}Device${sep}OccurredAt${sep}Status${sep}IsErroneous${sep}PreviewUrl${sep}VideoUrl",
                    )
                    for (e in targetEvents) {
                        appendLine(
                            "${e.id}${sep}${e.eventTypeName}${sep}${e.locationName}${sep}${e.deviceName}${sep}${e.occurredAt}${sep}${e.status}${sep}${e.isErroneous}${sep}${e.previewUrl.orEmpty()}${sep}${e.videoUrl.orEmpty()}",
                        )
                    }
                } else {
                    appendLine("ID${sep}Type${sep}Location${sep}Device${sep}OccurredAt${sep}Status${sep}IsErroneous")
                    for (e in targetEvents) {
                        appendLine(
                            "${e.id}${sep}${e.eventTypeName}${sep}${e.locationName}${sep}${e.deviceName}${sep}${e.occurredAt}${sep}${e.status}${sep}${e.isErroneous}",
                        )
                    }
                }
            }.encodeToByteArray()
        val handle = "mock-export-$exportId"
        ExportPayloadRegistry.register(
            handle = handle,
            payload =
                ExportPayload(
                    bytes = bytes,
                    contentType = if (isSemicolon) "text/csv; charset=utf-8" else "text/csv",
                ),
        )
        return EventsExportDownload(
            fileName = "events_export.csv",
            contentLength = bytes.size.toLong(),
            downloadHandle = handle,
        )
    }

    suspend fun generateAndAddRealtimeCommit(
        replayId: Long,
        insertAtBeginning: Boolean,
    ): TenantCommitFrameDto =
        mutex.withLock {
            currentSequence++
            generatedCount++
            todayEventsCount++

            val typeIndex = (generatedCount - 1) % mockEventTypes.size
            val type = mockEventTypes[typeIndex]

            val (loc, dev) = allDevicesWithLocations[(generatedCount - 1) % allDevicesWithLocations.size]

            val now = Clock.System.now()
            val occurredAt =
                if (insertAtBeginning || events.isEmpty()) {
                    now + (generatedCount * 2).seconds
                } else {
                    val sorted = events.sortedByDescending { Instant.parse(it.occurredAt) }
                    val newestTime = Instant.parse(sorted.first().occurredAt).toEpochMilliseconds()
                    val oldestTime = Instant.parse(sorted.last().occurredAt).toEpochMilliseconds()
                    val midTime = (newestTime + oldestTime) / 2 + ((generatedCount % 5) * 1000L)
                    Instant.fromEpochMilliseconds(midTime)
                }

            val categoryCode =
                when (type.id) {
                    "type_helmet", "type_person" -> "SAFETY"
                    "type_defect" -> "QUALITY"
                    "type_phone", "type_sleep" -> "DISCIPLINE"
                    else -> "CRITICAL"
                }

            val eventDto =
                EventDto(
                    id = "ev_rt_${currentSequence}_$generatedCount",
                    eventVersion = 1L,
                    classificationVersion = 1L,
                    acknowledgementVersion = 1L,
                    eventTypeId = type.id,
                    eventTypeName = type.name,
                    categoryCode = categoryCode,
                    iconKey = type.iconKey,
                    locationId = loc.id,
                    locationName = loc.name,
                    deviceId = dev.id,
                    deviceName = dev.name,
                    occurredAt = occurredAt.toString(),
                    receivedAt = now.toString(),
                    status = "NEW",
                    acknowledgedAt = null,
                    acknowledgedBy = null,
                    isErroneous = false,
                    erroneousMarkedAt = null,
                    erroneousMarkedBy = null,
                    previewUrl = MOCK_PREVIEW_URL,
                    videoUrl = MOCK_VIDEO_URL,
                )

            events.add(eventDto)

            TenantCommitFrameDto(
                schemaVersion = 1,
                type = "TENANT_COMMIT",
                replayId = replayId,
                tenantSequence = currentSequence,
                payloadSha256 = currentSequence.toString(16).padStart(64, '0'),
                eventCreated = eventDto,
                summaryUpdated = getSummaryDtoLocked(currentSequence),
            )
        }
}
