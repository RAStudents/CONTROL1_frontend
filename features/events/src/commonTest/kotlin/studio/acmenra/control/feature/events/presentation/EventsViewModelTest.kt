package studio.acmenra.control.feature.events.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_mutation_network_error
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventValidity
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsQuery
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EventsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeFeedSession(
        override val companyId: String,
        override val query: EventsQuery,
        initialAccess: EventsAccessCapabilities,
        initialFeedState: EventsFeedState = EventsFeedState(access = initialAccess),
    ) : EventsFeedSession {
        private val _state = MutableStateFlow(initialFeedState)
        override val state: StateFlow<EventsFeedState> = _state.asStateFlow()

        val noticeFlow = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
        override val connectionNotice: Flow<Notice> = noticeFlow.asSharedFlow()

        val filterInvalidatedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        override val filterOptionsInvalidated: Flow<Unit> = filterInvalidatedFlow.asSharedFlow()

        var loadNextPageCalled = false
        var refreshCalled = false
        var closed = false
        val pendingAcks = mutableSetOf<String>()

        override fun loadNextPage() {
            loadNextPageCalled = true
        }

        override fun refresh() {
            refreshCalled = true
        }

        override fun onViewportAtTop() {}

        override fun markOptimisticAckPending(eventId: String) {
            pendingAcks.add(eventId)
        }

        override fun clearOptimisticAckPending(eventId: String) {
            pendingAcks.remove(eventId)
        }

        val appliedAcks = mutableListOf<Event>()

        override fun applyAcknowledged(event: Event) {
            appliedAcks.add(event)
            pendingAcks.remove(event.id)
        }

        override fun applyEventMutationResult(event: Event) {
            appliedAcks.add(event)
        }

        override fun retryRebootstrap() {}

        override fun updateAccessCapabilities(access: EventsAccessCapabilities) {
            _state.value = _state.value.copy(access = access)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeExportFileSaver : ExportFileSaver {
        var saved = false

        override suspend fun saveExportFile(download: EventsExportDownload): Boolean {
            saved = true
            return true
        }
    }

    private fun createViewModel(
        feedSession: FakeFeedSession = FakeFeedSession("comp-1", EventsQuery(), EventsAccessCapabilities()),
        exportSaver: FakeExportFileSaver = FakeExportFileSaver(),
        ackUseCase: AcknowledgeEventUseCase =
            object : AcknowledgeEventUseCase {
                override suspend fun invoke(
                    companyId: String,
                    eventId: String,
                ): EventsOperationResult<Event> = EventsOperationResult.Success(createSampleEvent(eventId))
            },
    ): EventsViewModel {
        val observeFeed =
            object : ObserveEventsFeedUseCase {
                override fun invoke(
                    companyId: String,
                    query: EventsQuery,
                    accessCapabilities: EventsAccessCapabilities,
                ): EventsFeedSession {
                    feedSession.updateAccessCapabilities(accessCapabilities)
                    return feedSession
                }
            }
        val loadOptions =
            object : LoadEventFilterOptionsUseCase {
                override suspend fun invoke(companyId: String): EventsFilterOptions =
                    EventsFilterOptions(revision = "rev-1")
            }
        val ack = ackUseCase
        val markErroneous =
            object : MarkEventErroneousUseCase {
                override suspend fun invoke(
                    companyId: String,
                    eventId: String,
                    expectedClassificationVersion: Long,
                    idempotencyKey: String,
                ): EventsOperationResult<Event> =
                    EventsOperationResult.Success(createSampleEvent(eventId).copy(isErroneous = true))
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
                        createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                    )
            }
        val observeExport =
            object : ObserveEventsExportStatusUseCase {
                override suspend fun invoke(
                    companyId: String,
                    exportId: String,
                ): EventsExportStatus =
                    EventsExportStatus(
                        exportId = exportId,
                        status = EventsExportStatusCode.COMPLETED,
                        createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                    )
            }
        val downloadExport =
            object : DownloadEventsExportUseCase {
                override suspend fun invoke(
                    companyId: String,
                    exportId: String,
                ): EventsExportDownload =
                    EventsExportDownload(
                        fileName = "export.csv",
                        contentLength = 3L,
                        downloadHandle = "handle-$exportId",
                    )
            }

        return EventsViewModel(
            observeEventsFeedUseCase = observeFeed,
            loadEventFilterOptionsUseCase = loadOptions,
            acknowledgeEventUseCase = ack,
            markEventErroneousUseCase = markErroneous,
            createEventsExportUseCase = createExport,
            observeEventsExportStatusUseCase = observeExport,
            downloadEventsExportUseCase = downloadExport,
            exportFileSaver = exportSaver,
        )
    }

    @Test
    fun testExportModeToggle() {
        val vm = createViewModel()
        vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))
        assertFalse(vm.state.value.isExportModeActive)

        vm.toggleExportMode()
        assertTrue(vm.state.value.isExportModeActive)

        vm.toggleExportMode()
        assertFalse(vm.state.value.isExportModeActive)
    }

    @Test
    fun testSelectEvent() {
        val vm = createViewModel()
        assertNull(vm.state.value.selectedEventId)
        assertFalse(vm.state.value.isViewerOpenInCompact)

        vm.selectEvent("ev-123")
        assertEquals("ev-123", vm.state.value.selectedEventId)
        assertTrue(vm.state.value.isViewerOpenInCompact)

        vm.closeViewerCompact()
        assertFalse(vm.state.value.isViewerOpenInCompact)
    }

    @Test
    fun testValidityFilterChange() {
        val vm = createViewModel()
        vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))

        assertEquals(EventValidity.ALL, vm.state.value.filter.validity)
        vm.onValidityChanged(EventValidity.VALID)
        assertEquals(EventValidity.VALID, vm.state.value.filter.validity)
    }

    @Test
    fun testResetFilters() {
        val vm = createViewModel()
        vm.initialize("comp-1", EventsAccessCapabilities())

        vm.onEventTypeToggled("helmet")
        vm.onDeviceToggled("cam-1")
        vm.onValidityChanged(EventValidity.ERRONEOUS)
        assertTrue(vm.state.value.filter.hasActiveFilters)

        vm.resetFilters()
        assertFalse(vm.state.value.filter.hasActiveFilters)
        assertEquals(EventValidity.ALL, vm.state.value.filter.validity)
    }

    @Test
    fun testMarkErroneousFlow() =
        runTest(testDispatcher) {
            val sampleEvent = createSampleEvent("ev-1")
            val feed =
                FakeFeedSession(
                    companyId = "comp-1",
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                    initialFeedState =
                        EventsFeedState(
                            visibleEvents = listOf(sampleEvent),
                            access = EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                        ),
                )
            val vm = createViewModel(feedSession = feed)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))
            advanceUntilIdle()

            assertNull(vm.state.value.erroneousConfirmationEventId)
            vm.requestMarkErroneous("ev-1")
            assertEquals("ev-1", vm.state.value.erroneousConfirmationEventId)

            vm.cancelMarkErroneous()
            assertNull(vm.state.value.erroneousConfirmationEventId)

            vm.requestMarkErroneous(sampleEvent)
            vm.confirmMarkErroneous("ev-1", expectedVersion = 1L)
            assertNull(vm.state.value.erroneousConfirmationEventId)
            advanceUntilIdle()
            assertFalse(vm.state.value.isMarkingErroneous)
        }

    @Test
    fun testAcknowledgeEventSuccessUpdatesFeed() =
        runTest(testDispatcher) {
            val feed =
                FakeFeedSession(
                    "comp-1",
                    EventsQuery(),
                    EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                )
            val vm = createViewModel(feedSession = feed)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))

            val event = createSampleEvent("ev-100")
            vm.acknowledgeEvent(event)
            assertTrue(feed.pendingAcks.contains("ev-100"))
            advanceUntilIdle()
            assertEquals(listOf(event), feed.appliedAcks)
            assertFalse(feed.pendingAcks.contains("ev-100"))
            assertNull(vm.state.value.mutationErrorNotice)
        }

    @Test
    fun testAcknowledgeEventFailureRollsBackOptimisticAck() =
        runTest(testDispatcher) {
            val feed =
                FakeFeedSession(
                    "comp-1",
                    EventsQuery(),
                    EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                )
            val failingAck =
                object : AcknowledgeEventUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        eventId: String,
                    ): EventsOperationResult<Event> =
                        EventsOperationResult.Failure(
                            studio.acmenra.control.feature.events.domain.model.EventsMutationError.Network,
                        )
                }
            val vm = createViewModel(feedSession = feed, ackUseCase = failingAck)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))

            val event = createSampleEvent("ev-100")
            vm.acknowledgeEvent(event)
            assertTrue(feed.pendingAcks.contains("ev-100"))
            advanceUntilIdle()
            assertFalse(feed.pendingAcks.contains("ev-100"))
            assertEquals(Res.string.events_mutation_network_error, vm.state.value.mutationErrorNotice)
        }

    @Test
    fun testSelectEventAutoAcknowledgesNewEvent() =
        runTest(testDispatcher) {
            val event = createSampleEvent("ev-new")
            val feed =
                FakeFeedSession(
                    "comp-1",
                    EventsQuery(),
                    EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                    initialFeedState =
                        EventsFeedState(
                            visibleEvents = listOf(event),
                            access = EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                        ),
                )
            var acknowledgedEventId: String? = null
            val ackUseCase =
                object : AcknowledgeEventUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        eventId: String,
                    ): EventsOperationResult<Event> {
                        acknowledgedEventId = eventId
                        return EventsOperationResult.Success(
                            event.copy(status = EventAcknowledgementStatus.ACKNOWLEDGED),
                        )
                    }
                }
            val vm = createViewModel(feedSession = feed, ackUseCase = ackUseCase)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))
            advanceUntilIdle()

            vm.selectEvent("ev-new")
            advanceUntilIdle()

            assertEquals("ev-new", acknowledgedEventId)
            assertEquals("ev-new", feed.appliedAcks.firstOrNull()?.id)
            assertFalse(feed.pendingAcks.contains("ev-new"))
        }

    @Test
    fun testSelectEventDoesNotAcknowledgeAlreadyAcknowledged() =
        runTest(testDispatcher) {
            val event = createSampleEvent("ev-ack").copy(status = EventAcknowledgementStatus.ACKNOWLEDGED)
            val feed =
                FakeFeedSession(
                    "comp-1",
                    EventsQuery(),
                    EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                    initialFeedState =
                        EventsFeedState(
                            visibleEvents = listOf(event),
                            access = EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                        ),
                )
            var acknowledgedEventId: String? = null
            val ackUseCase =
                object : AcknowledgeEventUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        eventId: String,
                    ): EventsOperationResult<Event> {
                        acknowledgedEventId = eventId
                        return EventsOperationResult.Success(event)
                    }
                }
            val vm = createViewModel(feedSession = feed, ackUseCase = ackUseCase)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))
            advanceUntilIdle()

            vm.selectEvent("ev-ack")
            advanceUntilIdle()

            assertNull(acknowledgedEventId)
            assertFalse(feed.pendingAcks.contains("ev-ack"))
        }

    @Test
    fun testExportFlow() =
        runTest(testDispatcher) {
            val saver = FakeExportFileSaver()
            val vm = createViewModel(exportSaver = saver)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))

            vm.startExport()
            advanceUntilIdle()

            assertNotNull(vm.state.value.exportJobStatus)
            assertEquals(
                EventsExportStatusCode.COMPLETED,
                vm.state.value.exportJobStatus
                    ?.status,
            )

            vm.downloadExport("exp-1")
            advanceUntilIdle()
            assertTrue(saver.saved)
        }

    @Test
    fun testExportSettingsUpdates() {
        val vm = createViewModel()
        assertEquals(EventsExportFormatPreset.EXCEL_SEMICOLON_BOM, vm.state.value.exportFormatPreset)
        assertFalse(vm.state.value.exportIncludeMediaLinks)

        vm.setExportFormatPreset(EventsExportFormatPreset.STANDARD_COMMA_UTF8)
        assertEquals(EventsExportFormatPreset.STANDARD_COMMA_UTF8, vm.state.value.exportFormatPreset)

        vm.setExportIncludeMediaLinks(true)
        assertTrue(vm.state.value.exportIncludeMediaLinks)
    }

    private companion object {
        fun createSampleEvent(id: String) =
            Event(
                id = id,
                eventVersion = 1L,
                classificationVersion = 1L,
                acknowledgementVersion = 1L,
                eventTypeId = "type-1",
                eventTypeName = "Test Event",
                categoryCode = "SAFETY",
                iconKey = EventIconKey.GENERIC_ALERT,
                locationId = "loc-1",
                locationName = "Location 1",
                deviceId = "dev-1",
                deviceName = "Device 1",
                occurredAt = Instant.parse("2026-03-01T10:00:00Z"),
                receivedAt = Instant.parse("2026-03-01T10:00:01Z"),
                status = EventAcknowledgementStatus.NEW,
                acknowledgedAt = null,
                acknowledgedBy = null,
                isErroneous = false,
                previewUrl = "https://cdn.example.com/p.jpg",
                videoUrl = null,
            )
    }
}
