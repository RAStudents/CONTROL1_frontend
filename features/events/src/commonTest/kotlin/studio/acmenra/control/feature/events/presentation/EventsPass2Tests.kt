package studio.acmenra.control.feature.events.presentation

import kotlinx.coroutines.CompletableDeferred
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
import studio.acmenra.control.core.ui.generated.resources.events_export_download_initiated
import studio.acmenra.control.core.ui.generated.resources.events_export_expired_msg
import studio.acmenra.control.core.ui.generated.resources.events_export_media_links_warning
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.core.ui.notice.NoticeText
import studio.acmenra.control.feature.events.domain.model.CategoryScore
import studio.acmenra.control.feature.events.domain.model.DevicesStatusSummary
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.ScoreTrend
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EventsPass2Tests {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Fake Test Doubles ---

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

        override fun loadNextPage() {}

        override fun refresh() {}

        override fun onViewportAtTop() {}

        override fun markOptimisticAckPending(eventId: String) {}

        override fun clearOptimisticAckPending(eventId: String) {}

        override fun applyAcknowledged(event: Event) {}

        override fun applyEventMutationResult(event: Event) {}

        override fun retryRebootstrap() {}

        override fun updateAccessCapabilities(access: EventsAccessCapabilities) {
            _state.value = _state.value.copy(access = access)
        }

        override fun close() {}
    }

    private class FakeExportFileSaver : ExportFileSaver {
        var savedDownloads = mutableListOf<EventsExportDownload>()
        var shouldSucceed = true

        override suspend fun saveExportFile(download: EventsExportDownload): Boolean {
            if (shouldSucceed) {
                savedDownloads.add(download)
                return true
            }
            return false
        }
    }

    private class FakeCreateEventsExportUseCase(
        var statusToReturn: EventsExportStatus =
            EventsExportStatus(
                exportId = "exp-default",
                status = EventsExportStatusCode.QUEUED,
                createdAt = Instant.parse("2026-03-01T12:00:00Z"),
            ),
    ) : CreateEventsExportUseCase {
        var callCount = 0
        var lastCompanyId: String? = null
        var lastRequest: EventsExportRequest? = null
        var lastIdempotencyKey: String? = null

        override suspend fun invoke(
            companyId: String,
            request: EventsExportRequest,
            idempotencyKey: String,
        ): EventsExportStatus {
            callCount++
            lastCompanyId = companyId
            lastRequest = request
            lastIdempotencyKey = idempotencyKey
            return statusToReturn
        }
    }

    private class FakeObserveEventsExportStatusUseCase(
        var defaultStatus: EventsExportStatusCode = EventsExportStatusCode.PROCESSING,
        var statusesToReturn: MutableList<EventsExportStatus> = mutableListOf(),
    ) : ObserveEventsExportStatusUseCase {
        var callCount = 0

        override suspend fun invoke(
            companyId: String,
            exportId: String,
        ): EventsExportStatus {
            callCount++
            return if (statusesToReturn.isNotEmpty()) {
                statusesToReturn.removeAt(0)
            } else {
                EventsExportStatus(
                    exportId = exportId,
                    status = defaultStatus,
                    createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                )
            }
        }
    }

    private class FakeDownloadEventsExportUseCase : DownloadEventsExportUseCase {
        var callCount = 0
        var shouldFail = false
        var suspendGate: CompletableDeferred<Unit>? = null

        override suspend fun invoke(
            companyId: String,
            exportId: String,
        ): EventsExportDownload {
            callCount++
            suspendGate?.await()
            if (shouldFail) {
                throw IllegalStateException("Download network error")
            }
            return EventsExportDownload(
                fileName = "events_$exportId.csv",
                contentLength = 1024L,
                downloadHandle = "handle_$exportId",
            )
        }
    }

    private fun createViewModel(
        feedSession: FakeFeedSession = FakeFeedSession("comp-1", EventsQuery(), EventsAccessCapabilities()),
        exportSaver: FakeExportFileSaver = FakeExportFileSaver(),
        createExport: CreateEventsExportUseCase = FakeCreateEventsExportUseCase(),
        observeExport: ObserveEventsExportStatusUseCase = FakeObserveEventsExportStatusUseCase(),
        downloadExport: DownloadEventsExportUseCase = FakeDownloadEventsExportUseCase(),
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
        val ack =
            object : AcknowledgeEventUseCase {
                override suspend fun invoke(
                    companyId: String,
                    eventId: String,
                ): EventsOperationResult<Event> =
                    EventsOperationResult.Success(
                        Event(
                            id = eventId,
                            eventVersion = 1L,
                            classificationVersion = 1L,
                            acknowledgementVersion = 1L,
                            eventTypeId = "type-1",
                            eventTypeName = "Test",
                            categoryCode = "SAFETY",
                            iconKey = EventIconKey.GENERIC_ALERT,
                            locationId = "loc-1",
                            locationName = "Loc",
                            deviceId = "dev-1",
                            deviceName = "Dev",
                            occurredAt = Instant.parse("2026-03-01T10:00:00Z"),
                            receivedAt = Instant.parse("2026-03-01T10:00:01Z"),
                            status = EventAcknowledgementStatus.NEW,
                            acknowledgedAt = null,
                            acknowledgedBy = null,
                            isErroneous = false,
                            previewUrl = "https://example.com/p.jpg",
                            videoUrl = null,
                        ),
                    )
            }
        val markErroneous =
            object : MarkEventErroneousUseCase {
                override suspend fun invoke(
                    companyId: String,
                    eventId: String,
                    expectedClassificationVersion: Long,
                    idempotencyKey: String,
                ): EventsOperationResult<Event> =
                    EventsOperationResult.Success(
                        Event(
                            id = eventId,
                            eventVersion = 1L,
                            classificationVersion = 1L,
                            acknowledgementVersion = 1L,
                            eventTypeId = "type-1",
                            eventTypeName = "Test",
                            categoryCode = "SAFETY",
                            iconKey = EventIconKey.GENERIC_ALERT,
                            locationId = "loc-1",
                            locationName = "Loc",
                            deviceId = "dev-1",
                            deviceName = "Dev",
                            occurredAt = Instant.parse("2026-03-01T10:00:00Z"),
                            receivedAt = Instant.parse("2026-03-01T10:00:01Z"),
                            status = EventAcknowledgementStatus.NEW,
                            acknowledgedAt = null,
                            acknowledgedBy = null,
                            isErroneous = true,
                            previewUrl = "https://example.com/p.jpg",
                            videoUrl = null,
                        ),
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

    // --- Section 13.1 Unit Tests for Pass 2 ---

    @Test
    fun testSummaryCategories_TopTwoAndInitialOrder() {
        val cat1 = CategoryScore("c1", "Safety", 95, ScoreTrend.IMPROVING)
        val cat2 = CategoryScore("c2", "Quality", 80, ScoreTrend.WORSENING)
        val cat3 = CategoryScore("c3", "Speed", 70, ScoreTrend.STABLE)
        val cat4 = CategoryScore("c4", "Security", 60, ScoreTrend.UNKNOWN)

        val summary =
            EventsSummary(
                businessTimezone = "Europe/Moscow",
                businessDate = "2026-03-01",
                summaryAsOfSequence = 100L,
                todayEventsCount = 42,
                categories = listOf(cat1, cat2, cat3, cat4),
            )

        // EventsSummaryCard takes at most top 2 categories in original order
        val displayedCategories = summary.categories.take(2)
        assertEquals(2, displayedCategories.size)
        assertEquals(cat1, displayedCategories[0])
        assertEquals(cat2, displayedCategories[1])
        assertEquals(ScoreTrend.IMPROVING, displayedCategories[0].trend)
        assertEquals(ScoreTrend.WORSENING, displayedCategories[1].trend)
    }

    @Test
    fun testSummaryDevices_FormatCompactVsWide() {
        val devices =
            DevicesStatusSummary(
                totalCount = 10,
                onlineOkCount = 7,
                unknownCount = 2,
                errorCount = 1,
            )

        // Wide/Medium format: count/total
        fun formatWide(
            count: Int,
            total: Int,
        ) = "$count/$total"

        // Compact format: count only
        fun formatCompact(count: Int) = count.toString()

        assertEquals("7/10", formatWide(devices.onlineOkCount, devices.totalCount))
        assertEquals("2/10", formatWide(devices.unknownCount, devices.totalCount))
        assertEquals("1/10", formatWide(devices.errorCount, devices.totalCount))

        assertEquals("7", formatCompact(devices.onlineOkCount))
        assertEquals("2", formatCompact(devices.unknownCount))
        assertEquals("1", formatCompact(devices.errorCount))

        // Real server zero is rendered as "0/total" and "0"
        val zeroDevices =
            DevicesStatusSummary(
                totalCount = 5,
                onlineOkCount = 0,
                unknownCount = 0,
                errorCount = 5,
            )
        assertEquals("0/5", formatWide(zeroDevices.onlineOkCount, zeroDevices.totalCount))
        assertEquals("0", formatCompact(zeroDevices.onlineOkCount))

        // Null devices render as neutral dash "—"
        val nullDevices: DevicesStatusSummary? = null
        val nullText = if (nullDevices == null) "—" else "ok"
        assertEquals("—", nullText)
    }

    @Test
    fun testExportProgressCalculation_SafeZeroHandling() {
        // Safe derivedProgressPercent: null records, 0 total, and valid total
        val statusWithNulls =
            EventsExportStatus(
                exportId = "exp-1",
                status = EventsExportStatusCode.PROCESSING,
                createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                totalRecords = null,
                processedRecords = null,
            )
        assertNull(statusWithNulls.derivedProgressPercent)

        val statusWithZeroTotal =
            EventsExportStatus(
                exportId = "exp-2",
                status = EventsExportStatusCode.PROCESSING,
                createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                totalRecords = 0L,
                processedRecords = 0L,
            )
        assertNull(statusWithZeroTotal.derivedProgressPercent)

        val statusWithValidProgress =
            EventsExportStatus(
                exportId = "exp-3",
                status = EventsExportStatusCode.PROCESSING,
                createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                totalRecords = 200L,
                processedRecords = 50L,
            )
        assertEquals(25, statusWithValidProgress.derivedProgressPercent)
    }

    @Test
    fun testExport_ToggleIncludeMediaLinks_EmitsNotice() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))
            advanceUntilIdle()

            assertFalse(vm.state.value.exportIncludeMediaLinks)
            assertNull(vm.state.value.errorNotice)

            // Enabling media links emits Notice.info with warning
            vm.setExportIncludeMediaLinks(true)
            assertTrue(vm.state.value.exportIncludeMediaLinks)
            val notice = vm.state.value.errorNotice
            assertNotNull(notice)
            assertEquals(NoticeText.Resource(Res.string.events_export_media_links_warning), notice.title)

            // Disabling media links does not emit warning notice
            vm.dismissErrorNotice()
            vm.setExportIncludeMediaLinks(false)
            assertFalse(vm.state.value.exportIncludeMediaLinks)
            assertNull(vm.state.value.errorNotice)
        }

    @Test
    fun testExport_DoubleFastClick_BlocksSecondJobSubmission() =
        runTest(testDispatcher) {
            val createExport = FakeCreateEventsExportUseCase()
            val vm = createViewModel(createExport = createExport)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))

            // First click synchronously claims submission
            vm.startExport()
            assertTrue(vm.state.value.isExportSubmitting)

            // Second immediate click while isExportSubmitting is true is rejected
            vm.startExport()

            advanceUntilIdle()
            assertEquals(1, createExport.callCount)
            assertFalse(vm.state.value.isExportSubmitting)
        }

    @Test
    fun testExport_CompletedTriggersAutoDownloadExactlyOnce() =
        runTest(testDispatcher) {
            val completedStatus =
                EventsExportStatus(
                    exportId = "exp-auto",
                    status = EventsExportStatusCode.COMPLETED,
                    createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                )
            val createExport = FakeCreateEventsExportUseCase(statusToReturn = completedStatus)
            val downloadExport = FakeDownloadEventsExportUseCase()
            val saver = FakeExportFileSaver()

            val vm =
                createViewModel(
                    createExport = createExport,
                    downloadExport = downloadExport,
                    exportSaver = saver,
                )
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))

            vm.startExport()
            advanceUntilIdle()

            // Download usecase should be called exactly once
            assertEquals(1, downloadExport.callCount)
            assertEquals(1, saver.savedDownloads.size)
            assertEquals("events_exp-auto.csv", saver.savedDownloads[0].fileName)
            assertEquals(
                NoticeText.Resource(Res.string.events_export_download_initiated),
                vm.state.value.errorNotice
                    ?.title,
            )
            assertFalse(vm.state.value.isExportDownloading)
        }

    @Test
    fun testExport_AutoDownloadFailure_LeavesManualRedownloadAvailable() =
        runTest(testDispatcher) {
            val completedStatus =
                EventsExportStatus(
                    exportId = "exp-fail-auto",
                    status = EventsExportStatusCode.COMPLETED,
                    createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                    expiresAt = Clock.System.now() + 1.hours,
                    downloadUrl = "https://example.com/exp-fail-auto.csv",
                )
            val createExport = FakeCreateEventsExportUseCase(statusToReturn = completedStatus)
            val downloadExport = FakeDownloadEventsExportUseCase().apply { shouldFail = true }
            val saver = FakeExportFileSaver()

            val vm =
                createViewModel(
                    createExport = createExport,
                    downloadExport = downloadExport,
                    exportSaver = saver,
                )
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))

            vm.startExport()
            advanceUntilIdle()

            // Failed auto-download
            assertEquals(1, downloadExport.callCount)
            assertEquals(0, saver.savedDownloads.size)
            assertFalse(vm.state.value.isExportDownloading)
            assertEquals(
                EventsExportStatusCode.COMPLETED,
                vm.state.value.exportJobStatus
                    ?.status,
            )

            // Now manual retry succeeds
            downloadExport.shouldFail = false
            vm.downloadExport("exp-fail-auto")
            advanceUntilIdle()

            assertEquals(2, downloadExport.callCount)
            assertEquals(1, saver.savedDownloads.size)
            assertEquals(
                NoticeText.Resource(Res.string.events_export_download_initiated),
                vm.state.value.errorNotice
                    ?.title,
            )
        }

    @Test
    fun testExport_ManualDownload_BlockedWhenAlreadyDownloading() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val downloadExport = FakeDownloadEventsExportUseCase().apply { suspendGate = gate }
            val saver = FakeExportFileSaver()
            val vm =
                createViewModel(
                    downloadExport = downloadExport,
                    exportSaver = saver,
                )
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))

            // Start first download
            vm.downloadExport("exp-manual")
            testScheduler.runCurrent()

            // Coroutine has started and is suspended inside download use case
            assertTrue(vm.state.value.isExportDownloading)
            assertEquals(1, downloadExport.callCount)

            // Second manual download call while first is in-flight must be rejected
            vm.downloadExport("exp-manual")
            testScheduler.runCurrent()
            assertEquals(1, downloadExport.callCount)

            // Release the gate and let first download complete
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, downloadExport.callCount)
            assertFalse(vm.state.value.isExportDownloading)
            assertEquals(1, saver.savedDownloads.size)
        }

    @Test
    fun testExport_ExpiredJob_DisablesManualDownloadAndEmitsNotice() =
        runTest(testDispatcher) {
            val downloadExport = FakeDownloadEventsExportUseCase()
            val vm = createViewModel(downloadExport = downloadExport)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))

            // Job expired 2 hours ago
            val pastInstant =
                kotlin.time.Clock.System
                    .now() - 2.hours
            val expiredJob =
                EventsExportStatus(
                    exportId = "exp-past",
                    status = EventsExportStatusCode.COMPLETED,
                    createdAt = pastInstant - 1.hours,
                    expiresAt = pastInstant,
                )
            val createExport = FakeCreateEventsExportUseCase(statusToReturn = expiredJob)
            val vmWithExpired = createViewModel(createExport = createExport, downloadExport = downloadExport)
            vmWithExpired.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))

            // Set state to have expired status
            vmWithExpired.startExport()
            advanceUntilIdle()

            // Reset call count because startExport might have triggered autoDownload
            val initialDownloadCalls = downloadExport.callCount

            // Manual redownload attempt
            vmWithExpired.downloadExport("exp-past")
            advanceUntilIdle()

            // Download usecase must NOT have been called for expired job
            assertEquals(initialDownloadCalls, downloadExport.callCount)
            assertEquals(
                EventsExportStatusCode.EXPIRED,
                vmWithExpired.state.value.exportJobStatus
                    ?.status,
            )
            assertEquals(
                NoticeText.Resource(Res.string.events_export_expired_msg),
                vmWithExpired.state.value.errorNotice
                    ?.title,
            )
        }

    @Test
    fun testExport_PermissionRevocation_ClearsStateAndCancelsJobs() =
        runTest(testDispatcher) {
            val pendingJob =
                EventsExportStatus(
                    exportId = "exp-pending",
                    status = EventsExportStatusCode.PROCESSING,
                    createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                )
            val createExport = FakeCreateEventsExportUseCase(statusToReturn = pendingJob)
            val vm = createViewModel(createExport = createExport)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))

            vm.startExport()
            advanceUntilIdle()

            assertEquals(
                EventsExportStatusCode.PROCESSING,
                vm.state.value.exportJobStatus
                    ?.status,
            )

            // Revoke REPORTS_EXPORT capability
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = false))
            advanceUntilIdle()

            assertNull(vm.state.value.exportJobStatus)
            assertFalse(vm.state.value.isExportModeActive)
            assertFalse(vm.state.value.isExportSubmitting)
            assertFalse(vm.state.value.isExportDownloading)
        }

    @Test
    fun testExport_CompanySwitch_ClearsExportState() =
        runTest(testDispatcher) {
            val pendingJob =
                EventsExportStatus(
                    exportId = "exp-c1",
                    status = EventsExportStatusCode.PROCESSING,
                    createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                )
            val createExport = FakeCreateEventsExportUseCase(statusToReturn = pendingJob)
            val vm = createViewModel(createExport = createExport)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))

            vm.startExport()
            advanceUntilIdle()
            assertEquals("comp-1", vm.state.value.companyId)
            assertNotNull(vm.state.value.exportJobStatus)

            // Switch to comp-2
            vm.initialize("comp-2", EventsAccessCapabilities(eventsRead = true, reportsExport = true))
            advanceUntilIdle()

            assertEquals("comp-2", vm.state.value.companyId)
            assertNull(vm.state.value.exportJobStatus)
            assertFalse(vm.state.value.isExportSubmitting)
            assertFalse(vm.state.value.isExportDownloading)
        }

    @Test
    fun testExport_ModeSwitchPreservesJobState() =
        runTest(testDispatcher) {
            val pendingJob =
                EventsExportStatus(
                    exportId = "exp-preserve",
                    status = EventsExportStatusCode.PROCESSING,
                    createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                )
            val createExport = FakeCreateEventsExportUseCase(statusToReturn = pendingJob)
            val vm = createViewModel(createExport = createExport)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))

            vm.startExport()
            advanceUntilIdle()

            assertEquals(
                EventsExportStatusCode.PROCESSING,
                vm.state.value.exportJobStatus
                    ?.status,
            )
            assertFalse(vm.state.value.isExportModeActive)

            // Toggle to Export mode
            vm.toggleExportMode()
            assertTrue(vm.state.value.isExportModeActive)
            assertEquals(
                "exp-preserve",
                vm.state.value.exportJobStatus
                    ?.exportId,
            )

            // Toggle back to Statistics mode
            vm.toggleExportMode()
            assertFalse(vm.state.value.isExportModeActive)
            assertEquals(
                "exp-preserve",
                vm.state.value.exportJobStatus
                    ?.exportId,
            )
        }

    @Test
    fun testExportSettings_PresetsAndFormat() {
        val vm = createViewModel()
        assertEquals(EventsExportFormatPreset.EXCEL_SEMICOLON_BOM, vm.state.value.exportFormatPreset)

        vm.setExportFormatPreset(EventsExportFormatPreset.STANDARD_COMMA_UTF8)
        assertEquals(EventsExportFormatPreset.STANDARD_COMMA_UTF8, vm.state.value.exportFormatPreset)

        vm.setExportFormatPreset(EventsExportFormatPreset.EXCEL_SEMICOLON_BOM)
        assertEquals(EventsExportFormatPreset.EXCEL_SEMICOLON_BOM, vm.state.value.exportFormatPreset)
    }
}
