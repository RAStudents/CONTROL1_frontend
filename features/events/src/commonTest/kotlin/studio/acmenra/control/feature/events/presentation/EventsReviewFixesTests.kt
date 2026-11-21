package studio.acmenra.control.feature.events.presentation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.datetime.TimeZone
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import studio.acmenra.control.core.network.client.ApiConfig
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.KtorEventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.KtorEventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.MockEventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.MockEventsWebSocketDataSource
import studio.acmenra.control.feature.events.di.eventsMockModule
import studio.acmenra.control.feature.events.di.eventsModule
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.domain.model.DeviceFilterOption
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventTypeOption
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsMutationError
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
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

    private fun createTestViewModel(feedSession: TestFeedSession = TestFeedSession()): EventsViewModel {
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
        val createExport =
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
        val observeExportStatus =
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
        val downloadExport =
            object : DownloadEventsExportUseCase {
                override suspend fun invoke(
                    companyId: String,
                    exportId: String,
                ): EventsExportDownload = EventsExportDownload("file.csv", 100L, "h-1")
            }
        val exportSaver =
            object : ExportFileSaver {
                override suspend fun saveExportFile(download: EventsExportDownload): Boolean = true
            }

        return EventsViewModel(
            observeEventsFeedUseCase = observeFeed,
            loadEventFilterOptionsUseCase = loadOptions,
            acknowledgeEventUseCase = ack,
            markEventErroneousUseCase = markErroneous,
            createEventsExportUseCase = createExport,
            observeEventsExportStatusUseCase = observeExportStatus,
            downloadEventsExportUseCase = downloadExport,
            exportFileSaver = exportSaver,
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
}
