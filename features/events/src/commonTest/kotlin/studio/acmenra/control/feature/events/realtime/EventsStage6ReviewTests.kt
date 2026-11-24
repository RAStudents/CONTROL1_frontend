package studio.acmenra.control.feature.events.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.TokenPair
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.FilterOptionsFetchResult
import studio.acmenra.control.feature.events.data.datasource.IncomingWsEnvelope
import studio.acmenra.control.feature.events.data.datasource.WsCloseReason
import studio.acmenra.control.feature.events.data.model.BootstrapEventsDto
import studio.acmenra.control.feature.events.data.model.CreateExportRequestDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventsBootstrapDto
import studio.acmenra.control.feature.events.data.model.EventsExportStatusDto
import studio.acmenra.control.feature.events.data.model.EventsPageDto
import studio.acmenra.control.feature.events.data.model.EventsPaginationDto
import studio.acmenra.control.feature.events.data.model.EventsSummaryDto
import studio.acmenra.control.feature.events.data.realtime.BootstrapApplyResult
import studio.acmenra.control.feature.events.data.realtime.CommandDispatchResult
import studio.acmenra.control.feature.events.data.realtime.EventsFeedSessionImpl
import studio.acmenra.control.feature.events.data.realtime.EventsSessionProcessor
import studio.acmenra.control.feature.events.data.realtime.RealtimeOverlayReducer
import studio.acmenra.control.feature.events.data.realtime.SessionCommand
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsBootstrap
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsMutationError
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsPage
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.RealtimeEventOrigin
import studio.acmenra.control.feature.events.domain.model.RealtimeOverlay
import studio.acmenra.control.feature.events.domain.repository.DeltaDeliveryState
import studio.acmenra.control.feature.events.domain.repository.EventsConnectivityProvider
import studio.acmenra.control.feature.events.domain.repository.EventsFeedDelta
import studio.acmenra.control.feature.events.domain.repository.EventsFeedSession
import studio.acmenra.control.feature.events.domain.repository.EventsFeedState
import studio.acmenra.control.feature.events.domain.repository.FlushFailureReason
import studio.acmenra.control.feature.events.domain.repository.HistoryRefreshResult
import studio.acmenra.control.feature.events.domain.repository.PendingNewerFlushResult
import studio.acmenra.control.feature.events.domain.repository.appendFeedDeltaChecked
import studio.acmenra.control.feature.events.domain.usecase.AcknowledgeEventUseCase
import studio.acmenra.control.feature.events.domain.usecase.CreateEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.DownloadEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.LoadEventFilterOptionsUseCase
import studio.acmenra.control.feature.events.domain.usecase.MarkEventErroneousUseCase
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsExportStatusUseCase
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsFeedUseCase
import studio.acmenra.control.feature.events.presentation.EventsViewModel
import studio.acmenra.control.feature.events.presentation.components.LiveEventPhase
import studio.acmenra.control.feature.events.presentation.components.LiveEventPresentationEntry
import studio.acmenra.control.feature.events.presentation.components.LiveEventTrackerReducer
import studio.acmenra.control.feature.events.presentation.components.LiveEventTrackerState
import studio.acmenra.control.feature.events.presentation.components.extractRemovedEventBatches
import studio.acmenra.control.feature.events.presentation.platform.ExportFileSaver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EventsStage6ReviewTests {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleEvent(
        id: String,
        version: Long = 1L,
        status: EventAcknowledgementStatus = EventAcknowledgementStatus.NEW,
    ): Event =
        Event(
            id = id,
            eventVersion = version,
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
            status = status,
            acknowledgedAt = null,
            acknowledgedBy = null,
            isErroneous = false,
            previewUrl = "https://example.com/preview.jpg",
            videoUrl = null,
        )

    // =========================================================================
    // 1. RealtimeOverlayReducer: origin preservation & delta invariants
    // =========================================================================

    @Test
    fun testRealtimeOverlayReducer_PreservesOriginUponSubsequentUpdates() {
        val query = EventsQuery()
        val eventInitial = sampleEvent("ev-100", version = 1L)

        // Initial creation with isEventCreated = true -> origin should be CREATED
        val (overlay1, _) =
            RealtimeOverlayReducer.applyEventCommit(
                currentOverlay = RealtimeOverlay(),
                event = eventInitial,
                query = query,
                snapshotItems = emptyList(),
                isEventCreated = true,
            )
        val entry1 = overlay1.entries["ev-100"]
        assertNotNull(entry1)
        assertEquals(RealtimeEventOrigin.CREATED, entry1.origin)

        // Subsequent update of the same event with isEventCreated = false
        val eventUpdated =
            eventInitial.copy(
                eventVersion = 2L,
                status = EventAcknowledgementStatus.ACKNOWLEDGED,
            )
        val (overlay2, _) =
            RealtimeOverlayReducer.applyEventCommit(
                currentOverlay = overlay1,
                event = eventUpdated,
                query = query,
                snapshotItems = emptyList(),
                isEventCreated = false,
            )
        val entry2 = overlay2.entries["ev-100"]
        assertNotNull(entry2)
        // Must preserve existing CREATED origin!
        assertEquals(RealtimeEventOrigin.CREATED, entry2.origin)

        // Unknown initial entry with isEventCreated = false -> origin should be UPDATED
        val eventOther = sampleEvent("ev-200", version = 1L)
        val (overlayOther, _) =
            RealtimeOverlayReducer.applyEventCommit(
                currentOverlay = RealtimeOverlay(),
                event = eventOther,
                query = query,
                snapshotItems = emptyList(),
                isEventCreated = false,
            )
        val entryOther = overlayOther.entries["ev-200"]
        assertNotNull(entryOther)
        assertEquals(RealtimeEventOrigin.UPDATED, entryOther.origin)
    }

    @Test
    fun testEventsFeedDelta_InvariantsValidation() {
        // Valid delta
        val valid =
            EventsFeedDelta(
                batchId = 1L,
                tenantSequence = 10L,
                createdEventIds = listOf("e1"),
                updatedEventIds = listOf("e2"),
                removedEventIds = listOf("e3"),
            )
        assertEquals(1L, valid.batchId)

        // batchId <= 0 must fail
        assertFailsWith<IllegalArgumentException> {
            EventsFeedDelta(batchId = 0L, tenantSequence = 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            EventsFeedDelta(batchId = -1L, tenantSequence = 1L)
        }

        // tenantSequence < 0 must fail
        assertFailsWith<IllegalArgumentException> {
            EventsFeedDelta(batchId = 1L, tenantSequence = -1L)
        }

        // Internal duplicates must fail
        assertFailsWith<IllegalArgumentException> {
            EventsFeedDelta(batchId = 1L, tenantSequence = 1L, createdEventIds = listOf("e1", "e1"))
        }
        assertFailsWith<IllegalArgumentException> {
            EventsFeedDelta(batchId = 1L, tenantSequence = 1L, updatedEventIds = listOf("e2", "e2"))
        }
        assertFailsWith<IllegalArgumentException> {
            EventsFeedDelta(batchId = 1L, tenantSequence = 1L, removedEventIds = listOf("e3", "e3"))
        }

        // Overlapping lists must fail
        assertFailsWith<IllegalArgumentException> {
            EventsFeedDelta(
                batchId = 1L,
                tenantSequence = 1L,
                createdEventIds = listOf("e1"),
                removedEventIds = listOf("e1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EventsFeedDelta(
                batchId = 1L,
                tenantSequence = 1L,
                createdEventIds = listOf("e1"),
                updatedEventIds = listOf("e1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EventsFeedDelta(
                batchId = 1L,
                tenantSequence = 1L,
                updatedEventIds = listOf("e1"),
                removedEventIds = listOf("e1"),
            )
        }
    }

    // =========================================================================
    // 2. appendFeedDeltaChecked: overflow scenarios & 100 deltas cap
    // =========================================================================

    @Test
    fun testAppendFeedDeltaChecked_UnderCapRemainsHealthy() {
        var pending = emptyList<EventsFeedDelta>()
        var delivery: DeltaDeliveryState = DeltaDeliveryState.Healthy

        for (i in 1L..100L) {
            val delta = EventsFeedDelta(batchId = i, tenantSequence = i, createdEventIds = listOf("e$i"))
            val result = appendFeedDeltaChecked(pending, delivery, delta)
            pending = result.pendingDeltas
            delivery = result.deliveryState
        }

        assertEquals(100, pending.size)
        assertEquals(DeltaDeliveryState.Healthy, delivery)
        assertEquals(1L, pending.first().batchId)
        assertEquals(100L, pending.last().batchId)
    }

    @Test
    fun testAppendFeedDeltaChecked_OverflowsAt101AndTracksDroppedBatches() {
        var pending = emptyList<EventsFeedDelta>()
        var delivery: DeltaDeliveryState = DeltaDeliveryState.Healthy

        for (i in 1L..100L) {
            val delta = EventsFeedDelta(batchId = i, tenantSequence = i, createdEventIds = listOf("e$i"))
            val result = appendFeedDeltaChecked(pending, delivery, delta)
            pending = result.pendingDeltas
            delivery = result.deliveryState
        }

        // Adding 101st delta triggers overflow
        val delta101 = EventsFeedDelta(batchId = 101L, tenantSequence = 101L, createdEventIds = listOf("e101"))
        val overflowResult = appendFeedDeltaChecked(pending, delivery, delta101)

        assertEquals(100, overflowResult.pendingDeltas.size)
        assertEquals(2L, overflowResult.pendingDeltas.first().batchId)
        assertEquals(101L, overflowResult.pendingDeltas.last().batchId)

        assertIs<DeltaDeliveryState.Overflowed>(overflowResult.deliveryState)
        val overflowState = overflowResult.deliveryState as DeltaDeliveryState.Overflowed
        assertEquals(1L, overflowState.firstMissingBatchId)
        assertEquals(101L, overflowState.latestBatchId)

        // Adding 102nd delta while already in Overflowed state
        val delta102 = EventsFeedDelta(batchId = 102L, tenantSequence = 102L, createdEventIds = listOf("e102"))
        val nextResult =
            appendFeedDeltaChecked(overflowResult.pendingDeltas, overflowResult.deliveryState, delta102)

        assertEquals(100, nextResult.pendingDeltas.size)
        assertEquals(3L, nextResult.pendingDeltas.first().batchId)
        assertEquals(102L, nextResult.pendingDeltas.last().batchId)

        assertIs<DeltaDeliveryState.Overflowed>(nextResult.deliveryState)
        val nextOverflow = nextResult.deliveryState as DeltaDeliveryState.Overflowed
        // firstMissingBatchId should stay the minimum dropped batch ID (1L)
        assertEquals(1L, nextOverflow.firstMissingBatchId)
        assertEquals(102L, nextOverflow.latestBatchId)
    }

    // =========================================================================
    // 3. extractRemovedEventBatches
    // =========================================================================

    @Test
    fun testExtractRemovedEventBatches_MapsCorrectBatchIds() {
        // Empty liveRemovedIds returns empty map
        val emptyRes =
            extractRemovedEventBatches(
                pendingPresentationDeltas =
                    listOf(
                        EventsFeedDelta(batchId = 1L, tenantSequence = 1L, removedEventIds = listOf("r1")),
                    ),
                liveRemovedIds = emptySet(),
                liveBatchId = 5L,
            )
        assertTrue(emptyRes.isEmpty())

        // Matching removed events map to their batch IDs
        val deltas =
            listOf(
                EventsFeedDelta(batchId = 10L, tenantSequence = 10L, removedEventIds = listOf("rem-1")),
                EventsFeedDelta(batchId = 20L, tenantSequence = 20L, removedEventIds = listOf("rem-2", "rem-3")),
            )
        val result =
            extractRemovedEventBatches(
                pendingPresentationDeltas = deltas,
                liveRemovedIds = setOf("rem-1", "rem-2", "rem-unbatched"),
                liveBatchId = 30L,
            )

        assertEquals(10L, result["rem-1"])
        assertEquals(20L, result["rem-2"])
        // rem-unbatched not in deltas falls back to liveBatchId
        assertEquals(30L, result["rem-unbatched"])
        assertFalse(result.containsKey("rem-3")) // rem-3 not in liveRemovedIds
    }

    // =========================================================================
    // 4. LiveEventTrackerReducer: phase transitions, expanding keys, 200 cap
    // =========================================================================

    @Test
    fun testLiveEventTrackerReducer_TransitionsAndExpandingKeys() {
        var tracker = LiveEventTrackerState()
        val delta =
            EventsFeedDelta(
                batchId = 1L,
                createdEventIds = listOf("e-visible", "e-expanding", "e-offscreen"),
            )
        tracker = LiveEventTrackerReducer.registerBatch(tracker, delta)

        assertEquals(3, tracker.entries.size)
        assertTrue(tracker.entries.all { it.phase == LiveEventPhase.AWAITING_LAYOUT })

        // Reconcile: e-visible is in viewport, e-expanding is expanding in viewport, e-offscreen is not
        tracker =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = tracker,
                actuallyVisibleKeys = setOf("e-visible"),
                expandingInViewportIds = setOf("e-expanding"),
            )

        val map = tracker.entries.associate { it.eventId to it.phase }
        assertEquals(LiveEventPhase.ENTERING, map["e-visible"])
        assertEquals(LiveEventPhase.AWAITING_LAYOUT, map["e-expanding"])
        assertEquals(LiveEventPhase.OFFSCREEN_PENDING, map["e-offscreen"])

        // When e-expanding becomes actually visible
        tracker =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = tracker,
                actuallyVisibleKeys = setOf("e-visible", "e-expanding"),
            )
        val map2 = tracker.entries.associate { it.eventId to it.phase }
        assertEquals(LiveEventPhase.ENTERING, map2["e-visible"])
        assertEquals(LiveEventPhase.ENTERING, map2["e-expanding"])
        assertEquals(LiveEventPhase.OFFSCREEN_PENDING, map2["e-offscreen"])

        // Enter animation finishes while visible -> PRESENTED
        tracker =
            LiveEventTrackerReducer.enterAnimationFinished(
                state = tracker,
                eventId = "e-visible",
                batchId = 1L,
                isCurrentlyVisible = true,
            )
        val entryVisible = tracker.entries.first { it.eventId == "e-visible" }
        assertEquals(LiveEventPhase.PRESENTED, entryVisible.phase)

        // If e-offscreen becomes visible later -> transitions directly to PRESENTED
        tracker =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = tracker,
                actuallyVisibleKeys = setOf("e-offscreen"),
            )
        val entryOffscreen = tracker.entries.first { it.eventId == "e-offscreen" }
        assertEquals(LiveEventPhase.PRESENTED, entryOffscreen.phase)
    }

    @Test
    fun testLiveEventTrackerReducer_CapsPresentedEntriesAt200Fifo() {
        // Create 205 presented entries + 1 offscreen pending entry
        val presented =
            (1..205).map { i ->
                LiveEventPresentationEntry(
                    eventId = "ev-$i",
                    batchId = i.toLong(),
                    batchOrder = i,
                    phase = LiveEventPhase.PRESENTED,
                )
            }
        val offscreen =
            LiveEventPresentationEntry(
                eventId = "ev-pending",
                batchId = 999L,
                batchOrder = 0,
                phase = LiveEventPhase.OFFSCREEN_PENDING,
            )
        val initialTracker =
            LiveEventTrackerState(
                entries = presented + offscreen,
                lastRegisteredBatchId = 999L,
            )

        // Reconcile pass with no visible changes
        val reconciled =
            LiveEventTrackerReducer.reconcileAfterLayout(
                state = initialTracker,
                actuallyVisibleKeys = emptySet(),
            )

        val finalPresented = reconciled.entries.filter { it.phase == LiveEventPhase.PRESENTED }
        assertEquals(200, finalPresented.size)
        // First 5 (oldest) presented entries dropped
        assertEquals("ev-6", finalPresented.first().eventId)
        assertEquals("ev-205", finalPresented.last().eventId)
        // Non-presented entry is preserved
        assertTrue(reconciled.entries.any { it.eventId == "ev-pending" })
    }

    // =========================================================================
    // 5. EventsSessionProcessor: shutdown, deny latch, terminal, Error
    // =========================================================================

    @Test
    fun testEventsSessionProcessor_CommandsOnShutdown() =
        runTest(testDispatcher) {
            val processorScope = CoroutineScope(SupervisorJob() + testDispatcher)
            val processor =
                EventsSessionProcessor(
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    scope = processorScope,
                    onSendReplayRequest = { _, _ -> },
                    onTriggerRebootstrap = {},
                    onTokenRefreshNeeded = {},
                    onAccessInvalidated = { _, _ -> },
                )

            processor.close()
            runCurrent()

            // ViewportAtTop flush command should be rejected with PROCESSOR_CLOSED
            val flushDeferred = CompletableDeferred<PendingNewerFlushResult>()
            val dispatch1 =
                processor.dispatch(
                    SessionCommand.ViewportAtTop(
                        operationId = 42L,
                        response = flushDeferred,
                    ),
                )
            assertEquals(CommandDispatchResult.CLOSED, dispatch1)
            assertTrue(flushDeferred.isCompleted)
            val result = flushDeferred.await()
            assertIs<PendingNewerFlushResult.Rejected>(result)
            assertEquals(FlushFailureReason.PROCESSOR_CLOSED, (result as PendingNewerFlushResult.Rejected).reason)

            // InstallBootstrap should fail with CancellationException
            val bootstrapDeferred = CompletableDeferred<BootstrapApplyResult>()
            val dummyBootstrap =
                EventsBootstrap(
                    page = EventsPage(emptyList(), null, false, 0, 1L),
                    summary = EventsSummary("UTC", "2026-03-01", 1L),
                    streamCursor = 1L,
                )
            val dispatch2 =
                processor.dispatch(
                    SessionCommand.InstallBootstrap(
                        bootstrap = dummyBootstrap,
                        response = bootstrapDeferred,
                    ),
                )
            assertEquals(CommandDispatchResult.CLOSED, dispatch2)
            assertTrue(bootstrapDeferred.isCompleted)
            assertFailsWith<CancellationException> {
                bootstrapDeferred.await()
            }

            processorScope.cancel()
        }

    @Test
    fun testEventsSessionProcessor_CommandsOnDenyLatch() =
        runTest(testDispatcher) {
            val processorScope = CoroutineScope(SupervisorJob() + testDispatcher)
            val processor =
                EventsSessionProcessor(
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    scope = processorScope,
                    onSendReplayRequest = { _, _ -> },
                    onTriggerRebootstrap = {},
                    onTokenRefreshNeeded = {},
                    onAccessInvalidated = { _, _ -> },
                )

            // Deny events read immediately
            processor.setImmediateEventsReadDenied(denied = true, generation = 1L)
            runCurrent()

            // Flush command rejected with ACCESS_DENIED
            val flushDeferred = CompletableDeferred<PendingNewerFlushResult>()
            processor.dispatch(
                SessionCommand.ViewportAtTop(
                    operationId = 100L,
                    response = flushDeferred,
                ),
            )
            runCurrent()
            assertTrue(flushDeferred.isCompleted)
            val res = flushDeferred.await()
            assertIs<PendingNewerFlushResult.Rejected>(res)
            assertEquals(FlushFailureReason.ACCESS_DENIED, (res as PendingNewerFlushResult.Rejected).reason)

            processorScope.cancel()
        }

    // =========================================================================
    // 6. EventsFeedSessionImpl: single-flight history refresh
    // =========================================================================

    private class TestWebSocketDataSource : EventsWebSocketDataSource {
        val framesFlow = MutableSharedFlow<IncomingWsEnvelope>(extraBufferCapacity = 64)
        override val incomingFrames: Flow<IncomingWsEnvelope> = framesFlow

        override fun connect(
            companyId: String,
            accessToken: String,
            replayId: Long,
            afterSequence: Long,
            scope: CoroutineScope,
            getClientConfirmedSeq: () -> Long,
            activationGate: CompletableDeferred<Unit>?,
            onDisconnected: (reason: WsCloseReason, message: String?) -> Unit,
        ): Long {
            activationGate?.complete(Unit)
            return 1L
        }

        override fun activateConnection(connectionId: Long) {}

        override suspend fun sendReplayRequest(
            replayId: Long,
            afterSequence: Long,
        ) {}

        override suspend fun sendHeartbeat(clientConfirmedSeq: Long) {}

        override suspend fun disconnect(connectionId: Long?) {}
    }

    private class MockRemoteWithDeliberateDelay(
        private val delayMs: Long = 100L,
    ) : EventsRemoteDataSource {
        var fetchCount = 0

        override suspend fun getBootstrap(
            companyId: String,
            query: EventsQuery,
            limit: Int,
        ): EventsBootstrapDto {
            fetchCount++
            if (delayMs > 0) delay(delayMs)
            return EventsBootstrapDto(
                streamCursor = 10L,
                events =
                    BootstrapEventsDto(
                        items = emptyList(),
                        pagination =
                            EventsPaginationDto(
                                snapshotId = "snap-1",
                                asOfSequence = 10L,
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 0,
                            ),
                    ),
                summary =
                    EventsSummaryDto(
                        businessTimezone = "UTC",
                        businessDate = "2026-03-01",
                        summaryAsOfSequence = 10L,
                    ),
            )
        }

        override suspend fun getEventsPage(
            companyId: String,
            cursor: String,
            limit: Int,
        ): EventsPageDto = error("unused")

        override suspend fun getFilterOptions(
            companyId: String,
            ifNoneMatchEtag: String?,
        ): FilterOptionsFetchResult = error("unused")

        override suspend fun acknowledgeEvent(
            companyId: String,
            eventId: String,
        ): EventDto = error("unused")

        override suspend fun markEventErroneous(
            companyId: String,
            eventId: String,
            expectedClassificationVersion: Long,
            idempotencyKey: String,
        ): EventDto = error("unused")

        override suspend fun createExport(
            companyId: String,
            request: CreateExportRequestDto,
            idempotencyKey: String,
        ): EventsExportStatusDto = error("unused")

        override suspend fun getExportStatus(
            companyId: String,
            exportId: String,
        ): EventsExportStatusDto = error("unused")

        override suspend fun downloadExport(
            companyId: String,
            exportId: String,
        ): EventsExportDownload = error("unused")
    }

    @Test
    fun testEventsFeedSessionImpl_SingleFlightHistoryRefreshCoalescesCalls() =
        runTest(testDispatcher) {
            val remote = MockRemoteWithDeliberateDelay(delayMs = 50L)
            val session =
                EventsFeedSessionImpl(
                    companyId = "comp-1",
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    remoteDataSource = remote,
                    webSocketDataSource = TestWebSocketDataSource(),
                    sessionManager =
                        SessionManager(
                            object : TokenStorage {
                                override fun getTokens() = TokenPair("access", "refresh")

                                override fun saveTokens(tokens: TokenPair) {}

                                override fun clear() {}

                                override fun hasTokens() = true
                            },
                        ),
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline: Flow<Boolean> = flowOf(true)
                        },
                    parentScope = this,
                )

            // Concurrently invoke refreshForHistory 3 times
            val call1 = async { session.refreshForHistory(1L) }
            val call2 = async { session.refreshForHistory(2L) }
            val call3 = async { session.refreshForHistory(3L) }

            advanceTimeBy(100L)
            runCurrent()

            val res1 = call1.await()
            val res2 = call2.await()
            val res3 = call3.await()

            // All calls should receive Updated result
            assertIs<HistoryRefreshResult.Updated>(res1)
            assertIs<HistoryRefreshResult.Updated>(res2)
            assertIs<HistoryRefreshResult.Updated>(res3)

            // Only 1 bootstrap fetch was executed during the single-flight attempt (plus initial bootstrap if any)
            assertTrue(remote.fetchCount <= 2)

            session.close()
        }

    @Test
    fun testEventsFeedSessionImpl_CloseWhileAwaitingRefreshFailsImmediately() =
        runTest(testDispatcher) {
            val remote = MockRemoteWithDeliberateDelay(delayMs = 5_000L)
            val session =
                EventsFeedSessionImpl(
                    companyId = "comp-1",
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    remoteDataSource = remote,
                    webSocketDataSource = TestWebSocketDataSource(),
                    sessionManager =
                        SessionManager(
                            object : TokenStorage {
                                override fun getTokens() = TokenPair("access", "refresh")

                                override fun saveTokens(tokens: TokenPair) {}

                                override fun clear() {}

                                override fun hasTokens() = true
                            },
                        ),
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline: Flow<Boolean> = flowOf(true)
                        },
                    parentScope = this,
                )

            val refreshJob = async { session.refreshForHistory(99L) }
            runCurrent()

            // Closing session while refresh is pending
            session.close()
            runCurrent()

            val result = refreshJob.await()
            assertIs<HistoryRefreshResult.Failed>(result)
        }

    // =========================================================================
    // 7. EventsViewModel: retry job for delta ACK & overflow recovery
    // =========================================================================

    private class ControlledTestFeedSession : EventsFeedSession {
        override val companyId = "comp-1"
        override val query = EventsQuery()
        val mutableState = MutableStateFlow(EventsFeedState(access = EventsAccessCapabilities(eventsRead = true)))
        override val state: StateFlow<EventsFeedState> get() = mutableState
        override val connectionNotice: Flow<Notice> = emptyFlow()
        override val filterOptionsInvalidated: Flow<Unit> = emptyFlow()

        var ackAttempts = 0
        var ackSuccessOnAttempt = 3
        var refreshHistoryCount = 0

        override suspend fun flushPendingNewer(operationId: Long): PendingNewerFlushResult =
            PendingNewerFlushResult.Completed(batchId = 0L, snapshotId = null)

        override suspend fun acknowledgeDeltasThrough(batchId: Long): Boolean {
            ackAttempts++
            return ackAttempts >= ackSuccessOnAttempt
        }

        override suspend fun refreshForHistory(operationId: Long): HistoryRefreshResult {
            refreshHistoryCount++
            return HistoryRefreshResult.Updated
        }

        override fun loadNextPage() {}

        override fun refresh() {}

        override fun onViewportAtTop() {}

        override fun markOptimisticAckPending(eventId: String) {}

        override fun clearOptimisticAckPending(eventId: String) {}

        override fun applyAcknowledged(event: Event) {}

        override fun applyEventMutationResult(event: Event) {}

        override fun retryRebootstrap() {}

        override fun updateAccessCapabilities(access: EventsAccessCapabilities) {}

        override fun close() {}
    }

    private fun createTestViewModel(session: EventsFeedSession): EventsViewModel =
        EventsViewModel(
            observeEventsFeedUseCase =
                object : ObserveEventsFeedUseCase {
                    override operator fun invoke(
                        companyId: String,
                        query: EventsQuery,
                        accessCapabilities: EventsAccessCapabilities,
                    ): EventsFeedSession = session
                },
            loadEventFilterOptionsUseCase =
                object : LoadEventFilterOptionsUseCase {
                    override suspend operator fun invoke(companyId: String): EventsFilterOptions = EventsFilterOptions()
                },
            acknowledgeEventUseCase =
                object : AcknowledgeEventUseCase {
                    override suspend operator fun invoke(
                        companyId: String,
                        eventId: String,
                    ): EventsOperationResult<Event> =
                        EventsOperationResult.Failure(EventsMutationError.Server("not implemented"))
                },
            markEventErroneousUseCase =
                object : MarkEventErroneousUseCase {
                    override suspend operator fun invoke(
                        companyId: String,
                        eventId: String,
                        expectedClassificationVersion: Long,
                        idempotencyKey: String,
                    ): EventsOperationResult<Event> =
                        EventsOperationResult.Failure(EventsMutationError.Server("not implemented"))
                },
            createEventsExportUseCase =
                object : CreateEventsExportUseCase {
                    override suspend operator fun invoke(
                        companyId: String,
                        request: EventsExportRequest,
                        idempotencyKey: String,
                    ): EventsExportStatus =
                        EventsExportStatus(
                            exportId = "exp-1",
                            status = EventsExportStatusCode.QUEUED,
                            createdAt =
                                kotlin.time.Clock.System
                                    .now(),
                        )
                },
            observeEventsExportStatusUseCase =
                object : ObserveEventsExportStatusUseCase {
                    override suspend operator fun invoke(
                        companyId: String,
                        exportId: String,
                    ): EventsExportStatus =
                        EventsExportStatus(
                            exportId = exportId,
                            status = EventsExportStatusCode.COMPLETED,
                            createdAt =
                                kotlin.time.Clock.System
                                    .now(),
                        )
                },
            downloadEventsExportUseCase =
                object : DownloadEventsExportUseCase {
                    override suspend operator fun invoke(
                        companyId: String,
                        exportId: String,
                    ): EventsExportDownload = EventsExportDownload("file.csv", 100L, "h-1")
                },
            exportFileSaver =
                object : ExportFileSaver {
                    override suspend fun saveExportFile(download: EventsExportDownload): Boolean = true
                },
        )

    @Test
    fun testEventsViewModel_LiveDeltaAckRetriesWithBackoffUntilSuccess() =
        runTest(testDispatcher) {
            val session = ControlledTestFeedSession()
            session.ackSuccessOnAttempt = 3 // fails attempt 1 and 2, succeeds on attempt 3

            val vm = createTestViewModel(session)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true))
            runCurrent()

            // Trigger live delta registered
            vm.onLiveDeltaRegistered(sessionGeneration = 1L, batchId = 10L)
            runCurrent()

            // Attempt 1 happened immediately and failed
            assertEquals(1, session.ackAttempts)

            // Advance through first exponential backoff (100ms)
            advanceTimeBy(150L)
            runCurrent()
            assertEquals(2, session.ackAttempts)

            // Advance through second exponential backoff (200ms)
            advanceTimeBy(250L)
            runCurrent()
            assertEquals(3, session.ackAttempts) // Succeeded!
        }

    @Test
    fun testEventsViewModel_OverflowRecoveryTriggeredWhenOverflowReported() =
        runTest(testDispatcher) {
            val session = ControlledTestFeedSession()
            val vm = createTestViewModel(session)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true))
            runCurrent()

            // Session reports DeltaDeliveryState.Overflowed
            session.mutableState.value =
                session.mutableState.value.copy(
                    deltaDeliveryState =
                        DeltaDeliveryState.Overflowed(
                            firstMissingBatchId = 1L,
                            latestBatchId = 105L,
                        ),
                )
            runCurrent()

            // ViewModel triggers recovery refresh on session
            assertTrue(session.refreshHistoryCount >= 1)
            assertEquals(DeltaDeliveryState.Overflowed(1L, 105L), vm.state.value.deltaDeliveryState)
        }
}
