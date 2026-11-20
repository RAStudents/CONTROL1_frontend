package studio.acmenra.control.feature.events.realtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.TokenPair
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.data.datasource.EventsApiException
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.FilterOptionsFetchResult
import studio.acmenra.control.feature.events.data.datasource.IncomingWsEnvelope
import studio.acmenra.control.feature.events.data.datasource.IncomingWsFrame
import studio.acmenra.control.feature.events.data.datasource.KtorEventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.KtorEventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.WsCloseReason
import studio.acmenra.control.feature.events.data.mapper.EventsDtoMapper
import studio.acmenra.control.feature.events.data.model.AccessInvalidatedFrameDto
import studio.acmenra.control.feature.events.data.model.AuthRequiredFrameDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventTypeOptionDto
import studio.acmenra.control.feature.events.data.model.EventsBootstrapDto
import studio.acmenra.control.feature.events.data.model.EventsFilterOptionsDto
import studio.acmenra.control.feature.events.data.model.EventsPageDto
import studio.acmenra.control.feature.events.data.model.EventsSummaryDto
import studio.acmenra.control.feature.events.data.model.ReplayCompletedFrameDto
import studio.acmenra.control.feature.events.data.model.ResyncRequiredFrameDto
import studio.acmenra.control.feature.events.data.model.SafeJsLongSerializer
import studio.acmenra.control.feature.events.data.model.TenantCommitFrameDto
import studio.acmenra.control.feature.events.data.realtime.BootstrapApplyResult
import studio.acmenra.control.feature.events.data.realtime.EventsFeedSessionImpl
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
import studio.acmenra.control.feature.events.domain.model.RealtimeOverlay
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
import kotlin.test.assertNotEquals
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
        val framesFlow = MutableSharedFlow<IncomingWsEnvelope>(extraBufferCapacity = 64)
        override val incomingFrames: Flow<IncomingWsEnvelope> = framesFlow

        var connectCalled = false
        var connectedCompanyId: String? = null
        var lastReplayId: Long? = null
        var lastAfterSequence: Long? = null
        var lastDisconnectReason: WsCloseReason? = null
        var disconnected = false
        var disconnectCount = 0
        var sentReplayRequests = mutableListOf<Pair<Long, Long>>()
        var connectCount = 0
        var connectionHistory = mutableListOf<Triple<String, Long, Long>>()
        var currentConnectionId: Long = 0L
        var failReplayRequest = false

        suspend fun emit(
            frame: IncomingWsFrame,
            connId: Long = currentConnectionId,
        ) {
            framesFlow.emit(IncomingWsEnvelope(connId, frame))
        }

        override fun connect(
            companyId: String,
            accessToken: String,
            replayId: Long,
            afterSequence: Long,
            scope: CoroutineScope,
            getClientConfirmedSeq: () -> Long,
            onDisconnected: (reason: WsCloseReason, message: String?) -> Unit,
        ): Long {
            connectCalled = true
            connectCount++
            currentConnectionId = connectCount.toLong()
            connectedCompanyId = companyId
            lastReplayId = replayId
            lastAfterSequence = afterSequence
            connectionHistory.add(Triple(companyId, replayId, afterSequence))
            return currentConnectionId
        }

        override suspend fun sendReplayRequest(
            replayId: Long,
            afterSequence: Long,
        ) {
            if (failReplayRequest) {
                throw io.ktor.utils.io.errors
                    .IOException("Failed to send replay request")
            }
            sentReplayRequests.add(replayId to afterSequence)
        }

        override suspend fun sendHeartbeat(clientConfirmedSeq: Long) {}

        override suspend fun disconnect() {
            disconnected = true
            disconnectCount++
        }
    }

    private class TestTokenStorage(
        private var storedTokens: TokenPair? = TokenPair("tok", "ref"),
    ) : TokenStorage {
        override fun getTokens(): TokenPair? = storedTokens

        override fun saveTokens(tokens: TokenPair) {
            storedTokens = tokens
        }

        override fun clear() {
            storedTokens = null
        }

        override fun hasTokens(): Boolean = storedTokens != null
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

    private fun sampleCommitDto(
        replayId: Long,
        sequence: Long,
        eventId: String,
        payloadSha256: String = "0".repeat(64),
    ): TenantCommitFrameDto =
        TenantCommitFrameDto(
            replayId = replayId,
            tenantSequence = sequence,
            payloadSha256 = payloadSha256,
            eventCreated = sampleEventDto(eventId),
        )

    private fun sampleBootstrapDto(
        cursor: Long = 100L,
        eventId: String = "ev-1",
    ): EventsBootstrapDto =
        EventsBootstrapDto(
            streamCursor = cursor,
            events =
                EventsPageDto(
                    items = listOf(sampleEventDto(eventId)),
                    nextCursor = null,
                    hasMore = false,
                    totalCount = 1,
                    asOfSequence = cursor,
                ),
            summary =
                EventsSummaryDto(
                    businessTimezone = "UTC",
                    businessDate = "2026-03-01",
                    summaryAsOfSequence = cursor,
                    todayEventsCount = 1,
                ),
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
                wsA.emit(
                    IncomingWsFrame.TenantCommit(
                        sampleCommitDto(
                            replayId = replayA,
                            sequence = 101L,
                            eventId = "ev-comp-A",
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
                        IncomingWsEnvelope(
                            connectionId = 1L,
                            frame =
                                IncomingWsFrame.AccessInvalidated(
                                    AccessInvalidatedFrameDto(reason = "COMPANY_RESTRICTED", companyId = "comp-1"),
                                ),
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
                        IncomingWsEnvelope(
                            connectionId = 1L,
                            frame =
                                IncomingWsFrame.TenantCommit(
                                    sampleCommitDto(
                                        replayId = 1L,
                                        sequence = 101L,
                                        eventId = "ev-after-invalidated",
                                    ),
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
                        IncomingWsEnvelope(
                            connectionId = 1L,
                            frame =
                                IncomingWsFrame.ReplayCompleted(
                                    ReplayCompletedFrameDto(replayId = 1L, throughSequence = 500L),
                                ),
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
                        IncomingWsEnvelope(
                            connectionId = 1L,
                            frame =
                                IncomingWsFrame.ResyncRequired(
                                    studio.acmenra.control.feature.events.data.model
                                        .ResyncRequiredFrameDto(replayId = activeReplay),
                                ),
                        ),
                    ),
                )
                advanceUntilIdle()

                // Buffer commits with duplicate 101
                val sha101 = "a".repeat(64)
                val sha102 = "b".repeat(64)
                val commit101a =
                    TenantCommitFrameDto(
                        replayId = activeReplay,
                        tenantSequence = 101L,
                        payloadSha256 = sha101,
                        eventCreated = sampleEventDto("ev-101"),
                    )
                val commit101b =
                    TenantCommitFrameDto(
                        replayId = activeReplay,
                        tenantSequence = 101L,
                        payloadSha256 = sha101,
                        eventCreated = sampleEventDto("ev-101"),
                    )
                val commit102 =
                    TenantCommitFrameDto(
                        replayId = activeReplay,
                        tenantSequence = 102L,
                        payloadSha256 = sha102,
                        eventCreated = sampleEventDto("ev-102"),
                    )

                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit101a))),
                )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit101b))),
                )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit102))),
                )
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

                    override fun applyEventMutationResult(event: Event) {}

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

                                override fun applyEventMutationResult(event: Event) {}

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
                        IncomingWsEnvelope(
                            connectionId = 1L,
                            frame =
                                IncomingWsFrame.ResyncRequired(
                                    studio.acmenra.control.feature.events.data.model
                                        .ResyncRequiredFrameDto(replayId = activeReplayId),
                                ),
                        ),
                    ),
                )
                advanceUntilIdle()

                val shaStale = "a".repeat(64)
                val shaValid = "b".repeat(64)
                val staleCommit =
                    TenantCommitFrameDto(
                        replayId = activeReplayId - 1,
                        tenantSequence = 101L,
                        payloadSha256 = shaStale,
                        eventCreated = sampleEventDto("ev-stale"),
                    )
                val validCommit =
                    TenantCommitFrameDto(
                        replayId = activeReplayId,
                        tenantSequence = 101L,
                        payloadSha256 = shaValid,
                        eventCreated = sampleEventDto("ev-valid"),
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(staleCommit))),
                )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(validCommit))),
                )
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
                        IncomingWsEnvelope(
                            connectionId = 1L,
                            frame =
                                IncomingWsFrame.ResyncRequired(
                                    studio.acmenra.control.feature.events.data.model
                                        .ResyncRequiredFrameDto(replayId = activeReplay),
                                ),
                        ),
                    ),
                )
                advanceUntilIdle()

                // Sequence 101 missing! Only 102 arrived (gap)
                val sha102 = "b".repeat(64)
                val commit102 =
                    TenantCommitFrameDto(
                        replayId = activeReplay,
                        tenantSequence = 102L,
                        payloadSha256 = sha102,
                        eventCreated = sampleEventDto("ev-102"),
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit102))),
                )
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

                    override fun applyEventMutationResult(event: Event) {
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

                    override fun applyEventMutationResult(event: Event) {
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

                    override fun applyEventMutationResult(event: Event) {}

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

    // Scenario 20: Rebootstrap success reconnects WebSocket with incremented replayId & updated sequence
    @Test
    fun testScenario20_RebootstrapReconnectsWebSocketWithNewReplayId() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var bootstrapCallCount = 0
                val remote =
                    object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                        override suspend fun getBootstrap(
                            companyId: String,
                            query: EventsQuery,
                            limit: Int,
                        ): EventsBootstrapDto {
                            bootstrapCallCount++
                            return sampleBootstrapDto(
                                cursor = bootstrapCallCount * 100L,
                                eventId = "ev-$bootstrapCallCount",
                            )
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
                    EventsFeedSessionImpl(
                        companyId = "comp-1",
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        remoteDataSource = remote,
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

                advanceUntilIdle()
                val initialReplay = testWs.lastReplayId
                assertNotNull(initialReplay)
                assertEquals(100L, testWs.lastAfterSequence)

                // Trigger RESYNC_REQUIRED to cause rebootstrap
                testWs.emit(
                    IncomingWsFrame.ResyncRequired(
                        ResyncRequiredFrameDto(replayId = initialReplay, reason = "BUFFER_GAP"),
                    ),
                )
                advanceUntilIdle()

                // Rebootstrap should have completed, incremented replayId, and reconnected WebSocket!
                val reconnectedReplay = testWs.lastReplayId
                assertNotNull(reconnectedReplay)
                assertTrue(reconnectedReplay > initialReplay)
                assertEquals(200L, testWs.lastAfterSequence)

                // Verify that new commit on new replayId applies successfully
                testWs.emit(
                    IncomingWsFrame.TenantCommit(
                        sampleCommitDto(
                            replayId = reconnectedReplay,
                            sequence = 201L,
                            eventId = "ev-new-realtime",
                        ),
                    ),
                )
                advanceUntilIdle()
                session.onViewportAtTop()
                advanceUntilIdle()

                val visible = session.state.value.visibleEvents
                assertTrue(visible.any { it.id == "ev-new-realtime" })

                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 21: Revocation of EVENTS_READ clears processor snapshot/overlay and ignores incoming commits
    @Test
    fun testScenario21_EventsReadRevocationClearsProcessorAndDropsCommits() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = {},
                        onTokenRefreshNeeded = {},
                        onSessionRevoked = {},
                        onAccessInvalidated = { _, _ -> },
                    )

                val bootstrap =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-1"), sampleEvent("ev-2")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 2,
                                asOfSequence = 100L,
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 100L,
                                todayEventsCount = 2,
                            ),
                        streamCursor = 100L,
                    )

                val result = processor.installBootstrap(bootstrap, 1L)
                assertTrue(result is BootstrapApplyResult.Applied)
                processor.dispatch(SessionCommand.ViewportAtTop)
                advanceUntilIdle()

                assertEquals(2, processor.state.value.visibleEvents.size)

                // Revoke EVENTS_READ
                processor.dispatch(SessionCommand.AccessUpdated(EventsAccessCapabilities(eventsRead = false)))
                advanceUntilIdle()

                // State must be completely cleared (fail-closed)
                assertTrue(
                    processor.state.value.visibleEvents
                        .isEmpty(),
                )
                assertTrue(
                    processor.state.value.realtimeOverlay.entries
                        .isEmpty(),
                )
                assertNull(processor.state.value.summary)
                assertEquals(0, processor.state.value.snapshotTotalCount)

                // Deliver incoming commit while EVENTS_READ is false
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            connectionId = 1L,
                            frame =
                                IncomingWsFrame.TenantCommit(
                                    sampleCommitDto(
                                        replayId = processor.currentReplayId,
                                        sequence = 101L,
                                        eventId = "ev-leak-attempt",
                                    ),
                                ),
                        ),
                    ),
                )
                processor.dispatch(SessionCommand.ViewportAtTop)
                advanceUntilIdle()

                // Commit must be dropped: no events should appear
                assertTrue(
                    processor.state.value.visibleEvents
                        .isEmpty(),
                )
                assertEquals(0, processor.state.value.snapshotTotalCount)
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 22: Incoming AUTH_REQUIRED(SESSION_REVOKED) logs out SessionManager and closes session
    @Test
    fun testScenario22_SessionRevokedTerminatesSessionManager() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var invalidatedReason: String? = null
                val testStorage =
                    object : TokenStorage {
                        private var storedTokens: TokenPair? = TokenPair("tok", "ref")

                        override fun getTokens() = storedTokens

                        override fun saveTokens(t: TokenPair) {
                            storedTokens = t
                        }

                        override fun clear() {
                            storedTokens = null
                        }

                        override fun hasTokens() = storedTokens != null
                    }
                val sessionManager = SessionManager(testStorage)
                assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)

                val testWs = TestWebSocketDataSource()
                val session =
                    EventsFeedSessionImpl(
                        companyId = "comp-1",
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        remoteDataSource =
                            object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                                override suspend fun getBootstrap(
                                    companyId: String,
                                    query: EventsQuery,
                                    limit: Int,
                                ) = sampleBootstrapDto()

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
                            },
                        webSocketDataSource = testWs,
                        sessionManager = sessionManager,
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                            },
                        accessInvalidationListener = { reason, _ ->
                            invalidatedReason = reason
                        },
                        parentScope = parentScope,
                    )

                advanceUntilIdle()

                // WS sends AUTH_REQUIRED with SESSION_REVOKED
                testWs.emit(
                    IncomingWsFrame.AuthRequired(
                        AuthRequiredFrameDto(reason = "SESSION_REVOKED"),
                    ),
                )
                advanceUntilIdle()

                // SessionManager must be Unauthenticated, tokens cleared
                assertNull(sessionManager.getSnapshot())
                assertEquals(SessionState.Unauthenticated, sessionManager.sessionState.value)
                assertEquals("SESSION_REVOKED", invalidatedReason)

                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 23: Superseded WebSocket frames are dropped by connection fencing
    @Test
    fun testScenario23_SupersededWebSocketConnectionFencing() =
        runTest(testDispatcher) {
            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content = "",
                        status = HttpStatusCode.SwitchingProtocols,
                    )
                }
            val client =
                HttpClient(mockEngine) {
                    install(io.ktor.client.plugins.websocket.WebSockets)
                }
            val dataSource = KtorEventsWebSocketDataSource(client)

            val receivedFrames = mutableListOf<IncomingWsEnvelope>()
            val collectJob =
                launch {
                    dataSource.incomingFrames.collect { envelope ->
                        receivedFrames.add(envelope)
                    }
                }

            // Connect 1
            dataSource.connect(
                companyId = "comp-1",
                accessToken = "tok-1",
                replayId = 1L,
                afterSequence = 100L,
                scope = this,
                getClientConfirmedSeq = { 100L },
                onDisconnected = { _, _ -> },
            )
            advanceUntilIdle()
            val conn1Id = dataSource.getActiveConnectionIdForTest()
            assertEquals(1L, conn1Id)

            // Connect 2 (supersedes connection 1, advances activeConnectionId to 2)
            dataSource.connect(
                companyId = "comp-1",
                accessToken = "tok-2",
                replayId = 2L,
                afterSequence = 200L,
                scope = this,
                getClientConfirmedSeq = { 200L },
                onDisconnected = { _, _ -> },
            )
            advanceUntilIdle()
            val conn2Id = dataSource.getActiveConnectionIdForTest()
            assertEquals(2L, conn2Id)

            // Simulate frames arriving from connection 1 after connection 2 is active
            val shaValid = "c".repeat(64)
            val staleCommit =
                IncomingWsFrame.TenantCommit(
                    sampleCommitDto(1L, 101L, "ev-stale").copy(payloadSha256 = shaValid),
                )
            val staleAuth = IncomingWsFrame.AuthRequired(AuthRequiredFrameDto(reason = "TOKEN_EXPIRED"))
            dataSource.emitRawFrameForTest(conn1Id, staleCommit)
            dataSource.emitRawFrameForTest(conn1Id, staleAuth)

            // Emit frame from connection 2 (active)
            val validCommit =
                IncomingWsFrame.TenantCommit(
                    sampleCommitDto(2L, 201L, "ev-valid").copy(payloadSha256 = shaValid),
                )
            dataSource.emitRawFrameForTest(conn2Id, validCommit)
            advanceUntilIdle()

            // Verify: all connection 1 frames were discarded, only connection 2 frame was received
            assertEquals(1, receivedFrames.size)
            assertEquals(validCommit, receivedFrames.first().frame)
            assertEquals(conn2Id, receivedFrames.first().connectionId)

            collectJob.cancel()
            dataSource.disconnect()
            advanceUntilIdle()

            // Also verify processor drops frames with connId < activeConnectionId
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = {},
                        onTokenRefreshNeeded = {},
                        onAccessInvalidated = { _, _ -> },
                    )
                val bootstrap =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = emptyList(),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 0,
                                asOfSequence = 100L,
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 100L,
                                todayEventsCount = 0,
                            ),
                        streamCursor = 100L,
                    )
                processor.installBootstrap(bootstrap, 1L)
                advanceUntilIdle()

                // Activate connection 2 in processor
                processor.dispatch(SessionCommand.WsConnectionActivated(2L))
                advanceUntilIdle()

                // Dispatch stale frame with connectionId = 1L (e.g. was sitting in actor channel before activation)
                processor.dispatch(SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, staleCommit)))
                advanceUntilIdle()
                // Must be discarded without affecting state
                assertTrue(
                    processor.state.value.realtimeOverlay.entries
                        .isEmpty(),
                )

                // Dispatch valid frame with connectionId = 2L and next sequence 101L
                val processorCommit =
                    IncomingWsFrame.TenantCommit(
                        sampleCommitDto(processor.currentReplayId, 101L, "ev-valid").copy(payloadSha256 = shaValid),
                    )
                processor.dispatch(SessionCommand.WsFrameReceived(IncomingWsEnvelope(2L, processorCommit)))
                processor.dispatch(SessionCommand.ViewportAtTop)
                advanceUntilIdle()
                assertEquals(1, processor.state.value.visibleEvents.size)
                assertEquals(
                    "ev-valid",
                    processor.state.value.visibleEvents
                        .first()
                        .id,
                )
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 24: Concurrent acknowledge of distinct events runs in parallel without cancellation or stuck pending ID
    @Test
    fun testScenario24_ConcurrentAcknowledgeRunsInParallel() =
        runTest(testDispatcher) {
            val eventA =
                sampleEvent("ev-A").copy(
                    status = EventAcknowledgementStatus.NEW,
                )
            val eventB =
                sampleEvent("ev-B").copy(
                    status = EventAcknowledgementStatus.NEW,
                )

            val ackCompleted = mutableListOf<String>()
            val ackUseCase =
                object : AcknowledgeEventUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        eventId: String,
                    ): EventsOperationResult<Event> {
                        delay(50L)
                        ackCompleted.add(eventId)
                        return EventsOperationResult.Success(
                            sampleEvent(eventId).copy(
                                status = EventAcknowledgementStatus.ACKNOWLEDGED,
                                classificationVersion = 2L,
                            ),
                        )
                    }
                }

            val pendingSet = mutableSetOf<String>()
            val feed =
                object : EventsFeedSession {
                    override val companyId = "comp-1"
                    override val query = EventsQuery()
                    override val state: MutableStateFlow<EventsFeedState> =
                        MutableStateFlow(
                            EventsFeedState(
                                visibleEvents = listOf(eventA, eventB),
                                access = EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                            ),
                        )
                    override val connectionNotice = kotlinx.coroutines.flow.emptyFlow<Notice>()
                    override val filterOptionsInvalidated = kotlinx.coroutines.flow.emptyFlow<Unit>()

                    override fun loadNextPage() {}

                    override fun refresh() {}

                    override fun onViewportAtTop() {}

                    override fun markOptimisticAckPending(eventId: String) {
                        pendingSet.add(eventId)
                        state.value =
                            state.value.copy(realtimeOverlay = RealtimeOverlay(pendingAckIds = pendingSet.toSet()))
                    }

                    override fun clearOptimisticAckPending(eventId: String) {
                        pendingSet.remove(eventId)
                        state.value =
                            state.value.copy(realtimeOverlay = RealtimeOverlay(pendingAckIds = pendingSet.toSet()))
                    }

                    override fun applyAcknowledged(event: Event) {
                        pendingSet.remove(event.id)
                        state.value =
                            state.value.copy(realtimeOverlay = RealtimeOverlay(pendingAckIds = pendingSet.toSet()))
                    }

                    override fun applyEventMutationResult(event: Event) {}

                    override fun retryRebootstrap() {}

                    override fun updateAccessCapabilities(access: EventsAccessCapabilities) {}

                    override fun close() {}
                }

            val vm =
                EventsViewModel(
                    observeEventsFeedUseCase =
                        object : ObserveEventsFeedUseCase {
                            override fun invoke(
                                companyId: String,
                                query: EventsQuery,
                                access: EventsAccessCapabilities,
                            ) = feed
                        },
                    loadEventFilterOptionsUseCase =
                        object : LoadEventFilterOptionsUseCase {
                            override suspend fun invoke(companyId: String) = EventsFilterOptions()
                        },
                    acknowledgeEventUseCase = ackUseCase,
                    markEventErroneousUseCase =
                        object : MarkEventErroneousUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                eventId: String,
                                expectedClassificationVersion: Long,
                                idempotencyKey: String,
                            ) = error("unused")
                        },
                    createEventsExportUseCase =
                        object : CreateEventsExportUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                request: EventsExportRequest,
                                idempotencyKey: String,
                            ) = error("unused")
                        },
                    observeEventsExportStatusUseCase =
                        object : ObserveEventsExportStatusUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                exportId: String,
                            ) = error("unused")
                        },
                    downloadEventsExportUseCase =
                        object : DownloadEventsExportUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                exportId: String,
                            ) = error("unused")
                        },
                    exportFileSaver =
                        object : ExportFileSaver {
                            override suspend fun saveExportFile(download: EventsExportDownload) = true
                        },
                )

            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))
            advanceUntilIdle()

            // Trigger acknowledge on Event A and then immediately Event B
            vm.acknowledgeEvent(eventA)
            vm.acknowledgeEvent(eventB)

            assertTrue(pendingSet.contains("ev-A"))
            assertTrue(pendingSet.contains("ev-B"))

            // Advance past the 50ms delay
            testScheduler.advanceTimeBy(70L)
            advanceUntilIdle()

            // Both must finish successfully without cancellation!
            assertTrue(ackCompleted.contains("ev-A"))
            assertTrue(ackCompleted.contains("ev-B"))
            assertFalse(pendingSet.contains("ev-A"))
            assertFalse(pendingSet.contains("ev-B"))
        }

    // Scenario 25: markEventErroneous updates card state via applyEventMutationResult when WebSocket is offline
    @Test
    fun testScenario25_MarkEventErroneousUpdatesFeedWhenWebSocketOffline() =
        runTest(testDispatcher) {
            val eventInitial = sampleEvent("ev-1").copy(classificationVersion = 1L, isErroneous = false)
            var mutationAppliedEvent: Event? = null

            val feed =
                object : EventsFeedSession {
                    override val companyId = "comp-1"
                    override val query = EventsQuery()
                    override val state: MutableStateFlow<EventsFeedState> =
                        MutableStateFlow(
                            EventsFeedState(
                                visibleEvents = listOf(eventInitial),
                                access = EventsAccessCapabilities(eventsRead = true, eventsReview = true),
                                connectionState = RealtimeConnectionState.DISCONNECTED,
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

                    override fun applyEventMutationResult(event: Event) {
                        mutationAppliedEvent = event
                        state.value = state.value.copy(visibleEvents = listOf(event))
                    }

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
                            override suspend fun invoke(companyId: String) = EventsFilterOptions()
                        },
                    acknowledgeEventUseCase =
                        object : AcknowledgeEventUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                eventId: String,
                            ) = error("unused")
                        },
                    markEventErroneousUseCase =
                        object : MarkEventErroneousUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                eventId: String,
                                expectedClassificationVersion: Long,
                                idempotencyKey: String,
                            ): EventsOperationResult<Event> =
                                EventsOperationResult.Success(
                                    sampleEvent(eventId).copy(classificationVersion = 2L, isErroneous = true),
                                )
                        },
                    createEventsExportUseCase =
                        object : CreateEventsExportUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                request: EventsExportRequest,
                                idempotencyKey: String,
                            ) = error("unused")
                        },
                    observeEventsExportStatusUseCase =
                        object : ObserveEventsExportStatusUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                exportId: String,
                            ) = error("unused")
                        },
                    downloadEventsExportUseCase =
                        object : DownloadEventsExportUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                exportId: String,
                            ) = error("unused")
                        },
                    exportFileSaver =
                        object : ExportFileSaver {
                            override suspend fun saveExportFile(download: EventsExportDownload) = true
                        },
                )

            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))
            advanceUntilIdle()

            // Confirm mark erroneous
            vm.confirmMarkErroneous("ev-1", 1L)
            advanceUntilIdle()

            // Mutation must be applied to feed despite socket being disconnected
            val applied = mutationAppliedEvent
            assertNotNull(applied)
            assertEquals("ev-1", applied.id)
            assertEquals(2L, applied.classificationVersion)
            assertTrue(applied.isErroneous)
            assertEquals(1, vm.state.value.visibleEvents.size)
            assertTrue(
                vm.state.value.visibleEvents[0]
                    .isErroneous,
            )
        }

    // Scenario 26: Competing Bootstrap generations arriving out-of-order are rejected as Stale
    @Test
    fun testScenario26_CompetingBootstrapStaleRejection() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = {},
                        onTokenRefreshNeeded = {},
                        onSessionRevoked = {},
                        onAccessInvalidated = { _, _ -> },
                    )

                val bootstrap1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-gen1")),
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

                val bootstrap2 =
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

                // Bootstrap 2 (generation 2) arrives and installs first
                val res2 = processor.installBootstrap(bootstrap2, 2L)
                assertTrue(res2 is BootstrapApplyResult.Applied)
                processor.dispatch(SessionCommand.ViewportAtTop)
                advanceUntilIdle()

                assertEquals(
                    "ev-gen2",
                    processor.state.value.visibleEvents[0]
                        .id,
                )

                // Stale Bootstrap 1 (generation 1) arrives late
                val res1 = processor.installBootstrap(bootstrap1, 1L)
                assertEquals(BootstrapApplyResult.Stale, res1)

                // Equal generation (generation 2) arrives again
                val resEqual = processor.installBootstrap(bootstrap1, 2L)
                assertEquals(BootstrapApplyResult.Stale, resEqual)

                // Data must NOT be overwritten by stale generation
                assertEquals(
                    "ev-gen2",
                    processor.state.value.visibleEvents[0]
                        .id,
                )
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 27: Bootstrap completes after EVENTS_READ revocation -> summary and counts are not published
    @Test
    fun testScenario27_BootstrapDiscardedAfterEventsReadRevocation() =
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

                val bootstrap =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-leak")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 42,
                                asOfSequence = 100L,
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 100L,
                                todayEventsCount = 99,
                            ),
                        streamCursor = 100L,
                    )

                // User loses EVENTS_READ before or during bootstrap arrival
                processor.dispatch(SessionCommand.AccessUpdated(EventsAccessCapabilities(eventsRead = false)))
                advanceUntilIdle()

                val result = processor.installBootstrap(bootstrap, generation = 1L)
                assertEquals(BootstrapApplyResult.Stale, result)
                advanceUntilIdle()

                // State must remain strictly fail-closed: no events, null summary, zero counts
                assertTrue(
                    processor.state.value.visibleEvents
                        .isEmpty(),
                )
                assertNull(processor.state.value.summary)
                assertEquals(0, processor.state.value.snapshotTotalCount)
                assertEquals(0, processor.state.value.pendingNewerCount)
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 28: Rapid access capability updates serialize without race or deadlock
    @Test
    fun testScenario28_RapidAccessCapabilitiesTransitions() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var revokedCount = 0
                var grantedCount = 0
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                        onEventsReadRevoked = { revokedCount++ },
                        onEventsReadGranted = { grantedCount++ },
                    )

                // Dispatch rapid alternating permissions
                processor.dispatch(SessionCommand.AccessUpdated(EventsAccessCapabilities(eventsRead = false)))
                processor.dispatch(SessionCommand.AccessUpdated(EventsAccessCapabilities(eventsRead = true)))
                processor.dispatch(SessionCommand.AccessUpdated(EventsAccessCapabilities(eventsRead = false)))
                processor.dispatch(SessionCommand.AccessUpdated(EventsAccessCapabilities(eventsRead = true)))
                advanceUntilIdle()

                assertEquals(2, revokedCount)
                assertEquals(2, grantedCount)
                assertTrue(processor.state.value.access.eventsRead)

                // Can successfully install bootstrap after transitions
                val bootstrap =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-active")),
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
                val res = processor.installBootstrap(bootstrap, 1L)
                assertTrue(res is BootstrapApplyResult.Applied)
                processor.dispatch(SessionCommand.ViewportAtTop)
                advanceUntilIdle()

                assertEquals(1, processor.state.value.visibleEvents.size)
                assertEquals(
                    "ev-active",
                    processor.state.value.visibleEvents[0]
                        .id,
                )
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 29: Actor close during nextReplayGeneration does not hang deferred
    @Test
    fun testScenario29_ActorCloseDuringNextReplayGenerationCompletesDeferred() =
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

                processor.close()
                advanceUntilIdle()

                // Calling nextReplayGeneration after close must complete immediately (exceptionally) without hanging
                val resultDeferred =
                    async {
                        runCatching { processor.nextReplayGeneration() }
                    }
                advanceUntilIdle()

                assertTrue(resultDeferred.isCompleted)
                val outcome = resultDeferred.await()
                assertTrue(outcome.isFailure)
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 30: Acknowledge in-flight preserves mutation update even if filter changed
    @Test
    fun testScenario30_AcknowledgeInFlightPropagatesToActiveFeedSessionOnFilterChange() =
        runTest(testDispatcher) {
            val event1 = sampleEvent("ev-1").copy(status = EventAcknowledgementStatus.NEW, classificationVersion = 1L)
            val updatedEvent1 =
                event1.copy(
                    status = EventAcknowledgementStatus.ACKNOWLEDGED,
                    classificationVersion = 2L,
                )

            val session1 = FakeFeedSession(companyId = "comp-1", initialEvents = listOf(event1))
            val session2 = FakeFeedSession(companyId = "comp-1", initialEvents = listOf(event1))

            var callCount = 0
            val feedFactory: (String, EventsQuery, EventsAccessCapabilities) -> EventsFeedSession = { _, _, _ ->
                callCount++
                if (callCount == 1) session1 else session2
            }

            val ackUseCase =
                object : AcknowledgeEventUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        eventId: String,
                    ): EventsOperationResult<Event> {
                        delay(100L)
                        return EventsOperationResult.Success(updatedEvent1)
                    }
                }

            val vm =
                createTestViewModel(
                    feedFactory = feedFactory,
                    acknowledgeEventUseCase = ackUseCase,
                )
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, eventsReview = true))
            advanceUntilIdle()
            assertEquals(session1, vm.feedSession)

            // Start acknowledge on session1
            vm.acknowledgeEvent(event1)
            // While in flight, user changes filter -> vm creates session2
            vm.onEventTypeToggled("type-99")
            advanceUntilIdle()
            assertEquals(session2, vm.feedSession)

            // Let acknowledge finish
            advanceUntilIdle()

            // Both session1 and the active session2 received applyAcknowledged!
            assertEquals(
                EventAcknowledgementStatus.ACKNOWLEDGED,
                session2.state.value.visibleEvents
                    .first { it.id == "ev-1" }
                    .status,
            )
            assertEquals(
                2L,
                session2.state.value.visibleEvents
                    .first { it.id == "ev-1" }
                    .classificationVersion,
            )
            vm.onCleared()
        }

    // Scenario 31: Invalidation during in-flight getFilterOptions does not cache stale response
    @Test
    fun testScenario31_FilterCacheInvalidationDuringInFlightHttpFetch() =
        runTest(testDispatcher) {
            var fetchAttempts = 0
            val remoteDataSource =
                object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                    override suspend fun getFilterOptions(
                        companyId: String,
                        ifNoneMatchEtag: String?,
                    ): FilterOptionsFetchResult {
                        fetchAttempts++
                        delay(100L)
                        return FilterOptionsFetchResult.Success(
                            options =
                                EventsFilterOptionsDto(
                                    revision = "rev-$fetchAttempts",
                                    eventTypes =
                                        listOf(
                                            EventTypeOptionDto("type-$fetchAttempts", "Type $fetchAttempts"),
                                        ),
                                    locations = emptyList(),
                                ),
                            etag = "etag-$fetchAttempts",
                        )
                    }

                    override suspend fun getBootstrap(
                        companyId: String,
                        query: EventsQuery,
                        limit: Int,
                    ) = error("unused")

                    override suspend fun getEventsPage(
                        companyId: String,
                        cursor: String,
                        limit: Int,
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

            val repo =
                EventsRepositoryImpl(
                    remoteDataSource = remoteDataSource,
                    webSocketDataSourceFactory = { error("unused") },
                    sessionManager = SessionManager(TestTokenStorage()),
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                        },
                )

            // Start in-flight fetch
            val job1 = async { repo.getFilterOptions("comp-1") }
            advanceUntilIdle()

            // Invalidate cache while job1 is in-flight
            repo.invalidateFilterCache("comp-1")

            val result1 = job1.await()
            assertEquals("type-1", result1.eventTypes.first().id)

            // Cache must NOT have been populated with the invalidated response. Next call must re-fetch:
            val result2 = repo.getFilterOptions("comp-1")
            assertEquals(2, fetchAttempts)
            assertEquals("type-2", result2.eventTypes.first().id)
        }

    // Scenario 32: Export reaching terminal status clears activeExportKey
    @Test
    fun testScenario32_ExportTerminalStatusClearsActiveExportKey() =
        runTest(testDispatcher) {
            val generatedKeys = mutableListOf<String>()
            val createExportUseCase =
                object : CreateEventsExportUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        request: EventsExportRequest,
                        idempotencyKey: String,
                    ): EventsExportStatus {
                        generatedKeys.add(idempotencyKey)
                        return EventsExportStatus(
                            exportId = "exp-1",
                            status = EventsExportStatusCode.COMPLETED,
                            totalRecords = 10,
                            processedRecords = 10,
                            createdAt = Instant.fromEpochMilliseconds(0),
                        )
                    }
                }

            val vm = createTestViewModel(createEventsExportUseCase = createExportUseCase)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))
            advanceUntilIdle()

            // First export
            vm.startExport()
            advanceUntilIdle()
            assertEquals(1, generatedKeys.size)
            val key1 = generatedKeys[0]

            // Second export with same parameters
            vm.startExport()
            advanceUntilIdle()
            assertEquals(2, generatedKeys.size)
            val key2 = generatedKeys[1]

            // Idempotency keys must be distinct because previous export reached COMPLETED
            assertNotEquals(key1, key2)
            vm.onCleared()
        }

    // Scenario 33: EventsDtoMapper throws IllegalArgumentException on malformed timestamps
    @Test
    fun testScenario33_EventsDtoMapperStrictDateParsing() {
        val invalidOccurredDto =
            sampleEventDto("ev-bad").copy(
                occurredAt = "malformed-date-string",
            )
        assertFailsWith<IllegalArgumentException> {
            EventsDtoMapper.mapEvent(invalidOccurredDto)
        }

        val invalidReceivedDto =
            sampleEventDto("ev-bad-received").copy(
                receivedAt = "invalid-iso-string",
            )
        assertFailsWith<IllegalArgumentException> {
            EventsDtoMapper.mapEvent(invalidReceivedDto)
        }
    }

    // Scenario 34: TenantCommitFrameDto strictly enforces 64-character hexadecimal payload_sha256
    @Test
    fun testScenario34_TenantCommitFrameDtoStrictPayloadSha256Validation() {
        val validSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val validFrame =
            TenantCommitFrameDto(
                replayId = 1L,
                tenantSequence = 100L,
                payloadSha256 = validSha,
                eventCreated = sampleEventDto("ev-1"),
            )
        // Must succeed without throwing and return true
        assertTrue(validFrame.validateEnvelope())

        // Empty sha returns false
        val emptyShaFrame = validFrame.copy(payloadSha256 = "")
        assertFalse(emptyShaFrame.validateEnvelope())

        // Too short returns false
        val shortShaFrame = validFrame.copy(payloadSha256 = "abc123")
        assertFalse(shortShaFrame.validateEnvelope())

        // Non-hex characters returns false
        val nonHexSha = "g".repeat(64)
        val nonHexFrame = validFrame.copy(payloadSha256 = nonHexSha)
        assertFalse(nonHexFrame.validateEnvelope())
    }

    // Scenario 35: Feed session cleanup on terminal invalidation cancels sessionJob and is idempotent
    @Test
    fun testScenario35_FeedSessionCleanupCancelsSessionJobAndIsIdempotent() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val testWs = TestWebSocketDataSource()

                val sessionManager = SessionManager(TestTokenStorage())
                val session =
                    EventsFeedSessionImpl(
                        companyId = "comp-1",
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        remoteDataSource =
                            object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                                override suspend fun getBootstrap(
                                    companyId: String,
                                    query: EventsQuery,
                                    limit: Int,
                                ) = sampleBootstrapDto()

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
                            },
                        webSocketDataSource = testWs,
                        sessionManager = sessionManager,
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                            },
                        accessInvalidationListener = { _, _ -> },
                        parentScope = parentScope,
                    )
                advanceUntilIdle()

                // First close
                session.close()
                advanceUntilIdle()
                assertEquals(1, testWs.disconnectCount)

                // Second close must be idempotent (no duplicate cleanup)
                session.close()
                advanceUntilIdle()
                assertEquals(1, testWs.disconnectCount)
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 36: TenantCommitFrameDto strictly requires payload_sha256 in JSON deserialization
    @Test
    fun testScenario36_TenantCommitFrameDtoRequiresPayloadSha256InJson() {
        val eventJson = Json.encodeToString(sampleEventDto("ev-1"))
        val validJson =
            """
            {
                "replay_id": 1,
                "tenant_sequence": 100,
                "payload_sha256": "${"0".repeat(64)}",
                "event_created": $eventJson
            }
            """.trimIndent()
        val decoded = Json.decodeFromString<TenantCommitFrameDto>(validJson)
        assertEquals(100L, decoded.tenantSequence)
        assertEquals("0".repeat(64), decoded.payloadSha256)

        val missingShaJson =
            """
            {
                "replay_id": 1,
                "tenant_sequence": 100,
                "event_created": $eventJson
            }
            """.trimIndent()
        assertFailsWith<SerializationException> {
            Json.decodeFromString<TenantCommitFrameDto>(missingShaJson)
        }
    }

    // Scenario 37: Realtime commit with same sequence but different payloadSha256 triggers rebootstrap
    @Test
    fun testScenario37_RealtimeCommitDuplicateSequenceHashMismatchTriggersRebootstrap() =
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
                        onTokenRefreshNeeded = {},
                        onAccessInvalidated = { _, _ -> },
                    )
                val bootstrap =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = emptyList(),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 0,
                                asOfSequence = 100L,
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 100L,
                                todayEventsCount = 0,
                            ),
                        streamCursor = 100L,
                    )
                processor.installBootstrap(bootstrap, 1L)
                advanceUntilIdle()

                val sha1 = "1".repeat(64)
                val commit1 =
                    TenantCommitFrameDto(
                        replayId = processor.currentReplayId,
                        tenantSequence = 101L,
                        payloadSha256 = sha1,
                        eventCreated = sampleEventDto("ev-101"),
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit1))),
                )
                advanceUntilIdle()

                assertFalse(rebootstrapTriggered)

                // Dispatch another commit for sequence 101 but with a different hash (integrity violation)
                val sha2 = "2".repeat(64)
                val commitDuplicateMismatch =
                    TenantCommitFrameDto(
                        replayId = processor.currentReplayId,
                        tenantSequence = 101L,
                        payloadSha256 = sha2,
                        eventCreated = sampleEventDto("ev-101-corrupted"),
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commitDuplicateMismatch)),
                    ),
                )
                advanceUntilIdle()

                assertTrue(rebootstrapTriggered)
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 38: Ambiguous network error during startExport preserves activeExportKey on retry
    @Test
    fun testScenario38_AmbiguousNetworkErrorPreservesExportIdempotencyKey() =
        runTest(testDispatcher) {
            val usedKeys = mutableListOf<String>()
            var failWithNetworkError = true
            val createExportUseCase =
                object : CreateEventsExportUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        request: EventsExportRequest,
                        idempotencyKey: String,
                    ): EventsExportStatus {
                        usedKeys.add(idempotencyKey)
                        if (failWithNetworkError) {
                            throw io.ktor.utils.io.errors
                                .IOException("Network timeout")
                        }
                        return EventsExportStatus(
                            exportId = "exp-1",
                            status = EventsExportStatusCode.QUEUED,
                            totalRecords = 1,
                            processedRecords = 0,
                            createdAt = Instant.fromEpochMilliseconds(0),
                        )
                    }
                }

            val vm = createTestViewModel(createEventsExportUseCase = createExportUseCase)
            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))
            advanceUntilIdle()

            // First attempt fails with network error
            vm.startExport()
            advanceUntilIdle()
            assertEquals(1, usedKeys.size)
            assertNotNull(vm.state.value.exportError)

            // Retry attempt: network recovered
            failWithNetworkError = false
            vm.startExport()
            advanceUntilIdle()
            assertEquals(2, usedKeys.size)

            // Keys must match because network error is ambiguous and must reuse key
            assertEquals(usedKeys[0], usedKeys[1])
            assertEquals(
                EventsExportStatusCode.QUEUED,
                vm.state.value.exportJobStatus
                    ?.status,
            )
            vm.onCleared()
        }

    // Scenario 39: Rapid true -> false -> true EVENTS_READ transition serializes disconnect and reconnect
    @Test
    fun testScenario39_RapidEventsReadTransitionSerializesDisconnectAndReconnect() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val testWs = TestWebSocketDataSource()
                val session =
                    EventsFeedSessionImpl(
                        companyId = "comp-1",
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        remoteDataSource =
                            object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                                override suspend fun getBootstrap(
                                    companyId: String,
                                    query: EventsQuery,
                                    limit: Int,
                                ) = sampleBootstrapDto()

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
                            },
                        webSocketDataSource = testWs,
                        sessionManager = SessionManager(TestTokenStorage()),
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                            },
                        accessInvalidationListener = { _, _ -> },
                        parentScope = parentScope,
                    )
                advanceUntilIdle()
                assertEquals(1, testWs.connectCount)

                // Rapid transition: true -> false -> true
                session.updateAccessCapabilities(EventsAccessCapabilities(eventsRead = false))
                session.updateAccessCapabilities(EventsAccessCapabilities(eventsRead = true))
                advanceUntilIdle()

                // Must have disconnected old connection and reconnected new connection
                assertEquals(1, testWs.disconnectCount)
                assertEquals(2, testWs.connectCount)
                assertTrue(session.state.value.access.eventsRead)

                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 40: Revoking EVENTS_READ during loadNextPage resets isLoadingNextPage to false
    @Test
    fun testScenario40_RevokingEventsReadDuringLoadNextPageResetsLoadingState() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val pageStarted = CompletableDeferred<Unit>()
                val pageBlocker = CompletableDeferred<EventsPageDto>()
                val testWs = TestWebSocketDataSource()
                val session =
                    EventsFeedSessionImpl(
                        companyId = "comp-1",
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        remoteDataSource =
                            object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                                override suspend fun getBootstrap(
                                    companyId: String,
                                    query: EventsQuery,
                                    limit: Int,
                                ) = sampleBootstrapDto().copy(
                                    events =
                                        EventsPageDto(
                                            items = listOf(sampleEventDto("ev-1")),
                                            nextCursor = "cursor-next",
                                            hasMore = true,
                                            totalCount = 10,
                                            asOfSequence = 100L,
                                        ),
                                )

                                override suspend fun getEventsPage(
                                    companyId: String,
                                    cursor: String,
                                    limit: Int,
                                ): EventsPageDto {
                                    pageStarted.complete(Unit)
                                    return pageBlocker.await()
                                }

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
                            },
                        webSocketDataSource = testWs,
                        sessionManager = SessionManager(TestTokenStorage()),
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                            },
                        accessInvalidationListener = { _, _ -> },
                        parentScope = parentScope,
                    )
                advanceUntilIdle()

                // Trigger loadNextPage
                session.loadNextPage()
                advanceUntilIdle()
                assertTrue(pageStarted.isCompleted)
                assertTrue(session.state.value.isLoadingNextPage)

                // Revoke access while pagination is pending
                session.updateAccessCapabilities(EventsAccessCapabilities(eventsRead = false))
                advanceUntilIdle()

                // isLoadingNextPage must be reset to false immediately
                assertFalse(session.state.value.isLoadingNextPage)

                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 41: Failure in sendReplayRequest triggers rebootstrap instead of hanging in replaying
    @Test
    fun testScenario41_SendReplayRequestFailureTriggersRebootstrap() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val testWs = TestWebSocketDataSource()
                var bootstrapCallCount = 0
                val session =
                    EventsFeedSessionImpl(
                        companyId = "comp-1",
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        remoteDataSource =
                            object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                                override suspend fun getBootstrap(
                                    companyId: String,
                                    query: EventsQuery,
                                    limit: Int,
                                ): EventsBootstrapDto {
                                    bootstrapCallCount++
                                    return sampleBootstrapDto(cursor = 100L * bootstrapCallCount)
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
                            },
                        webSocketDataSource = testWs,
                        sessionManager = SessionManager(TestTokenStorage()),
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                            },
                        accessInvalidationListener = { _, _ -> },
                        parentScope = parentScope,
                    )
                advanceUntilIdle()
                assertEquals(1, bootstrapCallCount)

                // Configure replay requests to fail
                testWs.failReplayRequest = true

                // Send a replay request by receiving a REPLAY_COMPLETED through a higher sequence
                val currentReplay = testWs.lastReplayId ?: 1L
                testWs.emit(
                    IncomingWsFrame.ReplayCompleted(
                        ReplayCompletedFrameDto(replayId = currentReplay, throughSequence = 150L),
                    ),
                )
                advanceUntilIdle()

                // Sending replay request failed -> must trigger rebootstrap!
                assertTrue(bootstrapCallCount >= 2)

                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }

    private class FakeFeedSession(
        override val companyId: String = "comp-1",
        override val query: EventsQuery = EventsQuery(),
        initialEvents: List<Event> = emptyList(),
    ) : EventsFeedSession {
        override val state =
            MutableStateFlow(
                EventsFeedState(
                    visibleEvents = initialEvents,
                    snapshotTotalCount = initialEvents.size,
                    access = EventsAccessCapabilities(eventsRead = true, eventsReview = true, reportsExport = true),
                ),
            )
        val noticeFlow = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
        override val connectionNotice: Flow<Notice> = noticeFlow
        val filterInvalidatedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        override val filterOptionsInvalidated: Flow<Unit> = filterInvalidatedFlow

        override fun loadNextPage() {}

        override fun refresh() {}

        override fun onViewportAtTop() {}

        override fun markOptimisticAckPending(eventId: String) {
            val nextAck = state.value.realtimeOverlay.pendingAckIds + eventId
            state.update { it.copy(realtimeOverlay = it.realtimeOverlay.copy(pendingAckIds = nextAck)) }
        }

        override fun clearOptimisticAckPending(eventId: String) {
            val nextAck = state.value.realtimeOverlay.pendingAckIds - eventId
            state.update { it.copy(realtimeOverlay = it.realtimeOverlay.copy(pendingAckIds = nextAck)) }
        }

        override fun applyAcknowledged(event: Event) {
            val nextAck = state.value.realtimeOverlay.pendingAckIds - event.id
            val nextEvents = state.value.visibleEvents.map { if (it.id == event.id) event else it }
            state.update {
                it.copy(
                    visibleEvents = nextEvents,
                    realtimeOverlay = it.realtimeOverlay.copy(pendingAckIds = nextAck),
                )
            }
        }

        override fun applyEventMutationResult(event: Event) {
            val nextEvents = state.value.visibleEvents.map { if (it.id == event.id) event else it }
            state.update { it.copy(visibleEvents = nextEvents) }
        }

        override fun retryRebootstrap() {}

        override fun updateAccessCapabilities(access: EventsAccessCapabilities) {
            state.update { it.copy(access = access) }
        }

        override fun close() {}
    }

    private fun createTestViewModel(
        feed: EventsFeedSession = FakeFeedSession(),
        feedFactory: ((String, EventsQuery, EventsAccessCapabilities) -> EventsFeedSession)? = null,
        loadEventFilterOptionsUseCase: LoadEventFilterOptionsUseCase =
            object : LoadEventFilterOptionsUseCase {
                override suspend fun invoke(companyId: String) = EventsFilterOptions()
            },
        acknowledgeEventUseCase: AcknowledgeEventUseCase =
            object : AcknowledgeEventUseCase {
                override suspend fun invoke(
                    companyId: String,
                    eventId: String,
                ): EventsOperationResult<Event> = error("unused")
            },
        markEventErroneousUseCase: MarkEventErroneousUseCase =
            object : MarkEventErroneousUseCase {
                override suspend fun invoke(
                    companyId: String,
                    eventId: String,
                    expectedClassificationVersion: Long,
                    idempotencyKey: String,
                ): EventsOperationResult<Event> = error("unused")
            },
        createEventsExportUseCase: CreateEventsExportUseCase =
            object : CreateEventsExportUseCase {
                override suspend fun invoke(
                    companyId: String,
                    request: EventsExportRequest,
                    idempotencyKey: String,
                ): EventsExportStatus = error("unused")
            },
        observeEventsExportStatusUseCase: ObserveEventsExportStatusUseCase =
            object : ObserveEventsExportStatusUseCase {
                override suspend fun invoke(
                    companyId: String,
                    exportId: String,
                ): EventsExportStatus = error("unused")
            },
        downloadEventsExportUseCase: DownloadEventsExportUseCase =
            object : DownloadEventsExportUseCase {
                override suspend fun invoke(
                    companyId: String,
                    exportId: String,
                ): EventsExportDownload = error("unused")
            },
        exportFileSaver: ExportFileSaver =
            object : ExportFileSaver {
                override suspend fun saveExportFile(download: EventsExportDownload) = true
            },
    ): EventsViewModel {
        val feedUseCase =
            object : ObserveEventsFeedUseCase {
                override fun invoke(
                    companyId: String,
                    query: EventsQuery,
                    access: EventsAccessCapabilities,
                ): EventsFeedSession = feedFactory?.invoke(companyId, query, access) ?: feed
            }
        return EventsViewModel(
            observeEventsFeedUseCase = feedUseCase,
            loadEventFilterOptionsUseCase = loadEventFilterOptionsUseCase,
            acknowledgeEventUseCase = acknowledgeEventUseCase,
            markEventErroneousUseCase = markEventErroneousUseCase,
            createEventsExportUseCase = createEventsExportUseCase,
            observeEventsExportStatusUseCase = observeEventsExportStatusUseCase,
            downloadEventsExportUseCase = downloadEventsExportUseCase,
            exportFileSaver = exportFileSaver,
        )
    }
}
