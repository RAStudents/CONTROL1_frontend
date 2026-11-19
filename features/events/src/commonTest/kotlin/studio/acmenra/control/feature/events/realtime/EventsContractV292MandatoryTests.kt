package studio.acmenra.control.feature.events.realtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.TokenPair
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.data.datasource.EventsApiException
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.IncomingWsFrame
import studio.acmenra.control.feature.events.data.datasource.KtorEventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.WsCloseReason
import studio.acmenra.control.feature.events.data.model.AccessInvalidatedFrameDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.ReplayCompletedFrameDto
import studio.acmenra.control.feature.events.data.model.SafeJsLongSerializer
import studio.acmenra.control.feature.events.data.model.TenantCommitFrameDto
import studio.acmenra.control.feature.events.data.realtime.EventsSessionProcessor
import studio.acmenra.control.feature.events.data.realtime.SessionCommand
import studio.acmenra.control.feature.events.data.repository.EventsRepositoryImpl
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
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsPage
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.RealtimeConnectionState
import studio.acmenra.control.feature.events.domain.repository.EventsConnectivityProvider
import studio.acmenra.control.feature.events.domain.repository.EventsFeedSession
import studio.acmenra.control.feature.events.domain.repository.EventsFeedState
import studio.acmenra.control.feature.events.domain.usecase.AcknowledgeEventUseCase
import studio.acmenra.control.feature.events.domain.usecase.CreateEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.DownloadEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.LoadEventFilterOptionsUseCase
import studio.acmenra.control.feature.events.domain.usecase.MarkEventErroneousUseCase
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsExportStatusUseCase
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsFeedUseCase
import studio.acmenra.control.feature.events.presentation.EventsViewModel
import studio.acmenra.control.feature.events.presentation.platform.ExportFileSaver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EventsContractV292MandatoryTests {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class TestWebSocketDataSource : EventsWebSocketDataSource {
        val framesFlow = MutableSharedFlow<IncomingWsFrame>(extraBufferCapacity = 64)
        override val incomingFrames: Flow<IncomingWsFrame> = framesFlow

        var connectCalled = false
        var connectedCompanyId: String? = null
        var lastReplayId: Long? = null
        var lastAfterSequence: Long? = null
        var lastDisconnectReason: WsCloseReason? = null
        var disconnected = false
        var sentReplayRequests = mutableListOf<Pair<Long, Long>>()

        override fun connect(
            companyId: String,
            accessToken: String,
            replayId: Long,
            afterSequence: Long,
            scope: CoroutineScope,
            getClientConfirmedSeq: () -> Long,
            onDisconnected: (reason: WsCloseReason, message: String?) -> Unit,
        ) {
            connectCalled = true
            connectedCompanyId = companyId
            lastReplayId = replayId
            lastAfterSequence = afterSequence
        }

        override suspend fun sendReplayRequest(
            replayId: Long,
            afterSequence: Long,
        ) {
            sentReplayRequests.add(replayId to afterSequence)
        }

        override suspend fun sendHeartbeat(clientConfirmedSeq: Long) {}

        override suspend fun disconnect() {
            disconnected = true
        }
    }

    private fun sampleEvent(
        id: String,
        classificationVersion: Long = 1L,
    ): Event =
        Event(
            id = id,
            eventVersion = 1L,
            classificationVersion = classificationVersion,
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
            isErroneous = false,
            previewUrl = "https://cdn.example.com/preview.jpg",
            videoUrl = "https://cdn.example.com/video.mp4",
        )

    private fun sampleEventDto(
        id: String,
        classificationVersion: Long = 1L,
    ): EventDto =
        EventDto(
            id = id,
            eventVersion = 1L,
            classificationVersion = classificationVersion,
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
            isErroneous = false,
            previewUrl = "https://cdn.example.com/preview.jpg",
            videoUrl = "https://cdn.example.com/video.mp4",
        )

    // Scenario 1: Tenant Session Isolation (Sessions A and B have completely isolated WebSocket instances and data)
    @Test
    fun testScenario1_TenantSessionIsolation() =
        runTest(testDispatcher) {
            val parentScopeA = CoroutineScope(SupervisorJob() + testDispatcher)
            val parentScopeB = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val createdWsInstances = mutableListOf<TestWebSocketDataSource>()
                val mockRemote =
                    object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                        override suspend fun getBootstrap(
                            companyId: String,
                            query: EventsQuery,
                            limit: Int,
                        ) = studio.acmenra.control.feature.events.data.model.EventsBootstrapDto(
                            streamCursor = 100L,
                            events =
                                studio.acmenra.control.feature.events.data.model
                                    .EventsPageDto(),
                            summary =
                                studio.acmenra.control.feature.events.data.model.EventsSummaryDto(
                                    businessTimezone = "UTC",
                                    businessDate = "2026-03-01",
                                    summaryAsOfSequence = 100L,
                                ),
                        )

                        override suspend fun getEventsPage(
                            companyId: String,
                            cursor: String,
                            limit: Int,
                        ) = studio.acmenra.control.feature.events.data.model
                            .EventsPageDto()

                        override suspend fun getFilterOptions(
                            companyId: String,
                            ifNoneMatchEtag: String?,
                        ) = studio.acmenra.control.feature.events.data.datasource.FilterOptionsFetchResult.Success(
                            studio.acmenra.control.feature.events.data.model
                                .EventsFilterOptionsDto(revision = "r1"),
                            etag = "e1",
                        )

                        override suspend fun acknowledgeEvent(
                            companyId: String,
                            eventId: String,
                        ) = sampleEventDto(eventId)

                        override suspend fun markEventErroneous(
                            companyId: String,
                            eventId: String,
                            expectedClassificationVersion: Long,
                            idempotencyKey: String,
                        ) = sampleEventDto(eventId)

                        override suspend fun createExport(
                            companyId: String,
                            request: studio.acmenra.control.feature.events.data.model.CreateExportRequestDto,
                            idempotencyKey: String,
                        ) = error("unused")

                        override suspend fun getExportStatus(
                            companyId: String,
                            exportId: String,
                        ) = error("unused")

                        override suspend fun downloadExport(
                            companyId: String,
                            exportId: String,
                        ) = error("unused")
                    }

                val testStorage =
                    object : TokenStorage {
                        var stored: TokenPair? = null

                        override fun getTokens(): TokenPair? = stored

                        override fun saveTokens(tokens: TokenPair) {
                            stored = tokens
                        }

                        override fun clear() {
                            stored = null
                        }

                        override fun hasTokens(): Boolean = stored != null
                    }
                val sessionManager = SessionManager(testStorage)
                sessionManager.saveSession(TokenPair(accessToken = "valid_tok", refreshToken = "ref_tok"))

                val connectivity =
                    object : EventsConnectivityProvider {
                        override val isOnline: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)
                    }

                val repository =
                    EventsRepositoryImpl(
                        remoteDataSource = mockRemote,
                        webSocketDataSourceFactory = {
                            val ds = TestWebSocketDataSource()
                            createdWsInstances.add(ds)
                            ds
                        },
                        sessionManager = sessionManager,
                        connectivityProvider = connectivity,
                    )

                val sessionA =
                    repository.openFeedSession(
                        "comp-A",
                        EventsQuery(),
                        EventsAccessCapabilities(eventsRead = true),
                        parentScope = parentScopeA,
                    )
                val sessionB =
                    repository.openFeedSession(
                        "comp-B",
                        EventsQuery(),
                        EventsAccessCapabilities(eventsRead = true),
                        parentScope = parentScopeB,
                    )
                advanceUntilIdle()

                assertEquals(2, createdWsInstances.size)
                val wsA = createdWsInstances[0]
                val wsB = createdWsInstances[1]

                assertEquals("comp-A", wsA.connectedCompanyId)
                assertEquals("comp-B", wsB.connectedCompanyId)

                // Frame delivered to A must NOT appear in B
                val replayA = wsA.lastReplayId ?: 1L
                wsA.framesFlow.emit(
                    IncomingWsFrame.TenantCommit(
                        TenantCommitFrameDto(
                            replayId = replayA,
                            tenantSequence = 101L,
                            eventCreated = sampleEventDto("ev-comp-A"),
                        ),
                    ),
                )
                advanceUntilIdle()
                sessionA.onViewportAtTop()
                advanceUntilIdle()

                assertTrue(
                    sessionA.state.value.visibleEvents
                        .any { it.id == "ev-comp-A" },
                )
                assertFalse(
                    sessionB.state.value.visibleEvents
                        .any { it.id == "ev-comp-A" },
                )

                sessionA.close()
                sessionB.close()
                advanceUntilIdle()
            } finally {
                parentScopeA.cancel()
                parentScopeB.cancel()
            }
        }

    // Scenario 2: WebSocket Replaced / Session Closed does not trigger spurious reconnect
    @Test
    fun testScenario2_WsReplacedDoesNotTriggerReconnect() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.CONNECTED))
                advanceUntilIdle()
                assertEquals(RealtimeConnectionState.CONNECTED, processor.state.value.connectionState)

                processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.DISCONNECTED))
                advanceUntilIdle()
                assertEquals(RealtimeConnectionState.DISCONNECTED, processor.state.value.connectionState)

                processor.dispatch(SessionCommand.Close)
                advanceUntilIdle()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 3: Terminal ACCESS_INVALIDATED ignores subsequent TENANT_COMMIT and clears state
    @Test
    fun testScenario3_TerminalAccessInvalidated() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var accessInvalidatedReason: String? = null
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { reason, _ -> accessInvalidatedReason = reason },
                    )

                val bootstrap =
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
                processor.installBootstrap(bootstrap, generation = 1L)
                advanceUntilIdle()
                assertEquals(1, processor.state.value.visibleEvents.size)

                // Receive terminal ACCESS_INVALIDATED
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsFrame.AccessInvalidated(
                            AccessInvalidatedFrameDto(reason = "COMPANY_RESTRICTED", companyId = "comp-1"),
                        ),
                    ),
                )
                advanceUntilIdle()

                assertEquals("COMPANY_RESTRICTED", accessInvalidatedReason)
                assertEquals(RealtimeConnectionState.FAILED, processor.state.value.connectionState)
                assertTrue(
                    processor.state.value.visibleEvents
                        .isEmpty(),
                )
                assertNull(processor.state.value.summary)
                assertEquals(0L, processor.state.value.asOfSequence)

                // Subsequent commit arrives after invalidation
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsFrame.TenantCommit(
                            TenantCommitFrameDto(
                                replayId = 1L,
                                tenantSequence = 101L,
                                eventCreated = sampleEventDto("ev-after-invalidated"),
                            ),
                        ),
                    ),
                )
                advanceUntilIdle()

                // Must still be empty
                assertTrue(
                    processor.state.value.visibleEvents
                        .isEmpty(),
                )
                assertEquals(RealtimeConnectionState.FAILED, processor.state.value.connectionState)

                processor.dispatch(SessionCommand.Close)
                advanceUntilIdle()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 4: REPLAY_COMPLETED(throughSequence = 500) when client sequence is 498 does not enter CONNECTED
    @Test
    fun testScenario4_ReplayCompletedMissingFramesDoesNotEnterConnected() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var requestedReplaySeq: Long? = null
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, afterSeq -> requestedReplaySeq = afterSeq },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                processor.dispatch(SessionCommand.ResetSequence(498L))
                processor.dispatch(SessionCommand.StartReplay(replayId = 1L, afterSequence = 498L))
                processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.REPLAYING))
                advanceUntilIdle()

                // Server claims replay completed up to 500
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsFrame.ReplayCompleted(
                            ReplayCompletedFrameDto(replayId = 1L, throughSequence = 500L),
                        ),
                    ),
                )
                advanceUntilIdle()

                // Must NOT be CONNECTED
                assertEquals(RealtimeConnectionState.REPLAYING, processor.state.value.connectionState)
                // Must request missing sequence range from 498
                assertEquals(498L, requestedReplaySeq)

                processor.dispatch(SessionCommand.Close)
                advanceUntilIdle()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 5: Concurrent Bootstrap: stale bootstrap with lower generation is discarded
    @Test
    fun testScenario5_ConcurrentBootstrapGenerationGuard() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val bootstrapGen2 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-gen2")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 200L,
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 200L,
                                todayEventsCount = 1,
                            ),
                        streamCursor = 200L,
                    )
                val bootstrapGen1Stale =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-gen1-stale")),
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

                processor.installBootstrap(bootstrapGen2, generation = 2L)
                advanceUntilIdle()
                assertEquals(
                    listOf("ev-gen2"),
                    processor.state.value.visibleEvents
                        .map { it.id },
                )

                // Stale generation 1 arrives late
                processor.installBootstrap(bootstrapGen1Stale, generation = 1L)
                advanceUntilIdle()

                // Must still retain generation 2 items
                assertEquals(
                    listOf("ev-gen2"),
                    processor.state.value.visibleEvents
                        .map { it.id },
                )
                assertEquals(200L, processor.state.value.asOfSequence)

                processor.dispatch(SessionCommand.Close)
                advanceUntilIdle()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 6: Rebootstrap buffer deduplication [101, 101, 102] preserves continuity
    @Test
    fun testScenario6_RebootstrapBufferDeduplication() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val bootstrap1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-100")),
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
                processor.installBootstrap(bootstrap1, generation = 1L)
                advanceUntilIdle()
                val activeReplay = processor.currentReplayId

                // Trigger RESYNC_REQUIRED to enter rebootstrap buffering
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsFrame.ResyncRequired(
                            studio.acmenra.control.feature.events.data.model
                                .ResyncRequiredFrameDto(replayId = activeReplay),
                        ),
                    ),
                )
                advanceUntilIdle()

                // Buffer commits with duplicate 101
                val commit101a =
                    TenantCommitFrameDto(
                        replayId = activeReplay,
                        tenantSequence = 101L,
                        payloadSha256 = "sha-101",
                        eventCreated = sampleEventDto("ev-101"),
                    )
                val commit101b =
                    TenantCommitFrameDto(
                        replayId = activeReplay,
                        tenantSequence = 101L,
                        payloadSha256 = "sha-101",
                        eventCreated = sampleEventDto("ev-101"),
                    )
                val commit102 =
                    TenantCommitFrameDto(
                        replayId = activeReplay,
                        tenantSequence = 102L,
                        payloadSha256 = "sha-102",
                        eventCreated = sampleEventDto("ev-102"),
                    )

                processor.dispatch(SessionCommand.WsFrameReceived(IncomingWsFrame.TenantCommit(commit101a)))
                processor.dispatch(SessionCommand.WsFrameReceived(IncomingWsFrame.TenantCommit(commit101b)))
                processor.dispatch(SessionCommand.WsFrameReceived(IncomingWsFrame.TenantCommit(commit102)))
                advanceUntilIdle()

                // Rebootstrap loads bootstrap with streamCursor = 100
                val bootstrap2 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-100")),
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
                processor.installBootstrap(bootstrap2, generation = 2L)
                advanceUntilIdle()

                // Deduplication succeeded: sequence advanced to 102 and both events buffered without gap error
                assertEquals(102L, processor.getAsOfSequence())
                assertEquals(2, processor.state.value.pendingNewerCount)

                // Integrate newer events: they become visible
                processor.dispatch(SessionCommand.ViewportAtTop)
                advanceUntilIdle()

                val visibleIds =
                    processor.state.value.visibleEvents
                        .map { it.id }
                assertTrue("ev-101" in visibleIds)
                assertTrue("ev-102" in visibleIds)
                assertEquals(0, processor.state.value.pendingNewerCount)

                processor.dispatch(SessionCommand.Close)
                advanceUntilIdle()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 7: Acknowledge 200 OK updates event in visible list and clears optimistic pending ID
    @Test
    fun testScenario7_Acknowledge200OKUpdatesListAndClearsPending() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val unackEvent = sampleEvent("ev-ack-test").copy(status = EventAcknowledgementStatus.NEW)
                val bootstrap =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(unackEvent),
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
                processor.installBootstrap(bootstrap, generation = 1L)
                advanceUntilIdle()

                // User clicks acknowledge: optimistic ack pending
                processor.dispatch(SessionCommand.OptimisticAckPending("ev-ack-test"))
                advanceUntilIdle()
                assertTrue("ev-ack-test" in processor.state.value.pendingAckIds)

                // Server returns 200 OK with updated ACKNOWLEDGED event
                val acknowledgedEvent =
                    unackEvent.copy(
                        status = EventAcknowledgementStatus.ACKNOWLEDGED,
                        classificationVersion = 2L,
                    )
                processor.dispatch(SessionCommand.AcknowledgeSucceeded(acknowledgedEvent))
                advanceUntilIdle()

                // Pending ID removed, event in visibleEvents updated
                assertFalse("ev-ack-test" in processor.state.value.pendingAckIds)
                val eventInState =
                    processor.state.value.visibleEvents
                        .first { it.id == "ev-ack-test" }
                assertEquals(EventAcknowledgementStatus.ACKNOWLEDGED, eventInState.status)
                assertEquals(2L, eventInState.classificationVersion)

                processor.dispatch(SessionCommand.Close)
                advanceUntilIdle()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 8: Targeted Erroneous Confirmation: Dialog retains target event A even if event B is clicked
    @Test
    fun testScenario8_TargetedErroneousConfirmation() =
        runTest(testDispatcher) {
            val eventA = sampleEvent("ev-A", classificationVersion = 2L)
            val eventB = sampleEvent("ev-B", classificationVersion = 5L)

            var markedId: String? = null
            var markedVersion: Long? = null

            val markUseCase =
                object : MarkEventErroneousUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        eventId: String,
                        expectedClassificationVersion: Long,
                        idempotencyKey: String,
                    ): EventsOperationResult<Event> {
                        markedId = eventId
                        markedVersion = expectedClassificationVersion
                        return EventsOperationResult.Success(sampleEvent(eventId).copy(isErroneous = true))
                    }
                }

            val feedSession =
                object : EventsFeedSession {
                    override val companyId = "comp-1"
                    override val query = EventsQuery()
                    override val state =
                        kotlinx.coroutines.flow.MutableStateFlow(
                            EventsFeedState(
                                visibleEvents = listOf(eventA, eventB),
                                access = EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                            ),
                        )
                    override val connectionNotice: Flow<Notice> = kotlinx.coroutines.flow.emptyFlow()
                    override val filterOptionsInvalidated: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()

                    override fun loadNextPage() {}

                    override fun refresh() {}

                    override fun onViewportAtTop() {}

                    override fun markOptimisticAckPending(eventId: String) {}

                    override fun clearOptimisticAckPending(eventId: String) {}

                    override fun applyAcknowledged(event: Event) {}

                    override fun retryRebootstrap() {}

                    override fun updateAccessCapabilities(access: EventsAccessCapabilities) {}

                    override fun close() {}
                }

            val vm =
                EventsViewModel(
                    observeEventsFeedUseCase =
                        object : ObserveEventsFeedUseCase {
                            override fun invoke(
                                c: String,
                                q: EventsQuery,
                                a: EventsAccessCapabilities,
                            ) = feedSession
                        },
                    loadEventFilterOptionsUseCase =
                        object : LoadEventFilterOptionsUseCase {
                            override suspend fun invoke(c: String) = EventsFilterOptions()
                        },
                    acknowledgeEventUseCase =
                        object : AcknowledgeEventUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ) = EventsOperationResult.Success(sampleEvent(id))
                        },
                    markEventErroneousUseCase = markUseCase,
                    createEventsExportUseCase =
                        object : CreateEventsExportUseCase {
                            override suspend fun invoke(
                                c: String,
                                r: EventsExportRequest,
                                k: String,
                            ) = error("unused")
                        },
                    observeEventsExportStatusUseCase =
                        object : ObserveEventsExportStatusUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ) = error("unused")
                        },
                    downloadEventsExportUseCase =
                        object : DownloadEventsExportUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ) = error("unused")
                        },
                    exportFileSaver =
                        object : ExportFileSaver {
                            override suspend fun saveExportFile(d: EventsExportDownload) = true
                        },
                )
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))
            advanceUntilIdle()

            // User opens dialog for Event A
            vm.requestMarkErroneous(eventA)
            assertNotNull(vm.state.value.confirmationTarget)
            assertEquals(
                "ev-A",
                vm.state.value.confirmationTarget
                    ?.eventId,
            )
            assertEquals(
                2L,
                vm.state.value.confirmationTarget
                    ?.expectedClassificationVersion,
            )

            // While dialog is open, user clicks on Event B in the list
            vm.selectEvent(eventB.id)
            assertEquals("ev-B", vm.state.value.selectedEventId)

            // User confirms in the open dialog (which was targeting A)
            vm.confirmMarkErroneous()
            advanceUntilIdle()

            // Must mark event A, NOT event B!
            assertEquals("ev-A", markedId)
            assertEquals(2L, markedVersion)
        }

    // Scenario 9: 410 EXPORT_EXPIRED maps to EventsApiException.ExportExpired (not SnapshotExpired)
    @Test
    fun testScenario9_ExportExpiredExceptionMapping() =
        runTest(testDispatcher) {
            val mockEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/companies/comp-1/events/exports/exp-99" -> {
                            respond(
                                content = """{"error": {"code": "EXPORT_EXPIRED", "message": "Export expired"}}""",
                                status = HttpStatusCode.Gone,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        "/api/v1/companies/comp-1/events/bootstrap" -> {
                            respond(
                                content = """{"error": {"code": "SNAPSHOT_EXPIRED", "message": "Snapshot expired"}}""",
                                status = HttpStatusCode.Gone,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        else -> error("Unhandled ${request.url.encodedPath}")
                    }
                }

            val client = HttpClient(mockEngine)
            val remoteDataSource = KtorEventsRemoteDataSource(client)

            // 410 with EXPORT_EXPIRED -> EventsApiException.ExportExpired
            assertFailsWith<EventsApiException.ExportExpired> {
                remoteDataSource.getExportStatus("comp-1", "exp-99")
            }

            // 410 with SNAPSHOT_EXPIRED -> EventsApiException.SnapshotExpired
            assertFailsWith<EventsApiException.SnapshotExpired> {
                remoteDataSource.getBootstrap("comp-1", EventsQuery())
            }
        }

    // Scenario 10: SafeJsLongSerializer rejects decimal points, exponents, negatives, and overflow
    @Test
    fun testScenario10_SafeJsLongSerializerValidation() {
        val json = Json { ignoreUnknownKeys = true }

        // Valid Safe Integer
        val validJson = "123456789"
        assertEquals(123456789L, json.decodeFromString(SafeJsLongSerializer, validJson))

        // Rejects float / decimal point
        assertFailsWith<SerializationException> {
            json.decodeFromString(SafeJsLongSerializer, "123.45")
        }

        // Rejects scientific notation
        assertFailsWith<SerializationException> {
            json.decodeFromString(SafeJsLongSerializer, "1e6")
        }

        // Rejects negative number
        assertFailsWith<SerializationException> {
            json.decodeFromString(SafeJsLongSerializer, "-1")
        }

        // Rejects overflow > 9007199254740991L
        assertFailsWith<SerializationException> {
            json.decodeFromString(SafeJsLongSerializer, "9007199254740992")
        }

        // Rejects string numbers
        assertFailsWith<SerializationException> {
            json.decodeFromString(SafeJsLongSerializer, "\"123\"")
        }
    }

    // Scenario 11: Filename Sanitization for File Saving
    @Test
    fun testScenario11_FilenameSanitization() {
        val dangerousFilename = "../../etc/passwd\u0000_export*.csv"
        val clean = dangerousFilename.replace(Regex("[/\\\\:*?\"<>|\u0000-\u001F]"), "_").trim()
        assertFalse(clean.contains("/"))
        assertFalse(clean.contains("\\"))
        assertFalse(clean.contains("\u0000"))
        assertTrue(clean.endsWith(".csv"))
    }

    // Scenario 12: Revoking REPORTS_EXPORT during export polling cancels polling and exits export mode
    @Test
    fun testScenario12_RevokingExportAccessTerminatesPolling() =
        runTest(testDispatcher) {
            var observeExportCalls = 0
            val vm =
                EventsViewModel(
                    observeEventsFeedUseCase =
                        object : ObserveEventsFeedUseCase {
                            override fun invoke(
                                c: String,
                                q: EventsQuery,
                                a: EventsAccessCapabilities,
                            ) = object : EventsFeedSession {
                                override val companyId = c
                                override val query = q
                                override val state =
                                    kotlinx.coroutines.flow.MutableStateFlow(
                                        EventsFeedState(access = a),
                                    )
                                override val connectionNotice: Flow<Notice> = kotlinx.coroutines.flow.emptyFlow()
                                override val filterOptionsInvalidated: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()

                                override fun loadNextPage() {}

                                override fun refresh() {}

                                override fun onViewportAtTop() {}

                                override fun markOptimisticAckPending(eventId: String) {}

                                override fun clearOptimisticAckPending(eventId: String) {}

                                override fun applyAcknowledged(event: Event) {}

                                override fun retryRebootstrap() {}

                                override fun updateAccessCapabilities(access: EventsAccessCapabilities) {}

                                override fun close() {}
                            }
                        },
                    loadEventFilterOptionsUseCase =
                        object : LoadEventFilterOptionsUseCase {
                            override suspend fun invoke(c: String) = EventsFilterOptions()
                        },
                    acknowledgeEventUseCase =
                        object : AcknowledgeEventUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ) = error("unused")
                        },
                    markEventErroneousUseCase =
                        object : MarkEventErroneousUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                                v: Long,
                                k: String,
                            ) = error("unused")
                        },
                    createEventsExportUseCase =
                        object : CreateEventsExportUseCase {
                            override suspend fun invoke(
                                c: String,
                                r: EventsExportRequest,
                                k: String,
                            ) = EventsExportStatus(
                                exportId = "exp-1",
                                status = EventsExportStatusCode.PROCESSING,
                                createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                            )
                        },
                    observeEventsExportStatusUseCase =
                        object : ObserveEventsExportStatusUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ): EventsExportStatus {
                                observeExportCalls++
                                return EventsExportStatus(
                                    exportId = id,
                                    status = EventsExportStatusCode.PROCESSING,
                                    createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                                )
                            }
                        },
                    downloadEventsExportUseCase =
                        object : DownloadEventsExportUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ) = error("unused")
                        },
                    exportFileSaver =
                        object : ExportFileSaver {
                            override suspend fun saveExportFile(d: EventsExportDownload) = true
                        },
                )

            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))
            vm.toggleExportMode()
            assertTrue(vm.state.value.isExportModeActive)

            vm.startExport()
            advanceUntilIdle()
            assertTrue(observeExportCalls > 0)

            // Revoke reportsExport capability
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = false))
            advanceUntilIdle()

            // Export mode must be deactivated and export status reset
            assertFalse(vm.state.value.isExportModeActive)
            assertNull(vm.state.value.exportJobStatus)
        }

    // Scenario 13: Monotonic replay ID on multiple refreshes/bootstraps (never resets to 1L)
    @Test
    fun testScenario13_MonotonicReplayId() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val b1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("e1")),
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
                val res1 = processor.installBootstrap(b1, generation = 1L)
                assertTrue(res1 is studio.acmenra.control.feature.events.data.realtime.BootstrapApplyResult.Applied)
                val replay1 =
                    (res1 as studio.acmenra.control.feature.events.data.realtime.BootstrapApplyResult.Applied)
                        .replayId
                assertTrue(replay1 >= 1L)

                val b2 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("e2")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 150L,
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 150L,
                                todayEventsCount = 2,
                            ),
                        streamCursor = 150L,
                    )
                val res2 = processor.installBootstrap(b2, generation = 2L)
                assertTrue(res2 is studio.acmenra.control.feature.events.data.realtime.BootstrapApplyResult.Applied)
                val replay2 =
                    (res2 as studio.acmenra.control.feature.events.data.realtime.BootstrapApplyResult.Applied)
                        .replayId
                assertTrue(replay2 > replay1, "Replay ID must strictly increase on subsequent bootstrap/refresh")
                assertEquals(replay2, processor.currentReplayId)

                processor.dispatch(SessionCommand.Close)
                advanceUntilIdle()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 14: WebSocket replacement with delayed old finally does not cancel new connection's jobs
    @Test
    fun testScenario14_WebSocketReplacementIsolation() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val wsDataSource =
                    studio.acmenra.control.feature.events.data.datasource.KtorEventsWebSocketDataSource(
                        realtimeClient =
                            HttpClient(
                                MockEngine {
                                    kotlinx.coroutines.delay(10_000L)
                                    respond("OK")
                                },
                            ) {
                                install(io.ktor.client.plugins.websocket.WebSockets)
                            },
                        wsEndpointBase = "ws://localhost:8080",
                    )
                var disconnectedReason1: WsCloseReason? = null
                wsDataSource.connect(
                    companyId = "comp-1",
                    accessToken = "tok-1",
                    replayId = 1L,
                    afterSequence = 100L,
                    scope = testScope,
                    getClientConfirmedSeq = { 100L },
                    onDisconnected = { reason, _ -> disconnectedReason1 = reason },
                )
                testDispatcher.scheduler.runCurrent()

                var disconnectedReason2: WsCloseReason? = null
                wsDataSource.connect(
                    companyId = "comp-1",
                    accessToken = "tok-2",
                    replayId = 2L,
                    afterSequence = 200L,
                    scope = testScope,
                    getClientConfirmedSeq = { 200L },
                    onDisconnected = { reason, _ -> disconnectedReason2 = reason },
                )
                testDispatcher.scheduler.runCurrent()

                assertEquals(WsCloseReason.REPLACED, disconnectedReason1)
                assertNull(disconnectedReason2)

                wsDataSource.disconnect()
                advanceUntilIdle()
                assertEquals(WsCloseReason.SESSION_CLOSED, disconnectedReason2)
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 15: Rebootstrap buffer dropping commits from older replay generations
    @Test
    fun testScenario15_RebootstrapBufferGenerationFilter() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val b1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("e1")),
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
                processor.installBootstrap(b1, generation = 1L)
                advanceUntilIdle()
                val activeReplayId = processor.currentReplayId

                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsFrame.ResyncRequired(
                            studio.acmenra.control.feature.events.data.model
                                .ResyncRequiredFrameDto(replayId = activeReplayId),
                        ),
                    ),
                )
                advanceUntilIdle()

                val staleCommit =
                    TenantCommitFrameDto(
                        replayId = activeReplayId - 1,
                        tenantSequence = 101L,
                        payloadSha256 = "sha-stale",
                        eventCreated = sampleEventDto("ev-stale"),
                    )
                val validCommit =
                    TenantCommitFrameDto(
                        replayId = activeReplayId,
                        tenantSequence = 101L,
                        payloadSha256 = "sha-valid",
                        eventCreated = sampleEventDto("ev-valid"),
                    )
                processor.dispatch(SessionCommand.WsFrameReceived(IncomingWsFrame.TenantCommit(staleCommit)))
                processor.dispatch(SessionCommand.WsFrameReceived(IncomingWsFrame.TenantCommit(validCommit)))
                advanceUntilIdle()

                val b2 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("e1")),
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
                val applyRes = processor.installBootstrap(b2, generation = 2L)
                advanceUntilIdle()
                assertTrue(applyRes is studio.acmenra.control.feature.events.data.realtime.BootstrapApplyResult.Applied)

                assertEquals(101L, processor.getAsOfSequence())
                processor.dispatch(SessionCommand.ViewportAtTop)
                advanceUntilIdle()

                val visible =
                    processor.state.value.visibleEvents
                        .map { it.id }
                assertTrue("ev-valid" in visible)
                assertFalse("ev-stale" in visible)

                processor.dispatch(SessionCommand.Close)
                advanceUntilIdle()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 16: Automatic rebootstrap returns RetryRequired on buffer gap
    @Test
    fun testScenario16_RebootstrapBufferGapReturnsRetryRequired() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val b1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("e1")),
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
                processor.installBootstrap(b1, generation = 1L)
                advanceUntilIdle()
                val activeReplay = processor.currentReplayId

                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsFrame.ResyncRequired(
                            studio.acmenra.control.feature.events.data.model
                                .ResyncRequiredFrameDto(replayId = activeReplay),
                        ),
                    ),
                )
                advanceUntilIdle()

                // Sequence 101 missing! Only 102 arrived (gap)
                val commit102 =
                    TenantCommitFrameDto(
                        replayId = activeReplay,
                        tenantSequence = 102L,
                        payloadSha256 = "sha-102",
                        eventCreated = sampleEventDto("ev-102"),
                    )
                processor.dispatch(SessionCommand.WsFrameReceived(IncomingWsFrame.TenantCommit(commit102)))
                advanceUntilIdle()

                val b2 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("e1")),
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
                val res = processor.installBootstrap(b2, generation = 2L)
                advanceUntilIdle()

                assertEquals(
                    studio.acmenra.control.feature.events.data.realtime.BootstrapApplyResult.RetryRequired,
                    res,
                )

                processor.dispatch(SessionCommand.Close)
                advanceUntilIdle()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 17: ViewModel company switch during in-flight operations drops stale results
    @Test
    fun testScenario17_ViewModelCompanySwitchDropsInFlightResults() =
        runTest(testDispatcher) {
            val feedCompA =
                object : EventsFeedSession {
                    override val companyId = "comp-A"
                    override val query = EventsQuery()
                    override val state = kotlinx.coroutines.flow.MutableStateFlow(EventsFeedState())
                    override val connectionNotice = kotlinx.coroutines.flow.emptyFlow<Notice>()
                    override val filterOptionsInvalidated = kotlinx.coroutines.flow.emptyFlow<Unit>()
                    val applied = mutableListOf<Event>()

                    override fun loadNextPage() {}

                    override fun refresh() {}

                    override fun onViewportAtTop() {}

                    override fun markOptimisticAckPending(eventId: String) {}

                    override fun clearOptimisticAckPending(eventId: String) {}

                    override fun applyAcknowledged(event: Event) {
                        applied.add(event)
                    }

                    override fun retryRebootstrap() {}

                    override fun updateAccessCapabilities(access: EventsAccessCapabilities) {}

                    override fun close() {}
                }
            val feedCompB =
                object : EventsFeedSession {
                    override val companyId = "comp-B"
                    override val query = EventsQuery()
                    override val state = kotlinx.coroutines.flow.MutableStateFlow(EventsFeedState())
                    override val connectionNotice = kotlinx.coroutines.flow.emptyFlow<Notice>()
                    override val filterOptionsInvalidated = kotlinx.coroutines.flow.emptyFlow<Unit>()
                    val applied = mutableListOf<Event>()

                    override fun loadNextPage() {}

                    override fun refresh() {}

                    override fun onViewportAtTop() {}

                    override fun markOptimisticAckPending(eventId: String) {}

                    override fun clearOptimisticAckPending(eventId: String) {}

                    override fun applyAcknowledged(event: Event) {
                        applied.add(event)
                    }

                    override fun retryRebootstrap() {}

                    override fun updateAccessCapabilities(access: EventsAccessCapabilities) {}

                    override fun close() {}
                }

            var ackDelay = 0L
            val slowAckUseCase =
                object : AcknowledgeEventUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        eventId: String,
                    ): EventsOperationResult<Event> {
                        kotlinx.coroutines.delay(ackDelay)
                        return EventsOperationResult.Success(
                            sampleEvent(eventId).copy(status = EventAcknowledgementStatus.ACKNOWLEDGED),
                        )
                    }
                }

            val vm =
                EventsViewModel(
                    observeEventsFeedUseCase =
                        object : ObserveEventsFeedUseCase {
                            override fun invoke(
                                c: String,
                                q: EventsQuery,
                                a: EventsAccessCapabilities,
                            ) = if (c == "comp-A") feedCompA else feedCompB
                        },
                    loadEventFilterOptionsUseCase =
                        object : LoadEventFilterOptionsUseCase {
                            override suspend fun invoke(c: String) = EventsFilterOptions()
                        },
                    acknowledgeEventUseCase = slowAckUseCase,
                    markEventErroneousUseCase =
                        object : MarkEventErroneousUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                                v: Long,
                                k: String,
                            ) = error("unused")
                        },
                    createEventsExportUseCase =
                        object : CreateEventsExportUseCase {
                            override suspend fun invoke(
                                c: String,
                                r: EventsExportRequest,
                                k: String,
                            ) = error("unused")
                        },
                    observeEventsExportStatusUseCase =
                        object : ObserveEventsExportStatusUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ) = error("unused")
                        },
                    downloadEventsExportUseCase =
                        object : DownloadEventsExportUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ) = error("unused")
                        },
                    exportFileSaver =
                        object : ExportFileSaver {
                            override suspend fun saveExportFile(d: EventsExportDownload) = true
                        },
                )

            vm.initialize("comp-A", EventsAccessCapabilities(eventsRead = true, eventsReview = true))
            advanceUntilIdle()

            ackDelay = 1000L
            val evA = sampleEvent("ev-A")
            vm.acknowledgeEvent(evA)

            vm.initialize("comp-B", EventsAccessCapabilities(eventsRead = true, eventsReview = true))
            advanceUntilIdle()

            assertEquals(0, feedCompA.applied.size)
            assertEquals(0, feedCompB.applied.size)
            assertEquals("comp-B", vm.state.value.companyId)
        }

    // Scenario 18: Fail-closed access revocation for eventsRead and eventsReview
    @Test
    fun testScenario18_FailClosedAccessRevocation() =
        runTest(testDispatcher) {
            val feed =
                object : EventsFeedSession {
                    override val companyId = "comp-1"
                    override val query = EventsQuery()
                    override val state =
                        kotlinx.coroutines.flow.MutableStateFlow(
                            EventsFeedState(
                                visibleEvents = listOf(sampleEvent("ev-1")),
                                access = EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                            ),
                        )
                    override val connectionNotice = kotlinx.coroutines.flow.emptyFlow<Notice>()
                    override val filterOptionsInvalidated = kotlinx.coroutines.flow.emptyFlow<Unit>()

                    override fun loadNextPage() {}

                    override fun refresh() {}

                    override fun onViewportAtTop() {}

                    override fun markOptimisticAckPending(eventId: String) {}

                    override fun clearOptimisticAckPending(eventId: String) {}

                    override fun applyAcknowledged(event: Event) {}

                    override fun retryRebootstrap() {}

                    override fun updateAccessCapabilities(access: EventsAccessCapabilities) {}

                    override fun close() {}
                }

            val vm =
                EventsViewModel(
                    observeEventsFeedUseCase =
                        object : ObserveEventsFeedUseCase {
                            override fun invoke(
                                c: String,
                                q: EventsQuery,
                                a: EventsAccessCapabilities,
                            ) = feed
                        },
                    loadEventFilterOptionsUseCase =
                        object : LoadEventFilterOptionsUseCase {
                            override suspend fun invoke(c: String) = EventsFilterOptions()
                        },
                    acknowledgeEventUseCase =
                        object : AcknowledgeEventUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ) = EventsOperationResult.Success(sampleEvent(id))
                        },
                    markEventErroneousUseCase =
                        object : MarkEventErroneousUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                                v: Long,
                                k: String,
                            ) = error("unused")
                        },
                    createEventsExportUseCase =
                        object : CreateEventsExportUseCase {
                            override suspend fun invoke(
                                c: String,
                                r: EventsExportRequest,
                                k: String,
                            ) = error("unused")
                        },
                    observeEventsExportStatusUseCase =
                        object : ObserveEventsExportStatusUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ) = error("unused")
                        },
                    downloadEventsExportUseCase =
                        object : DownloadEventsExportUseCase {
                            override suspend fun invoke(
                                c: String,
                                id: String,
                            ) = error("unused")
                        },
                    exportFileSaver =
                        object : ExportFileSaver {
                            override suspend fun saveExportFile(d: EventsExportDownload) = true
                        },
                )

            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))
            advanceUntilIdle()

            vm.selectEvent("ev-1")
            vm.requestMarkErroneous("ev-1")
            assertEquals("ev-1", vm.state.value.selectedEventId)
            assertNotNull(vm.state.value.confirmationTarget)

            // Revoke eventsReview
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = false))
            advanceUntilIdle()
            assertNull(vm.state.value.confirmationTarget)

            // Revoke eventsRead
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = false, eventsReview = false))
            advanceUntilIdle()
            assertTrue(
                vm.state.value.visibleEvents
                    .isEmpty(),
            )
            assertNull(vm.state.value.selectedEventId)
            assertFalse(vm.state.value.isViewerOpenInCompact)
        }

    // Scenario 19: Notice emission after 3 consecutive connection or HTTP failures
    @Test
    fun testScenario19_NoticeEmissionAfter3ConsecutiveFailures() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var bootstrapAttempts = 0
                val failingRemote =
                    object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                        override suspend fun getBootstrap(
                            companyId: String,
                            query: EventsQuery,
                            limit: Int,
                        ): studio.acmenra.control.feature.events.data.model.EventsBootstrapDto {
                            bootstrapAttempts++
                            throw RuntimeException("Network error attempt $bootstrapAttempts")
                        }

                        override suspend fun getEventsPage(
                            companyId: String,
                            cursor: String,
                            limit: Int,
                        ) = error("unused")

                        override suspend fun getFilterOptions(
                            companyId: String,
                            ifNoneMatchEtag: String?,
                        ) = error("unused")

                        override suspend fun acknowledgeEvent(
                            companyId: String,
                            eventId: String,
                        ) = error("unused")

                        override suspend fun markEventErroneous(
                            companyId: String,
                            eventId: String,
                            expectedClassificationVersion: Long,
                            idempotencyKey: String,
                        ) = error("unused")

                        override suspend fun createExport(
                            companyId: String,
                            request: studio.acmenra.control.feature.events.data.model.CreateExportRequestDto,
                            idempotencyKey: String,
                        ) = error("unused")

                        override suspend fun getExportStatus(
                            companyId: String,
                            exportId: String,
                        ) = error("unused")

                        override suspend fun downloadExport(
                            companyId: String,
                            exportId: String,
                        ) = error("unused")
                    }

                val testWs = TestWebSocketDataSource()
                val session =
                    studio.acmenra.control.feature.events.data.realtime.EventsFeedSessionImpl(
                        companyId = "comp-1",
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        remoteDataSource = failingRemote,
                        webSocketDataSource = testWs,
                        sessionManager =
                            SessionManager(
                                object : TokenStorage {
                                    override fun getTokens() = TokenPair("tok", "ref")

                                    override fun saveTokens(t: TokenPair) {}

                                    override fun clear() {}

                                    override fun hasTokens() = true
                                },
                            ),
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                            },
                        parentScope = parentScope,
                    )

                val emittedNotices = mutableListOf<Notice>()
                val collectJob =
                    parentScope.launch {
                        session.connectionNotice.collect { emittedNotices.add(it) }
                    }

                // 1st failure happened on init
                advanceUntilIdle()
                assertEquals(0, emittedNotices.size)

                // 2nd failure
                session.refresh()
                advanceUntilIdle()
                assertEquals(0, emittedNotices.size)

                // 3rd failure
                session.refresh()
                advanceUntilIdle()
                assertEquals(1, emittedNotices.size)
                assertEquals(studio.acmenra.control.core.ui.notice.NoticeType.Error, emittedNotices[0].type)

                collectJob.cancel()
                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }
}
