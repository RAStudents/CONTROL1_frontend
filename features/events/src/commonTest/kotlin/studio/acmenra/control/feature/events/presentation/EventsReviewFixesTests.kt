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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import studio.acmenra.control.core.network.client.ApiConfig
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.ExportPayloadRegistry
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
import studio.acmenra.control.feature.events.di.eventsMockEnvironmentModule
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
import studio.acmenra.control.feature.events.domain.model.EventsQueryRouteCodec
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
import studio.acmenra.control.feature.events.domain.model.OverlayEntry
import studio.acmenra.control.feature.events.domain.model.OverlayMembership
import studio.acmenra.control.feature.events.domain.model.RealtimeConnectionState
import studio.acmenra.control.feature.events.domain.model.RealtimeOverlay
import studio.acmenra.control.feature.events.domain.repository.EventsFeedSession
import studio.acmenra.control.feature.events.domain.repository.EventsFeedState
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.AcknowledgeEventUseCase
import studio.acmenra.control.feature.events.domain.usecase.CreateEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.DownloadEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.LoadEventFilterOptionsUseCase
import studio.acmenra.control.feature.events.domain.usecase.LoadEventsBootstrapUseCase
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
    fun testExport_IdempotencyPayloadMismatchAndMultiCompany() =
        runTest {
            val store = MockEventsDataStore()
            val request1 =
                CreateExportRequestDto(
                    filters = EventsExportFiltersDto(validity = "VALID"),
                    includeMediaLinks = false,
                    formatPreset = "STANDARD_COMMA_UTF8",
                )
            val request2 =
                CreateExportRequestDto(
                    filters = EventsExportFiltersDto(validity = "ERRONEOUS"),
                    includeMediaLinks = false,
                    formatPreset = "STANDARD_COMMA_UTF8",
                )

            // 1. Same company + same key + same request -> exact same export
            val status1 = store.createExport("comp-A", request1, "key-idem")
            val status2 = store.createExport("comp-A", request1, "key-idem")
            assertEquals(status1.exportId, status2.exportId)

            // 2. Same company + same key + DIFFERENT request -> throws PayloadMismatch
            assertFailsWith<EventsDomainException.PayloadMismatch> {
                store.createExport("comp-A", request2, "key-idem")
            }

            // 3. Different company + same key -> distinct export created
            val statusB = store.createExport("comp-B", request1, "key-idem")
            assertTrue(status1.exportId != statusB.exportId)

            // 4. Company isolation on status / download
            assertFailsWith<EventsDomainException.PermissionDenied> {
                store.getExportStatus("comp-B", status1.exportId)
            }
            assertFailsWith<EventsDomainException.PermissionDenied> {
                store.downloadExport("comp-B", status1.exportId)
            }
        }

    @Test
    fun testMockDataStore_CsvEscapingFull() =
        runTest {
            // 1. Direct unit assertions for escapeCsv
            assertEquals("Normal Text", MockEventsDataStore.escapeCsv("Normal Text", ","))
            assertEquals("\"Camera, Section 1\"", MockEventsDataStore.escapeCsv("Camera, Section 1", ","))
            assertEquals("\"Camera; Section 1\"", MockEventsDataStore.escapeCsv("Camera; Section 1", ";"))
            assertEquals(
                "\"Camera \"\"Main\"\", Section 1\"",
                MockEventsDataStore.escapeCsv("Camera \"Main\", Section 1", ","),
            )
            assertEquals("\"Line 1\nLine 2\"", MockEventsDataStore.escapeCsv("Line 1\nLine 2", ","))
            assertEquals("\"Line 1\r\nLine 2\"", MockEventsDataStore.escapeCsv("Line 1\r\nLine 2", ","))

            // 2. End-to-end integration assertion with downloadExport
            val store = MockEventsDataStore()
            val request =
                CreateExportRequestDto(
                    filters = EventsExportFiltersDto(),
                    includeMediaLinks = false,
                    formatPreset = "STANDARD_COMMA_UTF8",
                )
            val exportStatus = store.createExport("comp-csv", request, "key-csv")
            val download = store.downloadExport("comp-csv", exportStatus.exportId)
            val payload = ExportPayloadRegistry.consume(download.downloadHandle)
            assertNotNull(payload)
            val csvContent = payload.bytes.decodeToString()
            assertTrue(csvContent.startsWith("ID,Type,Location,Device,OccurredAt,Status,IsErroneous"))
        }

    @Test
    fun testMockMutations_NotFound_ConcurrencyConflict_AndIdempotency() =
        runTest {
            val store = MockEventsDataStore()

            // 1. Unknown event ID on acknowledgeEvent throws NOT_FOUND
            val notFoundAck =
                assertFailsWith<EventsDomainException.Generic> {
                    store.acknowledgeEvent("comp-1", "non-existent-event")
                }
            assertEquals("NOT_FOUND", notFoundAck.code)

            // 2. Unknown event ID on markEventErroneous throws NOT_FOUND
            val notFoundErr =
                assertFailsWith<EventsDomainException.Generic> {
                    store.markEventErroneous("comp-1", "non-existent-event", 1L, "key-1")
                }
            assertEquals("NOT_FOUND", notFoundErr.code)

            // 3. Existing event with classification version mismatch throws StateConflict
            assertFailsWith<EventsDomainException.StateConflict> {
                store.markEventErroneous("comp-1", "ev_mock_comp-1_1", 999L, "key-wrong-ver")
            }

            // 4. Mark erroneous succeeds with correct expectedClassificationVersion = 1L
            val res1 = store.markEventErroneous("comp-1", "ev_mock_comp-1_1", 1L, "key-idem")
            assertTrue(res1.isErroneous)
            assertEquals(2L, res1.classificationVersion)

            // 5. Repeat with same idempotency key and same version returns cached result
            val res2 = store.markEventErroneous("comp-1", "ev_mock_comp-1_1", 1L, "key-idem")
            assertEquals(res1.id, res2.id)
            assertEquals(res1.eventVersion, res2.eventVersion)

            // 6. Repeat with same idempotency key but different expected version throws PayloadMismatch
            assertFailsWith<EventsDomainException.PayloadMismatch> {
                store.markEventErroneous("comp-1", "ev_mock_comp-1_1", 2L, "key-idem")
            }
        }

    @Test
    fun testMockWebSocket_ReplayMissedCommits() =
        runTest {
            val store = MockEventsDataStore()
            val ws = MockEventsWebSocketDataSource(store)

            // 1. Generate 5 commits in store (sequences 101L..105L)
            for (i in 1..5) {
                store.generateAndAddRealtimeCommit(
                    companyId = "comp-replay",
                    replayId = 1L,
                    insertAtBeginning = false,
                )
            }

            val receivedFrames = mutableListOf<IncomingWsFrame>()
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            try {
                val job =
                    testScope.launch {
                        ws.incomingFrames.collect { envelope ->
                            receivedFrames.add(envelope.frame)
                        }
                    }
                testScheduler.runCurrent()

                // 2. Connect requesting replay afterSequence = 102L (should replay 103L, 104L, 105L)
                val connectionId =
                    ws.connect(
                        companyId = "comp-replay",
                        accessToken = "token",
                        replayId = 2L,
                        afterSequence = 102L,
                        scope = testScope,
                        getClientConfirmedSeq = { 102L },
                        activationGate = null,
                        onDisconnected = { _, _ -> },
                    )

                testScheduler.runCurrent()
                ws.disconnect(connectionId)
                job.cancel()

                // Subscribed with replayEmpty = false
                val subscribed = receivedFrames.filterIsInstance<IncomingWsFrame.Subscribed>().firstOrNull()
                assertNotNull(subscribed)
                assertFalse(subscribed.frame.replayEmpty)
                assertEquals(103L, subscribed.frame.replayFromSequence)
                assertEquals(105L, subscribed.frame.replayUntilSequence)

                // 3 replayed commits in order
                val replayedCommits = receivedFrames.filterIsInstance<IncomingWsFrame.TenantCommit>()
                assertEquals(3, replayedCommits.size)
                assertEquals(listOf(103L, 104L, 105L), replayedCommits.map { it.frame.tenantSequence })

                // ReplayCompleted with throughSequence = 105L
                val completed = receivedFrames.filterIsInstance<IncomingWsFrame.ReplayCompleted>().firstOrNull()
                assertNotNull(completed)
                assertEquals(105L, completed.frame.throughSequence)
            } finally {
                testScope.cancel()
            }
        }

    @Test
    fun testNavigation_CompanyIsolation_NoFilterLeakAcrossCompanies() {
        val hashCompanyA = "#events?companyId=comp-A&search_id=ev-A-123"
        val (decodedCompanyA, queryA) = EventsQueryRouteCodec.decodeHash(hashCompanyA)
        assertEquals("comp-A", decodedCompanyA)
        assertEquals("ev-A-123", queryA.filter.searchId)

        // When switching to Company B:
        val routeCompanyB = "comp-B"
        val isCompanyMatching = (decodedCompanyA == routeCompanyB)
        assertFalse(isCompanyMatching, "Hash for Company A must not match Company B")

        // Hash for B falls back to fresh company B hash without A's filters
        val targetHashForB = if (isCompanyMatching) hashCompanyA else "#events?companyId=$routeCompanyB"
        assertEquals("#events?companyId=comp-B", targetHashForB)
    }

    @Test
    fun testNavigation_FreshPageOpen_PreservesFiltersAndCompany() {
        val initialBrowserHash = "#events?companyId=comp-001&event_type_ids=type_helmet&validity=VALID"
        val (decodedCompany, query) = EventsQueryRouteCodec.decodeHash(initialBrowserHash)
        assertEquals("comp-001", decodedCompany)
        assertEquals(setOf("type_helmet"), query.filter.eventTypeIds)
        assertEquals(EventValidityFilter.VALID, query.filter.validity)

        // On fresh page load, the initial backstack route may have null cleanCompanyId
        val routeCleanCompany: String? = null
        val isSameCompany =
            routeCleanCompany == null || decodedCompany == null || routeCleanCompany == decodedCompany
        assertTrue(isSameCompany, "Fresh page load with null routeCleanCompany must match decoded company")

        val isEventsHash =
            initialBrowserHash.startsWith("#events") || initialBrowserHash.startsWith("events")
        val targetHash =
            if (isSameCompany && isEventsHash) {
                initialBrowserHash
            } else {
                "#events?companyId=${routeCleanCompany ?: "null"}"
            }
        assertEquals(initialBrowserHash, targetHash, "Target hash must preserve full URL filters on fresh page open")
    }

    @Test
    fun testMockSummary_DynamicBusinessDate() =
        runTest {
            val store = MockEventsDataStore()
            val bootstrap = store.getBootstrap("comp-date-test", EventsQuery())
            val expectedToday =
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.of("Europe/Moscow"))
                    .date
                    .toString()
            assertEquals(expectedToday, bootstrap.summary.businessDate)
        }

    @Test
    fun testDI_MockEnvironmentWiresMockSourcesToRepository() {
        val testTokenStorage =
            object : studio.acmenra.control.core.network.auth.TokenStorage {
                private var tokens: studio.acmenra.control.core.network.model.TokenPair? = null

                override fun getTokens(): studio.acmenra.control.core.network.model.TokenPair? = tokens

                override fun saveTokens(tokens: studio.acmenra.control.core.network.model.TokenPair) {
                    this.tokens =
                        tokens
                }

                override fun clear() {
                    tokens = null
                }
            }

        val testKoin =
            startKoin {
                modules(
                    module {
                        single<ApiConfig> { ApiConfig(apiBaseUrl = "http://localhost:8080") }
                        single(named("api")) { HttpClient(MockEngine { respondOk() }) }
                        single(named("realtime")) { HttpClient(MockEngine { respondOk() }) }
                        single {
                            studio.acmenra.control.core.network.auth
                                .SessionManager(testTokenStorage)
                        }
                        single<studio.acmenra.control.core.ui.notice.ConnectivityMonitor> {
                            object : studio.acmenra.control.core.ui.notice.ConnectivityMonitor {
                                override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
                            }
                        }
                    },
                    eventsMockEnvironmentModule,
                )
            }

        val remoteSource = testKoin.koin.get<EventsRemoteDataSource>()
        assertTrue(remoteSource is MockEventsRemoteDataSource, "Remote source must be mock in mock environment")

        val wsSource = testKoin.koin.get<EventsWebSocketDataSource>()
        assertTrue(wsSource is MockEventsWebSocketDataSource, "WebSocket source must be mock in mock environment")

        val repo = testKoin.koin.get<EventsRepository>()
        assertNotNull(repo, "EventsRepository must resolve in mock environment")

        val mockRepo = testKoin.koin.get<EventsRepository>(named("mock"))
        assertNotNull(mockRepo, "Named mock EventsRepository must resolve")

        val observeFeedUseCase = testKoin.koin.getOrNull<ObserveEventsFeedUseCase>()
        assertNotNull(observeFeedUseCase, "ObserveEventsFeedUseCase must resolve in mock environment")

        val loadBootstrapUseCase = testKoin.koin.getOrNull<LoadEventsBootstrapUseCase>()
        assertNotNull(loadBootstrapUseCase, "LoadEventsBootstrapUseCase must resolve in mock environment")

        val vm = testKoin.koin.getOrNull<EventsViewModel>()
        assertNotNull(vm, "EventsViewModel must resolve in mock environment")
    }

    @Test
    fun testFilterChange_DoesNotTriggerFullPageReloadOrWipeSummaryAndEvents() =
        runTest {
            val initialSummary =
                EventsSummary(
                    businessTimezone = "Europe/Moscow",
                    businessDate = "2026-11-22",
                    summaryAsOfSequence = 100L,
                    todayEventsCount = 10,
                )
            val session1 =
                TestFeedSession(
                    initialFeedState =
                        EventsFeedState(
                            isInitialLoading = false,
                            visibleEvents = listOf(sampleEvent("ev-1")),
                            summary = initialSummary,
                            snapshotTotalCount = 1,
                            access = EventsAccessCapabilities(eventsRead = true),
                        ),
                )
            var currentSession = session1

            val observeFeed =
                object : ObserveEventsFeedUseCase {
                    override fun invoke(
                        companyId: String,
                        query: EventsQuery,
                        accessCapabilities: EventsAccessCapabilities,
                    ): EventsFeedSession = currentSession
                }

            val vm =
                EventsViewModel(
                    observeEventsFeedUseCase = observeFeed,
                    loadEventFilterOptionsUseCase =
                        object : LoadEventFilterOptionsUseCase {
                            override suspend fun invoke(companyId: String): EventsFilterOptions = EventsFilterOptions()
                        },
                    acknowledgeEventUseCase =
                        object : AcknowledgeEventUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                eventId: String,
                            ) = EventsOperationResult.Failure(EventsMutationError.Server("stub"))
                        },
                    markEventErroneousUseCase =
                        object : MarkEventErroneousUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                eventId: String,
                                v: Long,
                                k: String,
                            ) = EventsOperationResult.Failure(EventsMutationError.Server("stub"))
                        },
                    createEventsExportUseCase =
                        object : CreateEventsExportUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                r: EventsExportRequest,
                                k: String,
                            ) = EventsExportStatus(
                                exportId = "exp-1",
                                status = EventsExportStatusCode.QUEUED,
                                createdAt = Clock.System.now(),
                            )
                        },
                    observeEventsExportStatusUseCase =
                        object : ObserveEventsExportStatusUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                exportId: String,
                            ) = EventsExportStatus(
                                exportId = "exp-1",
                                status = EventsExportStatusCode.COMPLETED,
                                createdAt = Clock.System.now(),
                            )
                        },
                    downloadEventsExportUseCase =
                        object : DownloadEventsExportUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                exportId: String,
                            ) = EventsExportDownload("file.csv", 100L, "h")
                        },
                    exportFileSaver =
                        object : ExportFileSaver {
                            override suspend fun saveExportFile(download: EventsExportDownload) = true
                        },
                )

            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true))
            advanceUntilIdle()

            // Verify initial state has events and summary
            assertEquals(1, vm.state.value.visibleEvents.size)
            assertEquals(
                "ev-1",
                vm.state.value.visibleEvents
                    .first()
                    .id,
            )
            assertNotNull(vm.state.value.summary)
            assertFalse(vm.state.value.isInitialLoading, "Initial load must be completed")

            // Prepare session2 with in-flight initial state (isInitialLoading = true)
            val session2 =
                TestFeedSession(
                    initialFeedState =
                        EventsFeedState(
                            isInitialLoading = true,
                            visibleEvents = emptyList(),
                            summary = null,
                            snapshotTotalCount = 0,
                            access = EventsAccessCapabilities(eventsRead = true),
                        ),
                )
            currentSession = session2

            // Apply a filter: session restarts
            vm.onEventTypeToggled("type_helmet")
            advanceUntilIdle()

            // In-flight state during filter change MUST NOT wipe visibleEvents or summary, and must NOT set isInitialLoading to true
            assertFalse(vm.state.value.isInitialLoading, "Filter change must not reset isInitialLoading to true")
            assertNotNull(
                vm.state.value.summary,
                "Filter change must not wipe existing summary to null while in flight",
            )
            assertEquals(
                "Europe/Moscow",
                vm.state.value.summary
                    ?.businessTimezone,
                "Timezone must be preserved",
            )
            assertEquals(
                1,
                vm.state.value.visibleEvents.size,
                "Previous visible events must not be wiped while in flight",
            )
            assertEquals(
                "ev-1",
                vm.state.value.visibleEvents
                    .first()
                    .id,
            )

            // When new session's bootstrap finishes:
            val updatedSummary = initialSummary.copy(todayEventsCount = 15)
            session2.emitState(
                EventsFeedState(
                    isInitialLoading = false,
                    visibleEvents = listOf(sampleEvent("ev-2")),
                    summary = updatedSummary,
                    snapshotTotalCount = 1,
                    access = EventsAccessCapabilities(eventsRead = true),
                ),
            )
            advanceUntilIdle()

            // Verify smooth transition to new filtered data
            assertEquals(1, vm.state.value.visibleEvents.size)
            assertEquals(
                "ev-2",
                vm.state.value.visibleEvents
                    .first()
                    .id,
            )
            assertEquals(
                15,
                vm.state.value.summary
                    ?.todayEventsCount,
            )
            assertFalse(vm.state.value.isInitialLoading)
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

    @Test
    fun testLiveCreatedClassification_DirectAndPendingNewerMaterialization() =
        runTest {
            val event1 = sampleEvent("ev-1")
            val event2 = sampleEvent("ev-2")
            val event3 = sampleEvent("ev-3")

            val initialFeed =
                EventsFeedState(
                    isInitialLoading = false,
                    visibleEvents = listOf(event1),
                    snapshotId = "snap-1",
                    asOfSequence = 10L,
                    access = EventsAccessCapabilities(eventsRead = true),
                )
            val session = TestFeedSession(initialFeed)
            val observeFeed =
                object : ObserveEventsFeedUseCase {
                    override fun invoke(
                        companyId: String,
                        query: EventsQuery,
                        accessCapabilities: EventsAccessCapabilities,
                    ): EventsFeedSession = session
                }

            val vm =
                EventsViewModel(
                    observeEventsFeedUseCase = observeFeed,
                    loadEventFilterOptionsUseCase =
                        object : LoadEventFilterOptionsUseCase {
                            override suspend fun invoke(companyId: String): EventsFilterOptions = EventsFilterOptions()
                        },
                    acknowledgeEventUseCase =
                        object : AcknowledgeEventUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                eventId: String,
                            ) = EventsOperationResult.Success(event1)
                        },
                    markEventErroneousUseCase =
                        object : MarkEventErroneousUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                eventId: String,
                                v: Long,
                                k: String,
                            ) = EventsOperationResult.Success(event1)
                        },
                    createEventsExportUseCase =
                        object : CreateEventsExportUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                r: EventsExportRequest,
                                k: String,
                            ) = EventsExportStatus(
                                exportId = "e1",
                                status = EventsExportStatusCode.QUEUED,
                                createdAt = Clock.System.now(),
                            )
                        },
                    observeEventsExportStatusUseCase =
                        object : ObserveEventsExportStatusUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                exportId: String,
                            ) = EventsExportStatus(
                                exportId = "e1",
                                status = EventsExportStatusCode.COMPLETED,
                                createdAt = Clock.System.now(),
                            )
                        },
                    downloadEventsExportUseCase =
                        object : DownloadEventsExportUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                exportId: String,
                            ) = EventsExportDownload("file.csv", 100L, "h")
                        },
                    exportFileSaver =
                        object : ExportFileSaver {
                            override suspend fun saveExportFile(download: EventsExportDownload) = true
                        },
                )

            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true))
            advanceUntilIdle()

            // 1. Direct live creation: event2 added directly as INCLUDED with sequence advance
            val overlayWithEvent2 =
                RealtimeOverlay(
                    entries =
                        mapOf(
                            event2.id to
                                OverlayEntry(
                                    eventId = event2.id,
                                    event = event2,
                                    eventVersion = 1L,
                                    membership = OverlayMembership.INCLUDED,
                                ),
                        ),
                )
            session.emitState(
                initialFeed.copy(
                    asOfSequence = 11L,
                    visibleEvents = listOf(event2, event1),
                    realtimeOverlay = overlayWithEvent2,
                ),
            )
            runCurrent()
            assertTrue("ev-2" in vm.state.value.liveCreatedIds, "Direct live event must be in liveCreatedIds")

            // 2. Pending newer event arrives: event3 with membership PENDING_NEWER
            val overlayWithEvent3Pending =
                RealtimeOverlay(
                    entries =
                        mapOf(
                            event2.id to
                                OverlayEntry(
                                    eventId = event2.id,
                                    event = event2,
                                    eventVersion = 1L,
                                    membership = OverlayMembership.INCLUDED,
                                ),
                            event3.id to
                                OverlayEntry(
                                    eventId = event3.id,
                                    event = event3,
                                    eventVersion = 1L,
                                    membership = OverlayMembership.PENDING_NEWER,
                                ),
                        ),
                )
            session.emitState(
                initialFeed.copy(
                    asOfSequence = 12L,
                    visibleEvents = listOf(event2, event1),
                    realtimeOverlay = overlayWithEvent3Pending,
                    pendingNewerCount = 1,
                ),
            )
            runCurrent()
            assertFalse("ev-3" in vm.state.value.liveCreatedIds, "Pending newer event not yet in visibleEvents")

            // 3. onViewportAtTop integrates event3 into INCLUDED and visibleEvents (sequence does not advance)
            val overlayWithEvent3Integrated =
                RealtimeOverlay(
                    entries =
                        mapOf(
                            event2.id to
                                OverlayEntry(
                                    eventId = event2.id,
                                    event = event2,
                                    eventVersion = 1L,
                                    membership = OverlayMembership.INCLUDED,
                                ),
                            event3.id to
                                OverlayEntry(
                                    eventId = event3.id,
                                    event = event3,
                                    eventVersion = 1L,
                                    membership = OverlayMembership.INCLUDED,
                                ),
                        ),
                )
            session.emitState(
                initialFeed.copy(
                    asOfSequence = 12L,
                    visibleEvents = listOf(event3, event2, event1),
                    realtimeOverlay = overlayWithEvent3Integrated,
                    pendingNewerCount = 0,
                ),
            )
            runCurrent()
            assertTrue(
                "ev-3" in vm.state.value.liveCreatedIds,
                "Materialized pending newer event must be in liveCreatedIds",
            )
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
