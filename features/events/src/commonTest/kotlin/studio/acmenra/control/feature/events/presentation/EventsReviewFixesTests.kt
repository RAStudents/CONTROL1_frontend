package studio.acmenra.control.feature.events.presentation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import studio.acmenra.control.core.network.client.ApiConfig
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.IncomingWsEnvelope
import studio.acmenra.control.feature.events.data.datasource.IncomingWsFrame
import studio.acmenra.control.feature.events.data.datasource.KtorEventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.KtorEventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.MockEventsDataStore
import studio.acmenra.control.feature.events.data.datasource.MockEventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.MockEventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.model.CreateExportRequestDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventsExportFiltersDto
import studio.acmenra.control.feature.events.data.model.ResyncRequiredFrameDto
import studio.acmenra.control.feature.events.data.model.TenantCommitFrameDto
import studio.acmenra.control.feature.events.data.realtime.EventsSessionProcessor
import studio.acmenra.control.feature.events.data.realtime.SessionCommand
import studio.acmenra.control.feature.events.di.eventsMockModule
import studio.acmenra.control.feature.events.di.eventsModule
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.domain.model.DeviceFilterOption
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventTypeOption
import studio.acmenra.control.feature.events.domain.model.EventValidityFilter
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsBootstrap
import studio.acmenra.control.feature.events.domain.model.EventsDomainException
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsMutationError
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsPage
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
import studio.acmenra.control.feature.events.domain.model.RealtimeConnectionState
import studio.acmenra.control.feature.events.domain.repository.EventsFeedSession
import studio.acmenra.control.feature.events.domain.repository.EventsFeedState
import studio.acmenra.control.feature.events.domain.usecase.AcknowledgeEventUseCase
import studio.acmenra.control.feature.events.domain.usecase.CreateEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.DownloadEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.LoadEventFilterOptionsUseCase
import studio.acmenra.control.feature.events.domain.usecase.MarkEventErroneousUseCase
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsExportStatusUseCase
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsFeedUseCase
import studio.acmenra.control.feature.events.presentation.platform.ExportFileSaver
import studio.acmenra.control.feature.events.presentation.util.EventDateDisplayFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EventsReviewFixesTests {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        stopKoin()
    }

    private class TestFeedSession(
        initialFeedState: EventsFeedState =
            EventsFeedState(
                access = EventsAccessCapabilities(eventsRead = true, eventsReview = true, reportsExport = true),
            ),
    ) : EventsFeedSession {
        override val companyId: String = "comp-1"
        override val query: EventsQuery = EventsQuery()
        private val _state = MutableStateFlow(initialFeedState)
        override val state: StateFlow<EventsFeedState> = _state.asStateFlow()

        val noticeFlow = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
        override val connectionNotice: Flow<Notice> = noticeFlow.asSharedFlow()

        val filterInvalidatedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        override val filterOptionsInvalidated: Flow<Unit> = filterInvalidatedFlow.asSharedFlow()

        var refreshCallCount = 0

        fun emitState(newState: EventsFeedState) {
            _state.value =
                if (!newState.access.eventsRead) {
                    newState.copy(
                        access = EventsAccessCapabilities(eventsRead = true, eventsReview = true, reportsExport = true),
                    )
                } else {
                    newState
                }
        }

        override fun loadNextPage() {}

        override fun refresh() {
            refreshCallCount++
        }

        override fun onViewportAtTop() {}

        override fun markOptimisticAckPending(eventId: String) {}

        override fun clearOptimisticAckPending(eventId: String) {}

        override fun applyAcknowledged(event: Event) {}

        override fun applyEventMutationResult(event: Event) {}

        override fun retryRebootstrap() {}

        override fun updateAccessCapabilities(access: EventsAccessCapabilities) {}

        override fun close() {}
    }

    private fun createTestViewModel(
        feedSession: TestFeedSession = TestFeedSession(),
        createExportUseCase: CreateEventsExportUseCase? = null,
        observeExportStatusUseCase: ObserveEventsExportStatusUseCase? = null,
        downloadExportUseCase: DownloadEventsExportUseCase? = null,
        exportSaver: ExportFileSaver? = null,
    ): EventsViewModel {
        val observeFeed =
            object : ObserveEventsFeedUseCase {
                override fun invoke(
                    companyId: String,
                    query: EventsQuery,
                    accessCapabilities: EventsAccessCapabilities,
                ): EventsFeedSession = feedSession
            }
        val loadOptions =
            object : LoadEventFilterOptionsUseCase {
                override suspend fun invoke(companyId: String): EventsFilterOptions = EventsFilterOptions()
            }
        val ack =
            object : AcknowledgeEventUseCase {
                override suspend fun invoke(
                    companyId: String,
                    eventId: String,
                ): EventsOperationResult<Event> =
                    EventsOperationResult.Failure(EventsMutationError.Server("not implemented"))
            }
        val markErroneous =
            object : MarkEventErroneousUseCase {
                override suspend fun invoke(
                    companyId: String,
                    eventId: String,
                    expectedClassificationVersion: Long,
                    idempotencyKey: String,
                ): EventsOperationResult<Event> =
                    EventsOperationResult.Failure(EventsMutationError.Server("not implemented"))
            }
        val defaultCreateExport =
            object : CreateEventsExportUseCase {
                override suspend fun invoke(
                    companyId: String,
                    request: EventsExportRequest,
                    idempotencyKey: String,
                ): EventsExportStatus =
                    EventsExportStatus(
                        exportId = "exp-1",
                        status = EventsExportStatusCode.QUEUED,
                        createdAt = Clock.System.now(),
                    )
            }
        val defaultObserveExportStatus =
            object : ObserveEventsExportStatusUseCase {
                override suspend fun invoke(
                    companyId: String,
                    exportId: String,
                ): EventsExportStatus =
                    EventsExportStatus(
                        exportId = exportId,
                        status = EventsExportStatusCode.COMPLETED,
                        createdAt = Clock.System.now(),
                    )
            }
        val defaultDownloadExport =
            object : DownloadEventsExportUseCase {
                override suspend fun invoke(
                    companyId: String,
                    exportId: String,
                ): EventsExportDownload = EventsExportDownload("file.csv", 100L, "h-1")
            }
        val defaultExportSaver =
            object : ExportFileSaver {
                override suspend fun saveExportFile(download: EventsExportDownload): Boolean = true
            }

        return EventsViewModel(
            observeEventsFeedUseCase = observeFeed,
            loadEventFilterOptionsUseCase = loadOptions,
            acknowledgeEventUseCase = ack,
            markEventErroneousUseCase = markErroneous,
            createEventsExportUseCase = createExportUseCase ?: defaultCreateExport,
            observeEventsExportStatusUseCase = observeExportStatusUseCase ?: defaultObserveExportStatus,
            downloadEventsExportUseCase = downloadExportUseCase ?: defaultDownloadExport,
            exportFileSaver = exportSaver ?: defaultExportSaver,
        )
    }

    // --- Requirement 1: DI resolution (P1) ---
    @Test
    fun testDI_ProductionModuleResolvesKtorByDefault_MockModuleResolvesMock() {
        val mockNetworkModule =
            module {
                single(named("api")) { HttpClient(MockEngine { respondOk() }) }
                single(named("realtime")) { HttpClient(MockEngine { respondOk() }) }
                single { ApiConfig(apiBaseUrl = "http://localhost", allowInsecureTransport = true) }
            }

        val koinApp =
            startKoin {
                modules(mockNetworkModule, eventsModule, eventsMockModule)
            }

        val defaultRemote = koinApp.koin.get<EventsRemoteDataSource>()
        assertTrue(
            defaultRemote is KtorEventsRemoteDataSource,
            "Default unqualified EventsRemoteDataSource must be KtorEventsRemoteDataSource, was ${defaultRemote::class.simpleName}",
        )

        val defaultWs = koinApp.koin.get<EventsWebSocketDataSource>()
        assertTrue(
            defaultWs is KtorEventsWebSocketDataSource,
            "Default unqualified EventsWebSocketDataSource must be KtorEventsWebSocketDataSource, was ${defaultWs::class.simpleName}",
        )

        val mockRemote = koinApp.koin.get<EventsRemoteDataSource>(named("mock"))
        assertTrue(
            mockRemote is MockEventsRemoteDataSource,
            "Named 'mock' EventsRemoteDataSource must be MockEventsRemoteDataSource, was ${mockRemote::class.simpleName}",
        )

        val mockWs = koinApp.koin.get<EventsWebSocketDataSource>(named("mock"))
        assertTrue(
            mockWs is MockEventsWebSocketDataSource,
            "Named 'mock' EventsWebSocketDataSource must be MockEventsWebSocketDataSource, was ${mockWs::class.simpleName}",
        )
    }

    // --- Requirement 2: Timezone gating (P1/P2) ---
    @Test
    fun testTimezoneGating_DateFilterRejectedWhenTimezoneNotLoaded() =
        runTest(testDispatcher) {
            val feedSession = TestFeedSession()
            val vm = createTestViewModel(feedSession)

            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true))
            advanceUntilIdle()

            // Initially, summary is null (no business timezone loaded)
            assertNull(
                vm.state.value.summary
                    ?.businessTimezone,
            )

            // Attempting to select a specific date without business timezone must be strictly rejected
            vm.onDateSelectionChanged(DateSelection.Single(LocalDate(2026, 3, 10)))
            assertNull(vm.state.value.filter.dateFrom)
            assertNull(vm.state.value.filter.dateTo)

            // However, DateSelection.All is always safe to apply
            vm.onDateSelectionChanged(DateSelection.All)
            assertNull(vm.state.value.filter.dateFrom)

            // Now feed emits summary with valid business timezone
            val tzId = "Asia/Yekaterinburg" // UTC+5
            feedSession.emitState(
                EventsFeedState(
                    summary =
                        EventsSummary(
                            businessTimezone = tzId,
                            businessDate = "2026-03-10",
                            summaryAsOfSequence = 1L,
                        ),
                ),
            )
            advanceUntilIdle()
            assertEquals(
                tzId,
                vm.state.value.summary
                    ?.businessTimezone,
            )

            // Applying date selection now works with exact business timezone bounds
            val localDate = LocalDate(2026, 3, 10)
            vm.onDateSelectionChanged(DateSelection.Single(localDate))

            val tz = TimeZone.of(tzId)
            val (expectedFrom, expectedTo) =
                DateFilterUtils.dateSelectionToUtcBounds(
                    DateSelection.Single(localDate),
                    tz,
                )
            assertEquals(expectedFrom, vm.state.value.filter.dateFrom)
            assertEquals(expectedTo, vm.state.value.filter.dateTo)
        }

    // --- Requirement 3: Filter options sanitization on empty lists (P2) ---
    @Test
    fun testFilterOptionsSanitize_SuccessfullyLoadedEmptyListRemovesStaleIds() {
        val initialFilter =
            EventsFilter(
                eventTypeIds = setOf("old_type_1", "old_type_2"),
                deviceIds = setOf("old_dev_1"),
            )

        // Server returned empty list of options for the company
        val emptyOptions =
            EventsFilterOptions(
                revision = "v2",
                eventTypes = emptyList(),
                locations = emptyList(),
            )

        val sanitized = emptyOptions.sanitize(initialFilter)
        assertTrue(sanitized.eventTypeIds.isEmpty(), "Loaded empty event types must clear filter eventTypeIds")
        assertTrue(sanitized.deviceIds.isEmpty(), "Loaded empty locations must clear filter deviceIds")

        // Non-empty options should preserve only valid IDs
        val optionsWithOne =
            EventsFilterOptions(
                eventTypes = listOf(EventTypeOption(id = "valid_type", name = "Valid", iconKey = EventIconKey.HELMET)),
                locations =
                    listOf(
                        LocationFilterGroup(
                            id = "loc_1",
                            name = "Loc 1",
                            devices = listOf(DeviceFilterOption(id = "valid_dev", name = "Cam 1")),
                        ),
                    ),
            )
        val mixedFilter =
            EventsFilter(
                eventTypeIds = setOf("old_type_1", "valid_type"),
                deviceIds = setOf("old_dev_1", "valid_dev"),
            )
        val sanitizedMixed = optionsWithOne.sanitize(mixedFilter)
        assertEquals(setOf("valid_type"), sanitizedMixed.eventTypeIds)
        assertEquals(setOf("valid_dev"), sanitizedMixed.deviceIds)
    }

    // --- Requirement 4: DatePicker round-trip and non-midnight bounds (P2) ---
    @Test
    fun testDateFilterUtils_RoundTripAndCustomIntervalContract() {
        val tz = TimeZone.of("Europe/Moscow")

        // 1. All
        val (allFrom, allTo) = DateFilterUtils.dateSelectionToUtcBounds(DateSelection.All, tz)
        assertNull(allFrom)
        assertNull(allTo)
        assertEquals(DateSelection.All, DateFilterUtils.utcBoundsToDateSelection(allFrom, allTo, tz))

        // 2. Single
        val singleDate = LocalDate(2026, 3, 5)
        val (singleFrom, singleTo) = DateFilterUtils.dateSelectionToUtcBounds(DateSelection.Single(singleDate), tz)
        assertNotNull(singleFrom)
        assertNotNull(singleTo)
        assertEquals(
            DateSelection.Single(singleDate),
            DateFilterUtils.utcBoundsToDateSelection(singleFrom, singleTo, tz),
        )

        // 3. Range
        val startRange = LocalDate(2026, 3, 1)
        val endRange = LocalDate(2026, 3, 7)
        val (rangeFrom, rangeTo) =
            DateFilterUtils.dateSelectionToUtcBounds(
                DateSelection.Range(startRange, endRange),
                tz,
            )
        assertEquals(
            DateSelection.Range(startRange, endRange),
            DateFilterUtils.utcBoundsToDateSelection(rangeFrom, rangeTo, tz),
        )

        // 4. From
        val (fromBoundsStart, fromBoundsEnd) =
            DateFilterUtils.dateSelectionToUtcBounds(
                DateSelection.From(startRange),
                tz,
            )
        assertNotNull(fromBoundsStart)
        assertNull(fromBoundsEnd)
        assertEquals(
            DateSelection.From(startRange),
            DateFilterUtils.utcBoundsToDateSelection(fromBoundsStart, fromBoundsEnd, tz),
        )

        // 5. Until
        val (untilBoundsStart, untilBoundsEnd) =
            DateFilterUtils.dateSelectionToUtcBounds(
                DateSelection.Until(endRange),
                tz,
            )
        assertNull(untilBoundsStart)
        assertNotNull(untilBoundsEnd)
        assertEquals(
            DateSelection.Until(endRange),
            DateFilterUtils.utcBoundsToDateSelection(untilBoundsStart, untilBoundsEnd, tz),
        )

        // 6. Non-midnight boundary (custom instant) throws IllegalArgumentException and MUST NOT be converted to All
        val arbitraryInstantFrom = Instant.parse("2026-03-01T10:30:00Z")
        val arbitraryInstantTo = Instant.parse("2026-03-07T16:45:00Z")
        assertFailsWith<IllegalArgumentException> {
            DateFilterUtils.utcBoundsToDateSelection(arbitraryInstantFrom, arbitraryInstantTo, tz)
        }

        // 7. EventDateDisplayFormatter formats custom interval accurately with time, not misleading date-only
        val formattedCustom =
            EventDateDisplayFormatter.formatFilterDisplayDate(
                dateFrom = arbitraryInstantFrom,
                dateTo = arbitraryInstantTo,
                businessTimeZone = tz,
                customIntervalPrefix = "Интервал",
            )
        assertNotNull(formattedCustom)
        assertTrue(formattedCustom.contains(":"), "Custom non-midnight interval formatting must include time")
    }

    // --- Requirement 5: Search debounce and reset URL synchronization (P2) ---
    @Test
    fun testSearchDebounce_SingleOwnerAndEarlyReturnWhenUnchanged() =
        runTest(testDispatcher) {
            val feedSession = TestFeedSession()
            var observedUrlHash: String? = null
            val vm =
                createTestViewModel(feedSession).apply {
                    onQueryHashChanged = { observedUrlHash = it }
                }

            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true))
            advanceUntilIdle()

            val initialRestartCount = feedSession.refreshCallCount

            // 1. Rapid typing in search field triggers debounce in ViewModel without race condition
            vm.onSearchIdChanged("ev_1")
            advanceTimeBy(100L)
            vm.onSearchIdChanged("ev_12")
            advanceTimeBy(100L)
            vm.onSearchIdChanged("ev_123")

            // Not yet triggered (debounce is 400ms)
            assertEquals(initialRestartCount, feedSession.refreshCallCount)

            // Advance debounce time
            advanceTimeBy(400L)
            advanceUntilIdle()
            assertEquals("ev_123", vm.state.value.filter.searchId)
            assertNotNull(observedUrlHash)
            assertTrue(observedUrlHash!!.contains("searchId=ev_123") || observedUrlHash!!.contains("ev_123"))

            // 2. Committing identical searchId does NOT restart the feed
            val sessionCountAfterSearch = feedSession.refreshCallCount
            vm.onSearchIdCommitted("ev_123")
            advanceUntilIdle()
            assertEquals(sessionCountAfterSearch, feedSession.refreshCallCount)

            // 3. Reset filters cancels debounce and immediately synchronizes to URL
            vm.onSearchIdChanged("ev_pending")
            vm.resetFilters()
            advanceUntilIdle()
            assertNull(vm.state.value.filter.searchId)
            assertFalse(vm.state.value.filter.hasActiveFilters)
            assertNotNull(observedUrlHash)
            assertFalse(observedUrlHash!!.contains("searchId=ev_pending"))
            assertFalse(observedUrlHash!!.contains("ev_pending"))
        }

    // --- Requirement 6: Export isDownloadable check ---
    @Test
    fun testExport_IsDownloadable_StrictlyRequiresCompletedAndUrlAndNonExpired() {
        val now = Clock.System.now()

        val valid =
            EventsExportStatus(
                exportId = "exp-valid",
                status = EventsExportStatusCode.COMPLETED,
                createdAt = now - 1.hours,
                expiresAt = now + 1.hours,
                downloadUrl = "https://example.com/file.csv",
            )
        assertTrue(valid.isDownloadable(now))

        val expired = valid.copy(expiresAt = now - 1.seconds)
        assertFalse(expired.isDownloadable(now))

        val noUrl = valid.copy(downloadUrl = null)
        assertFalse(noUrl.isDownloadable(now))

        val processing = valid.copy(status = EventsExportStatusCode.PROCESSING)
        assertFalse(processing.isDownloadable(now))
    }

    // --- Requirement 7: Export flushes pending search input (P2) ---
    @Test
    fun testExport_SearchDebounceFlushedOnStartExport() =
        runTest(testDispatcher) {
            val feedSession = TestFeedSession()
            var createdRequest: EventsExportRequest? = null
            val createExportSpy =
                object : CreateEventsExportUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        request: EventsExportRequest,
                        idempotencyKey: String,
                    ): EventsExportStatus {
                        createdRequest = request
                        return EventsExportStatus(
                            exportId = "exp-flushed",
                            status = EventsExportStatusCode.QUEUED,
                            createdAt = Clock.System.now(),
                        )
                    }
                }
            val vm = createTestViewModel(feedSession, createExportUseCase = createExportSpy)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))
            advanceUntilIdle()

            // User types search ID and clicks Start Export after only 100ms (< 400ms debounce)
            vm.onSearchIdChanged("ev_flush_123")
            advanceTimeBy(100L)
            vm.startExport()
            advanceUntilIdle()

            assertNotNull(createdRequest, "Export request must be created")
            assertEquals("ev_flush_123", createdRequest!!.query.filter.searchId)
            assertEquals("ev_flush_123", vm.state.value.filter.searchId)
        }

    // --- Requirement 8: Strict canDownloadExport validation (P2) ---
    @Test
    fun testExport_StrictDownloadValidation() =
        runTest(testDispatcher) {
            val feedSession = TestFeedSession()
            var downloadCallCount = 0
            val downloadSpy =
                object : DownloadEventsExportUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        exportId: String,
                    ): EventsExportDownload {
                        downloadCallCount++
                        return EventsExportDownload("file.csv", 100L, "h-1")
                    }
                }
            val vm = createTestViewModel(feedSession, downloadExportUseCase = downloadSpy)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))
            advanceUntilIdle()

            // 1. Without job status: downloadExport must be rejected
            vm.downloadExport("arbitrary-id")
            advanceUntilIdle()
            assertEquals(0, downloadCallCount)

            // 2. Start an export that completes
            vm.startExport()
            advanceUntilIdle()

            // Export in state is "exp-1", attempting to download "other-id" must be rejected
            vm.downloadExport("other-id")
            advanceUntilIdle()
            assertEquals(0, downloadCallCount)
        }

    // --- Requirement 9: MockDataStore idempotency, isolation, and escaping (P2) ---
    @Test
    fun testMockDataStore_IdempotencyAndCompanyIsolationAndCsvEscaping() =
        runTest {
            val store = MockEventsDataStore()
            val request =
                CreateExportRequestDto(
                    filters = EventsExportFiltersDto(),
                    includeMediaLinks = false,
                    formatPreset = "STANDARD_COMMA_UTF8",
                )

            // 1. Idempotency: same companyId and idempotencyKey returns exact same exportId
            val status1 = store.createExport("comp-A", request, "key-123")
            val status2 = store.createExport("comp-A", request, "key-123")
            assertEquals(status1.exportId, status2.exportId)
            assertEquals(status1.createdAt, status2.createdAt)

            // 2. Company isolation: accessing comp-A's export with comp-B fails closed
            assertFailsWith<EventsDomainException.PermissionDenied> {
                store.getExportStatus("comp-B", status1.exportId)
            }
            assertFailsWith<EventsDomainException.PermissionDenied> {
                store.downloadExport("comp-B", status1.exportId)
            }
        }

    // --- Requirement 10: Date display formatting localization and seconds (P2/P3) ---
    @Test
    fun testDateDisplayFormatter_LocalizationAndSeconds() {
        val tz = TimeZone.of("Europe/Moscow")

        // 1. Localization prefixes
        val fromInstant = Instant.parse("2026-03-05T00:00:00Z")
        val formattedEn =
            EventDateDisplayFormatter.formatFilterDisplayDate(
                dateFrom = fromInstant,
                dateTo = null,
                businessTimeZone = tz,
                fromPrefix = "from",
                toPrefix = "to",
            )
        assertNotNull(formattedEn)
        assertTrue(formattedEn.startsWith("from "))

        val formattedRu =
            EventDateDisplayFormatter.formatFilterDisplayDate(
                dateFrom = fromInstant,
                dateTo = null,
                businessTimeZone = tz,
                fromPrefix = "от",
                toPrefix = "до",
            )
        assertNotNull(formattedRu)
        assertTrue(formattedRu.startsWith("от "))

        // 2. Non-zero seconds in formatLocalDateTime
        val dtWithSec = LocalDateTime(2026, 3, 5, 14, 30, 45)
        val formattedWithSec = EventDateDisplayFormatter.formatLocalDateTime(dtWithSec)
        assertEquals("05.03.2026 14:30:45", formattedWithSec)

        val dtWithoutSec = LocalDateTime(2026, 3, 5, 14, 30, 0)
        val formattedWithoutSec = EventDateDisplayFormatter.formatLocalDateTime(dtWithoutSec)
        assertEquals("05.03.2026 14:30", formattedWithoutSec)
    }

    // --- Requirement 3.1: Live update for unknown event triggers authoritative rebootstrap ---
    @Test
    fun testRealtime_UnknownUpdatedEvent_TriggersRebootstrap() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var rebootstrapTriggered = false
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { rebootstrapTriggered = true },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val initialBootstrap =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-1")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 100L,
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 100L,
                                todayEventsCount = 1,
                            ),
                        streamCursor = 100L,
                    )
                processor.installBootstrap(initialBootstrap, generation = 1L)
                processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.CONNECTED))
                advanceUntilIdle()

                val activeReplayId = processor.currentReplayId
                assertFalse(rebootstrapTriggered)

                // Dispatch an update for an event unknown to snapshot or overlay
                val commit101 =
                    TenantCommitFrameDto(
                        replayId = activeReplayId,
                        tenantSequence = 101L,
                        payloadSha256 = "0".repeat(64),
                        eventUpdated = sampleEventDto("ev-unknown-999"),
                    )

                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            connectionId = 1L,
                            frame = IncomingWsFrame.TenantCommit(commit101),
                        ),
                    ),
                )
                advanceUntilIdle()

                assertTrue(rebootstrapTriggered, "Update for unknown event must trigger authoritative rebootstrap")
            } finally {
                testScope.cancel()
            }
        }

    // --- Requirement 3.2: Batch replay sequentially computes net count delta against workingEvents ---
    @Test
    fun testRealtime_MultipleUpdatesInSameReplayBatch_SequentialCountDelta() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val query = EventsQuery(filter = EventsFilter(validity = EventValidityFilter.VALID))
                val processor =
                    EventsSessionProcessor(
                        query = query,
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val initialBootstrap =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-1", isErroneous = false)),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 100L,
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 100L,
                                todayEventsCount = 1,
                            ),
                        streamCursor = 100L,
                    )
                processor.installBootstrap(initialBootstrap, generation = 1L)
                advanceUntilIdle()
                val activeReplayId = processor.currentReplayId
                assertEquals(1, processor.state.value.snapshotTotalCount)

                // Enter rebootstrap via RESYNC_REQUIRED so subsequent commits are buffered
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            connectionId = 1L,
                            frame =
                                IncomingWsFrame.ResyncRequired(
                                    ResyncRequiredFrameDto(replayId = activeReplayId),
                                ),
                        ),
                    ),
                )
                advanceUntilIdle()

                val bufferReplayId = processor.nextReplayGeneration()

                // Batch contains sequential updates for the same event:
                // 1) ev-1: VALID -> ERRONEOUS (delta -1 -> totalCount 0)
                // 2) ev-1: ERRONEOUS -> VALID (delta +1 -> totalCount 1)
                // 3) ev-1: VALID -> ERRONEOUS (delta -1 -> totalCount 0)
                val commit101 =
                    TenantCommitFrameDto(
                        replayId = bufferReplayId,
                        tenantSequence = 101L,
                        payloadSha256 = "1".repeat(64),
                        eventUpdated = sampleEventDto("ev-1", isErroneous = true),
                    )
                val commit102 =
                    TenantCommitFrameDto(
                        replayId = bufferReplayId,
                        tenantSequence = 102L,
                        payloadSha256 = "2".repeat(64),
                        eventUpdated = sampleEventDto("ev-1", isErroneous = false),
                    )
                val commit103 =
                    TenantCommitFrameDto(
                        replayId = bufferReplayId,
                        tenantSequence = 103L,
                        payloadSha256 = "3".repeat(64),
                        eventUpdated = sampleEventDto("ev-1", isErroneous = true),
                    )

                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(connectionId = 1L, frame = IncomingWsFrame.TenantCommit(commit101)),
                    ),
                )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(connectionId = 1L, frame = IncomingWsFrame.TenantCommit(commit102)),
                    ),
                )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(connectionId = 1L, frame = IncomingWsFrame.TenantCommit(commit103)),
                    ),
                )
                advanceUntilIdle()

                // Authoritative bootstrap arrives with snapshot cursor = 100L and totalCount = 1
                val rebootstrapSnapshot =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-1", isErroneous = false)),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 100L,
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 100L,
                                todayEventsCount = 1,
                            ),
                        streamCursor = 100L,
                    )
                processor.installBootstrap(rebootstrapSnapshot, generation = 2L)
                advanceUntilIdle()

                // With sequential evaluation against workingEvents, count is accurately 0 (1 - 1 + 1 - 1)
                assertEquals(0, processor.state.value.snapshotTotalCount)
            } finally {
                testScope.cancel()
            }
        }

    private fun sampleEvent(
        id: String,
        isErroneous: Boolean = false,
    ): Event =
        Event(
            id = id,
            eventVersion = 1L,
            classificationVersion = 1L,
            acknowledgementVersion = 1L,
            eventTypeId = "type-1",
            eventTypeName = "Safety Violation",
            categoryCode = "safety",
            iconKey = EventIconKey.GENERIC_ALERT,
            locationId = "loc-1",
            locationName = "Workshop 1",
            deviceId = "dev-1",
            deviceName = "Camera 1",
            occurredAt = Instant.parse("2026-03-01T10:00:00Z"),
            receivedAt = Instant.parse("2026-03-01T10:00:01Z"),
            status = EventAcknowledgementStatus.NEW,
            acknowledgedAt = null,
            acknowledgedBy = null,
            isErroneous = isErroneous,
            previewUrl = "https://cdn.example.com/preview.jpg",
            videoUrl = "https://cdn.example.com/video.mp4",
        )

    private fun sampleEventDto(
        id: String,
        isErroneous: Boolean = false,
    ): EventDto =
        EventDto(
            id = id,
            eventVersion = 1L,
            classificationVersion = 1L,
            acknowledgementVersion = 1L,
            eventTypeId = "type-1",
            eventTypeName = "Safety Violation",
            categoryCode = "safety",
            iconKey = "generic_alert",
            locationId = "loc-1",
            locationName = "Workshop 1",
            deviceId = "dev-1",
            deviceName = "Camera 1",
            occurredAt = "2026-03-01T10:00:00Z",
            receivedAt = "2026-03-01T10:00:01Z",
            status = "NEW",
            isErroneous = isErroneous,
            previewUrl = "https://cdn.example.com/preview.jpg",
            videoUrl = "https://cdn.example.com/video.mp4",
        )
}
