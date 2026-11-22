package studio.acmenra.control.feature.events.data.datasource

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
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
import studio.acmenra.control.feature.events.domain.model.EventsDomainException
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
    private var exportIdCounter = 0

    private data class MockExportRecord(
        val exportId: String,
        val companyId: String,
        val idempotencyKey: String,
        val request: CreateExportRequestDto,
        val createdAt: Instant,
        val finishedAt: Instant,
        val expiresAt: Instant,
        val processedRecords: Long,
        val totalRecords: Long,
        val downloadUrl: String,
    )

    private val exportsById = mutableMapOf<String, MockExportRecord>()
    private val exportIdsByIdempotencyKey = mutableMapOf<Pair<String, String>, String>()

    private data class MutationKey(
        val companyId: String,
        val eventId: String,
        val idempotencyKey: String,
    )

    private data class MutationRecord(
        val expectedClassificationVersion: Long,
        val result: EventDto,
    )

    private val mutationsByKey = mutableMapOf<MutationKey, MutationRecord>()

    private data class CompanyMockState(
        val events: MutableList<EventDto>,
        val commits: MutableList<TenantCommitFrameDto> = mutableListOf(),
        var currentSequence: Long = 100L,
        var todayEventsCount: Int = 20,
        var generatedCount: Int = 0,
    )

    private val companyStates = mutableMapOf<String, CompanyMockState>()

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

    private fun createInitialEvents(companyId: String): MutableList<EventDto> {
        val list = mutableListOf<EventDto>()
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

            list.add(
                EventDto(
                    id = "ev_mock_${companyId}_$i",
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
        return list
    }

    private fun getOrCreateCompanyStateLocked(companyId: String): CompanyMockState =
        companyStates.getOrPut(companyId) {
            CompanyMockState(events = createInitialEvents(companyId))
        }

    private fun getSummaryDtoLocked(state: CompanyMockState): EventsSummaryDto {
        val tz = TimeZone.of("Europe/Moscow")
        val now = Clock.System.now()
        val localNow = now.toLocalDateTime(tz)
        val businessDate = localNow.date.toString()
        val startOfDay = LocalDateTime(localNow.date, LocalTime(0, 0, 0)).toInstant(tz)
        val endOfDay = LocalDateTime(localNow.date, LocalTime(23, 59, 59, 999_999_999)).toInstant(tz)
        val todayCount =
            state.events.count {
                val inst = runCatching { Instant.parse(it.occurredAt) }.getOrNull()
                inst != null && inst in startOfDay..endOfDay
            }
        return EventsSummaryDto(
            businessTimezone = "Europe/Moscow",
            businessDate = businessDate,
            summaryAsOfSequence = state.currentSequence,
            todayEventsCount = todayCount,
            categories = mockCategories,
            devices = mockDevicesSummary,
        )
    }

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
            val state = getOrCreateCompanyStateLocked(companyId)
            val filtered = filterEventsLocked(state.events, query)
            val paged = filtered.take(limit)
            EventsBootstrapDto(
                streamCursor = state.currentSequence,
                events =
                    BootstrapEventsDto(
                        items = paged,
                        pagination =
                            EventsPaginationDto(
                                snapshotId = "mock_snap_${state.currentSequence}",
                                asOfSequence = state.currentSequence,
                                nextCursor = if (filtered.size > limit) "mock_cur_$limit" else null,
                                hasMore = filtered.size > limit,
                                totalCount = filtered.size,
                            ),
                    ),
                summary = getSummaryDtoLocked(state),
            )
        }

    suspend fun getEventsPage(
        companyId: String,
        cursor: String,
        limit: Int = 50,
    ): EventsPageDto =
        mutex.withLock {
            val state = getOrCreateCompanyStateLocked(companyId)
            val offset = cursor.removePrefix("mock_cur_").toIntOrNull() ?: limit
            val filtered = filterEventsLocked(state.events, EventsQuery())
            val paged = filtered.drop(offset).take(limit)
            val nextOffset = offset + paged.size
            val hasMore = filtered.size > nextOffset
            EventsPageDto(
                items = paged,
                nextCursor = if (hasMore) "mock_cur_$nextOffset" else null,
                hasMore = hasMore,
                totalCount = filtered.size,
                asOfSequence = state.currentSequence,
                snapshotId = "mock_snap_${state.currentSequence}",
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
            val state = getOrCreateCompanyStateLocked(companyId)
            val index = state.events.indexOfFirst { it.id == eventId }
            if (index < 0) {
                throw EventsDomainException.Generic(
                    code = "NOT_FOUND",
                    serverMessage = "Event not found: $eventId",
                    retryable = false,
                )
            }
            val now = Clock.System.now().toString()
            val cur = state.events[index]
            val up =
                cur.copy(
                    status = "ACKNOWLEDGED",
                    acknowledgedAt = now,
                    acknowledgedBy = "Мок-оператор",
                    eventVersion = cur.eventVersion + 1,
                    acknowledgementVersion = cur.acknowledgementVersion + 1,
                )
            state.events[index] = up
            state.currentSequence++
            state.commits.add(
                TenantCommitFrameDto(
                    schemaVersion = 1,
                    type = "TENANT_COMMIT",
                    replayId = 1L,
                    tenantSequence = state.currentSequence,
                    payloadSha256 = state.currentSequence.toString(16).padStart(64, '0'),
                    eventUpdated = up,
                    summaryUpdated = getSummaryDtoLocked(state),
                ),
            )
            up
        }

    suspend fun markEventErroneous(
        companyId: String,
        eventId: String,
        expectedClassificationVersion: Long,
        idempotencyKey: String,
    ): EventDto =
        mutex.withLock {
            val key = MutationKey(companyId, eventId, idempotencyKey)
            val existing = mutationsByKey[key]
            if (existing != null) {
                if (existing.expectedClassificationVersion != expectedClassificationVersion) {
                    throw EventsDomainException.PayloadMismatch(
                        "Idempotency key reused with different expectedClassificationVersion",
                    )
                }
                return@withLock existing.result
            }

            val state = getOrCreateCompanyStateLocked(companyId)
            val index = state.events.indexOfFirst { it.id == eventId }
            if (index < 0) {
                throw EventsDomainException.Generic(
                    code = "NOT_FOUND",
                    serverMessage = "Event not found: $eventId",
                    retryable = false,
                )
            }
            val cur = state.events[index]
            if (cur.classificationVersion != expectedClassificationVersion) {
                throw EventsDomainException.StateConflict(
                    message =
                        "Classification version mismatch: expected $expectedClassificationVersion, " +
                            "got ${cur.classificationVersion}",
                )
            }
            val now = Clock.System.now().toString()
            val up =
                cur.copy(
                    isErroneous = true,
                    erroneousMarkedAt = now,
                    erroneousMarkedBy = "Мок-оператор",
                    eventVersion = cur.eventVersion + 1,
                    classificationVersion = cur.classificationVersion + 1,
                )
            state.events[index] = up
            state.currentSequence++
            mutationsByKey[key] = MutationRecord(expectedClassificationVersion, up)
            state.commits.add(
                TenantCommitFrameDto(
                    schemaVersion = 1,
                    type = "TENANT_COMMIT",
                    replayId = 1L,
                    tenantSequence = state.currentSequence,
                    payloadSha256 = state.currentSequence.toString(16).padStart(64, '0'),
                    eventUpdated = up,
                    summaryUpdated = getSummaryDtoLocked(state),
                ),
            )
            up
        }

    suspend fun createExport(
        companyId: String,
        request: CreateExportRequestDto,
        idempotencyKey: String,
    ): EventsExportStatusDto =
        mutex.withLock {
            val key = companyId to idempotencyKey
            val existingId = exportIdsByIdempotencyKey[key]
            if (existingId != null) {
                val existingRecord = exportsById[existingId]
                if (existingRecord != null) {
                    if (existingRecord.request != request) {
                        throw EventsDomainException.PayloadMismatch(
                            "Idempotency key was reused with a different request",
                        )
                    }
                    val now = Clock.System.now()
                    val isExpired = now >= existingRecord.expiresAt
                    return@withLock EventsExportStatusDto(
                        exportId = existingRecord.exportId,
                        status = if (isExpired) "EXPIRED" else "COMPLETED",
                        createdAt = existingRecord.createdAt.toString(),
                        finishedAt = existingRecord.finishedAt.toString(),
                        expiresAt = existingRecord.expiresAt.toString(),
                        processedRecords = existingRecord.processedRecords,
                        totalRecords = existingRecord.totalRecords,
                        progressPercent = 100,
                        downloadUrl = if (isExpired) null else existingRecord.downloadUrl,
                    )
                }
            }

            val state = getOrCreateCompanyStateLocked(companyId)
            val exportId = "mock-export-${++exportIdCounter}"
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
            val matchingEvents = filterEventsLocked(state.events, query)
            val total = matchingEvents.size.toLong()
            val downloadUrl = "https://example.com/$exportId.csv"

            val record =
                MockExportRecord(
                    exportId = exportId,
                    companyId = companyId,
                    idempotencyKey = idempotencyKey,
                    request = request,
                    createdAt = now,
                    finishedAt = now,
                    expiresAt = expiresAt,
                    processedRecords = total,
                    totalRecords = total,
                    downloadUrl = downloadUrl,
                )
            exportsById[exportId] = record
            exportIdsByIdempotencyKey[key] = exportId

            EventsExportStatusDto(
                exportId = exportId,
                status = "COMPLETED",
                createdAt = now.toString(),
                finishedAt = now.toString(),
                expiresAt = expiresAt.toString(),
                processedRecords = total,
                totalRecords = total,
                progressPercent = 100,
                downloadUrl = downloadUrl,
            )
        }

    suspend fun getExportStatus(
        companyId: String,
        exportId: String,
    ): EventsExportStatusDto =
        mutex.withLock {
            val record =
                exportsById[exportId]
                    ?: throw EventsDomainException.Generic(
                        code = "NOT_FOUND",
                        serverMessage = "Export not found: $exportId",
                        retryable = false,
                    )
            if (record.companyId != companyId) {
                throw EventsDomainException.PermissionDenied("Export does not belong to company: $companyId")
            }
            val now = Clock.System.now()
            val isExpired = now >= record.expiresAt
            EventsExportStatusDto(
                exportId = record.exportId,
                status = if (isExpired) "EXPIRED" else "COMPLETED",
                createdAt = record.createdAt.toString(),
                finishedAt = record.finishedAt.toString(),
                expiresAt = record.expiresAt.toString(),
                processedRecords = record.processedRecords,
                totalRecords = record.totalRecords,
                progressPercent = 100,
                downloadUrl = if (isExpired) null else record.downloadUrl,
            )
        }

    suspend fun downloadExport(
        companyId: String,
        exportId: String,
    ): EventsExportDownload =
        mutex.withLock {
            val record =
                exportsById[exportId]
                    ?: throw EventsDomainException.Generic(
                        code = "NOT_FOUND",
                        serverMessage = "Export not found: $exportId",
                        retryable = false,
                    )
            if (record.companyId != companyId) {
                throw EventsDomainException.PermissionDenied("Export does not belong to company: $companyId")
            }
            val now = Clock.System.now()
            if (now >= record.expiresAt) {
                throw EventsDomainException.ExportExpired("Export expired at ${record.expiresAt}")
            }

            val state = getOrCreateCompanyStateLocked(companyId)
            val req = record.request
            val includeMedia = req.includeMediaLinks
            val isSemicolon = req.formatPreset != "STANDARD_COMMA_UTF8"
            val sep = if (isSemicolon) ";" else ","
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
            val targetEvents = filterEventsLocked(state.events, query)

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
                                "${escapeCsv(
                                    e.id,
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.eventTypeName,
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.locationName,
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.deviceName,
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.occurredAt,
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.status,
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.isErroneous.toString(),
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.previewUrl.orEmpty(),
                                    sep,
                                )}${sep}${escapeCsv(e.videoUrl.orEmpty(), sep)}",
                            )
                        }
                    } else {
                        appendLine(
                            "ID${sep}Type${sep}Location${sep}Device${sep}OccurredAt${sep}Status${sep}IsErroneous",
                        )
                        for (e in targetEvents) {
                            appendLine(
                                "${escapeCsv(
                                    e.id,
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.eventTypeName,
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.locationName,
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.deviceName,
                                    sep,
                                )}${sep}${escapeCsv(
                                    e.occurredAt,
                                    sep,
                                )}${sep}${escapeCsv(e.status, sep)}${sep}${escapeCsv(e.isErroneous.toString(), sep)}",
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
            return@withLock EventsExportDownload(
                fileName = "events_export.csv",
                contentLength = bytes.size.toLong(),
                downloadHandle = handle,
            )
        }

    suspend fun generateAndAddRealtimeCommit(
        companyId: String = "comp-001",
        replayId: Long,
        insertAtBeginning: Boolean,
    ): TenantCommitFrameDto =
        mutex.withLock {
            val state = getOrCreateCompanyStateLocked(companyId)
            state.currentSequence++
            state.generatedCount++

            val typeIndex = (state.generatedCount - 1) % mockEventTypes.size
            val type = mockEventTypes[typeIndex]

            val (loc, dev) = allDevicesWithLocations[(state.generatedCount - 1) % allDevicesWithLocations.size]

            val now = Clock.System.now()
            val occurredAt =
                if (insertAtBeginning || state.events.isEmpty()) {
                    now
                } else {
                    val sorted =
                        state.events
                            .mapNotNull { runCatching { Instant.parse(it.occurredAt) }.getOrNull() }
                            .sortedDescending()
                    if (sorted.size >= 2) {
                        val newestTime = sorted.first().toEpochMilliseconds()
                        val oldestTime = sorted.last().toEpochMilliseconds()
                        val midTime = (newestTime + oldestTime) / 2
                        Instant.fromEpochMilliseconds(midTime)
                    } else {
                        now - 1.minutes
                    }
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
                    id = "ev_rt_${state.currentSequence}_${state.generatedCount}",
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

            state.events.add(eventDto)

            val commit =
                TenantCommitFrameDto(
                    schemaVersion = 1,
                    type = "TENANT_COMMIT",
                    replayId = replayId,
                    tenantSequence = state.currentSequence,
                    payloadSha256 = state.currentSequence.toString(16).padStart(64, '0'),
                    eventCreated = eventDto,
                    summaryUpdated = getSummaryDtoLocked(state),
                )
            state.commits.add(commit)
            commit
        }

    suspend fun getCommitsAfterSequence(
        companyId: String,
        afterSequence: Long,
    ): List<TenantCommitFrameDto> =
        mutex.withLock {
            val state = getOrCreateCompanyStateLocked(companyId)
            state.commits.filter { it.tenantSequence > afterSequence }
        }

    companion object {
        fun escapeCsv(
            value: String,
            separator: String = ",",
        ): String {
            val needsQuotes =
                value.contains(separator) ||
                    value.contains('"') ||
                    value.contains('\n') ||
                    value.contains('\r')
            return if (needsQuotes) {
                "\"${value.replace("\"", "\"\"")}\""
            } else {
                value
            }
        }
    }
}
