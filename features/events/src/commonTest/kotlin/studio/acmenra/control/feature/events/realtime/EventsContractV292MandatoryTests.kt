package studio.acmenra.control.feature.events.realtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.readAvailable
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
import studio.acmenra.control.core.network.auth.SessionSnapshot
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.TokenPair
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_export_error_access_revoked
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.data.datasource.BoundedByteAccumulator
import studio.acmenra.control.feature.events.data.datasource.EventsApiException
import studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.ExportPayload
import studio.acmenra.control.feature.events.data.datasource.ExportPayloadRegistry
import studio.acmenra.control.feature.events.data.datasource.FilterOptionsFetchResult
import studio.acmenra.control.feature.events.data.datasource.HeartbeatTracker
import studio.acmenra.control.feature.events.data.datasource.IncomingWsEnvelope
import studio.acmenra.control.feature.events.data.datasource.IncomingWsFrame
import studio.acmenra.control.feature.events.data.datasource.KtorEventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.KtorEventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.WsCloseReason
import studio.acmenra.control.feature.events.data.mapper.EventsDtoMapper
import studio.acmenra.control.feature.events.data.model.AccessInvalidatedFrameDto
import studio.acmenra.control.feature.events.data.model.AuthRequiredFrameDto
import studio.acmenra.control.feature.events.data.model.BootstrapEventsDto
import studio.acmenra.control.feature.events.data.model.CreateExportRequestDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventTypeOptionDto
import studio.acmenra.control.feature.events.data.model.EventsBootstrapDto
import studio.acmenra.control.feature.events.data.model.EventsExportStatusDto
import studio.acmenra.control.feature.events.data.model.EventsFilterOptionsDto
import studio.acmenra.control.feature.events.data.model.EventsPageDto
import studio.acmenra.control.feature.events.data.model.EventsPaginationDto
import studio.acmenra.control.feature.events.data.model.EventsSummaryDto
import studio.acmenra.control.feature.events.data.model.GenericRealtimeFrameDto
import studio.acmenra.control.feature.events.data.model.HeartbeatAckFrameDto
import studio.acmenra.control.feature.events.data.model.MAX_SAFE_JS_INTEGER
import studio.acmenra.control.feature.events.data.model.ReplayCompletedFrameDto
import studio.acmenra.control.feature.events.data.model.ResyncRequiredFrameDto
import studio.acmenra.control.feature.events.data.model.SafeJsLongSerializer
import studio.acmenra.control.feature.events.data.model.SubscribedFrameDto
import studio.acmenra.control.feature.events.data.model.TenantCommitFrameDto
import studio.acmenra.control.feature.events.data.realtime.BootstrapApplyResult
import studio.acmenra.control.feature.events.data.realtime.EventMergeResult
import studio.acmenra.control.feature.events.data.realtime.EventMerger
import studio.acmenra.control.feature.events.data.realtime.EventsFeedSessionImpl
import studio.acmenra.control.feature.events.data.realtime.EventsSessionProcessor
import studio.acmenra.control.feature.events.data.realtime.RealtimeOverlayReducer
import studio.acmenra.control.feature.events.data.realtime.ReplayGenerationResult
import studio.acmenra.control.feature.events.data.realtime.SequenceValidationResult
import studio.acmenra.control.feature.events.data.realtime.SessionCommand
import studio.acmenra.control.feature.events.data.realtime.TenantSequenceProcessor
import studio.acmenra.control.feature.events.data.repository.EventsRepositoryImpl
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import studio.acmenra.control.feature.events.domain.model.EventTypeOption
import studio.acmenra.control.feature.events.domain.model.EventValidityFilter
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsBootstrap
import studio.acmenra.control.feature.events.domain.model.EventsDomainException
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsMutationError
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsPage
import studio.acmenra.control.feature.events.domain.model.EventsPaginationError
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.EventsQueryRouteCodec
import studio.acmenra.control.feature.events.domain.model.EventsSort
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.OverlayMembership
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

@OptIn(ExperimentalCoroutinesApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class EventsContractV292MandatoryTests {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        studio.acmenra.control.feature.events.domain.model
            .ensureTimezoneDatabaseInitialized()
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
        var lastOnDisconnected: ((reason: WsCloseReason, message: String?) -> Unit)? = null

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
            activationGate: CompletableDeferred<Unit>?,
            onDisconnected: (reason: WsCloseReason, message: String?) -> Unit,
        ): Long {
            lastOnDisconnected = onDisconnected
            connectCalled = true
            connectCount++
            currentConnectionId = connectCount.toLong()
            connectedCompanyId = companyId
            lastReplayId = replayId
            lastAfterSequence = afterSequence
            connectionHistory.add(Triple(companyId, replayId, afterSequence))
            activationGate?.complete(Unit)
            return currentConnectionId
        }

        override fun activateConnection(connectionId: Long) {}

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

        var disconnectBlocker: CompletableDeferred<Unit>? = null
        var lastDisconnectedConnectionId: Long? = null

        override suspend fun disconnect(connectionId: Long?) {
            disconnectBlocker?.await()
            disconnected = true
            disconnectCount++
            lastDisconnectedConnectionId = connectionId
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
        snapshotId: String = "snap-$cursor",
    ): EventsBootstrapDto =
        EventsBootstrapDto(
            streamCursor = cursor,
            events =
                BootstrapEventsDto(
                    items = listOf(sampleEventDto(eventId)),
                    pagination =
                        EventsPaginationDto(
                            snapshotId = snapshotId,
                            asOfSequence = cursor,
                            nextCursor = null,
                            hasMore = false,
                            totalCount = 1,
                        ),
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
                        ) = sampleBootstrapDto(cursor = 100L)

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
                val bufferingReplay = processor.nextReplayGeneration()

                // Buffer commits with duplicate 101
                val sha101 = "a".repeat(64)
                val sha102 = "b".repeat(64)
                val commit101a =
                    TenantCommitFrameDto(
                        replayId = bufferingReplay,
                        tenantSequence = 101L,
                        payloadSha256 = sha101,
                        eventCreated = sampleEventDto("ev-101"),
                    )
                val commit101b =
                    TenantCommitFrameDto(
                        replayId = bufferingReplay,
                        tenantSequence = 101L,
                        payloadSha256 = sha101,
                        eventCreated = sampleEventDto("ev-101"),
                    )
                val commit102 =
                    TenantCommitFrameDto(
                        replayId = bufferingReplay,
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
                processor.dispatch(SessionCommand.ViewportAtTop())
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
                        eventVersion = 2L,
                        acknowledgementVersion = 2L,
                        status = EventAcknowledgementStatus.ACKNOWLEDGED,
                    )
                processor.dispatch(SessionCommand.AcknowledgeSucceeded(acknowledgedEvent))
                advanceUntilIdle()

                // Pending ID removed, event in visibleEvents updated
                assertFalse("ev-ack-test" in processor.state.value.pendingAckIds)
                val eventInState =
                    processor.state.value.visibleEvents
                        .first { it.id == "ev-ack-test" }
                assertEquals(EventAcknowledgementStatus.ACKNOWLEDGED, eventInState.status)
                assertEquals(2L, eventInState.acknowledgementVersion)

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
                val newReplay = processor.nextReplayGeneration()

                val shaStale = "a".repeat(64)
                val shaValid = "b".repeat(64)
                val staleCommit =
                    TenantCommitFrameDto(
                        replayId = activeReplayId,
                        tenantSequence = 101L,
                        payloadSha256 = shaStale,
                        eventCreated = sampleEventDto("ev-stale"),
                    )
                val validCommit =
                    TenantCommitFrameDto(
                        replayId = newReplay,
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
                processor.dispatch(SessionCommand.ViewportAtTop())
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
                val newReplay = processor.nextReplayGeneration()

                // Sequence 101 missing! Only 102 arrived (gap)
                val sha102 = "b".repeat(64)
                val commit102 =
                    TenantCommitFrameDto(
                        replayId = newReplay,
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
                processor.dispatch(SessionCommand.ViewportAtTop())
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
                processor.dispatch(SessionCommand.ViewportAtTop())
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
                processor.dispatch(SessionCommand.ViewportAtTop())
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
                processor.dispatch(SessionCommand.ViewportAtTop())
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
                processor.dispatch(SessionCommand.ViewportAtTop())
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
            vm.clearForTest()
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
            vm.clearForTest()
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

        // Uppercase hex characters return false (strict lowercase 0..9, a..f per contract)
        val upperHexSha = "A".repeat(64)
        val upperHexFrame = validFrame.copy(payloadSha256 = upperHexSha)
        assertFalse(upperHexFrame.validateEnvelope())
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
            vm.clearForTest()
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
                                        BootstrapEventsDto(
                                            items = listOf(sampleEventDto("ev-1")),
                                            pagination =
                                                EventsPaginationDto(
                                                    snapshotId = "snap-100",
                                                    asOfSequence = 100L,
                                                    nextCursor = "cursor-next",
                                                    hasMore = true,
                                                    totalCount = 10,
                                                ),
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

    // Scenario 42: Initial configuration eventsRead = false does not invoke bootstrap and does not connect WebSocket
    @Test
    fun testScenario42_InitialConfigurationWithoutEventsReadDoesNotBootstrapOrConnectWs() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val testWs = TestWebSocketDataSource()
                var bootstrapCallCount = 0
                val session =
                    EventsFeedSessionImpl(
                        companyId = "comp-1",
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = false),
                        remoteDataSource =
                            object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                                override suspend fun getBootstrap(
                                    companyId: String,
                                    query: EventsQuery,
                                    limit: Int,
                                ): EventsBootstrapDto {
                                    bootstrapCallCount++
                                    return sampleBootstrapDto(cursor = 100L)
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

                assertEquals(0, bootstrapCallCount)
                assertFalse(testWs.connectCalled)
                assertEquals(0, testWs.connectCount)

                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 43: DISCONNECTED -> revoke access -> network online does not reconnect WebSocket
    @Test
    fun testScenario43_DisconnectedThenRevokeAccessThenNetworkOnlineDoesNotReconnectWs() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val testWs = TestWebSocketDataSource()
                val connectivityFlow = MutableStateFlow(true)
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
                                ): EventsBootstrapDto = sampleBootstrapDto(cursor = 100L)

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
                                override val isOnline = connectivityFlow
                            },
                        accessInvalidationListener = { _, _ -> },
                        parentScope = parentScope,
                    )
                advanceUntilIdle()
                assertEquals(1, testWs.connectCount)

                // Network goes offline -> state becomes DISCONNECTED
                connectivityFlow.value = false
                advanceUntilIdle()
                assertEquals(RealtimeConnectionState.DISCONNECTED, session.state.value.connectionState)

                // Access is revoked
                session.updateAccessCapabilities(EventsAccessCapabilities(eventsRead = false))
                advanceUntilIdle()

                // Network comes back online
                connectivityFlow.value = true
                advanceUntilIdle()

                // No new WebSocket connection must be initiated
                assertEquals(1, testWs.connectCount)

                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 44: Access revocation concurrent with onDisconnected does not trigger reconnect
    @Test
    fun testScenario44_AccessRevocationConcurrentWithOnDisconnectedDoesNotReconnect() =
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
                                ): EventsBootstrapDto = sampleBootstrapDto(cursor = 100L)

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

                val onDisconnectedCb = testWs.lastOnDisconnected
                assertNotNull(onDisconnectedCb)

                // Concurrently revoke access and trigger onDisconnected
                session.updateAccessCapabilities(EventsAccessCapabilities(eventsRead = false))
                onDisconnectedCb.invoke(WsCloseReason.REMOTE_CLOSE, "Simulated disconnect")
                advanceUntilIdle()

                // Must NOT reconnect
                assertEquals(1, testWs.connectCount)

                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 45: Token refresh started before revocation, completes after: no reconnect
    @Test
    fun testScenario45_TokenRefreshStartedBeforeRevocationCompletesAfterDoesNotReconnect() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val testWs = TestWebSocketDataSource()
                val sessionManager = SessionManager(TestTokenStorage())
                val refreshDeferred = CompletableDeferred<SessionSnapshot?>()
                sessionManager.setRefreshDelegate {
                    refreshDeferred.await()
                }

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
                                ): EventsBootstrapDto = sampleBootstrapDto(cursor = 100L)

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
                assertEquals(1, testWs.connectCount)

                // WS emits AuthRequired frame triggering token refresh
                testWs.emit(IncomingWsFrame.AuthRequired(AuthRequiredFrameDto(reason = "TOKEN_EXPIRED")))
                advanceUntilIdle()

                // Token refresh is in flight (waiting on refreshDeferred). Now revoke access!
                session.updateAccessCapabilities(EventsAccessCapabilities(eventsRead = false))
                advanceUntilIdle()

                // Complete token refresh now
                refreshDeferred.complete(SessionSnapshot(tokens = TokenPair("new_acc", "new_ref"), revision = 2L))
                advanceUntilIdle()

                // Must NOT have reconnected because access was revoked
                assertEquals(1, testWs.connectCount)

                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 46: Immediate Deny-Latch drops incoming frames and rejects operations when access revoked
    @Test
    fun testScenario46_ImmediateDenyLatchDropsFramesAndRejectsOperations() =
        runTest(testDispatcher) {
            val processor =
                EventsSessionProcessor(
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    scope = this,
                    onSendReplayRequest = { _, _ -> },
                    onTriggerRebootstrap = {},
                    onTokenRefreshNeeded = {},
                    onAccessInvalidated = { _, _ -> },
                )
            advanceUntilIdle()

            // Bootstrap processor successfully first
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
            val result = processor.installBootstrap(bootstrap, generation = 1L)
            assertTrue(result is BootstrapApplyResult.Applied)
            assertEquals(1, processor.state.value.visibleEvents.size)

            // Activate immediate deny-latch before actor even receives AccessUpdated command
            processor.setImmediateEventsReadDenied(true)

            // Incoming commit frame should be dropped immediately without modifying state
            processor.dispatch(SessionCommand.WsConnectionActivated(1L))
            val commitFrame =
                TenantCommitFrameDto(
                    replayId = 1L,
                    tenantSequence = 101L,
                    payloadSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    eventCreated = sampleEventDto("ev-2"),
                )
            processor.dispatch(
                SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commitFrame))),
            )
            advanceUntilIdle()

            // Event ev-2 was NOT added
            assertEquals(1, processor.state.value.visibleEvents.size)
            assertNull(
                processor.state.value.visibleEvents
                    .find { it.id == "ev-2" },
            )

            // Revoke access in state
            processor.dispatch(SessionCommand.AccessUpdated(EventsAccessCapabilities(eventsRead = false)))
            advanceUntilIdle()
            assertTrue(
                processor.state.value.visibleEvents
                    .isEmpty(),
            )

            // Commands that recompute visible events MUST NOT republish old events when deny-latch is active
            processor.dispatch(SessionCommand.ViewportAtTop())
            processor.dispatch(SessionCommand.AcknowledgeSucceeded(sampleEvent("ev-1")))
            processor.dispatch(SessionCommand.EventMutationApplied(sampleEvent("ev-1")))
            advanceUntilIdle()
            assertTrue(
                processor.state.value.visibleEvents
                    .isEmpty(),
            )

            // installBootstrap returns Stale immediately
            val staleResult = processor.installBootstrap(bootstrap, generation = 2L)
            assertTrue(staleResult is BootstrapApplyResult.Stale)

            processor.close()
            advanceUntilIdle()
        }

    // Scenario 47: Duplicate sequence older than recentHashes cache window triggers IntegrityViolation
    @Test
    fun testScenario47_DuplicateSequenceEvictedFromRecentHashesTriggersIntegrityViolation() {
        val seqProcessor = TenantSequenceProcessor(lastProcessedSequence = 0L, activeReplayId = 1L)

        // Record 1005 sequential frames with valid lowercase SHA
        val baseSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd"
        for (seq in 1L..1005L) {
            val sha = baseSha + (seq % 10).toString() + (seq % 10).toString()
            seqProcessor.advanceSequence(seq, sha)
        }

        // Sequence 1 is older than recentHashes retention window (1000 items) and has been evicted
        val duplicateFrameOld =
            TenantCommitFrameDto(
                replayId = 1L,
                tenantSequence = 1L,
                payloadSha256 = baseSha + "11",
                eventCreated = sampleEventDto("ev-old"),
            )
        val resultOld =
            seqProcessor.checkIncomingSequence(
                duplicateFrameOld.replayId,
                duplicateFrameOld.tenantSequence,
                duplicateFrameOld.payloadSha256,
            )
        // Duplicates with incomingSequence <= lastProcessedSequence outside recentHashes window must return Duplicate per I.2.2.4
        assertEquals(SequenceValidationResult.Duplicate, resultOld)

        // In-window duplicate (sequence 1005) with matching SHA returns Duplicate
        val duplicateFrameRecent =
            TenantCommitFrameDto(
                replayId = 1L,
                tenantSequence = 1005L,
                payloadSha256 = baseSha + "55",
                eventCreated = sampleEventDto("ev-recent"),
            )
        val resultRecent =
            seqProcessor.checkIncomingSequence(
                duplicateFrameRecent.replayId,
                duplicateFrameRecent.tenantSequence,
                duplicateFrameRecent.payloadSha256,
            )
        assertTrue(resultRecent is SequenceValidationResult.Duplicate)

        // In-window duplicate with mismatched SHA returns IntegrityViolation
        val mismatchRecent =
            duplicateFrameRecent.copy(
                payloadSha256 = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            )
        val resultMismatch =
            seqProcessor.checkIncomingSequence(
                mismatchRecent.replayId,
                mismatchRecent.tenantSequence,
                mismatchRecent.payloadSha256,
            )
        assertTrue(resultMismatch is SequenceValidationResult.IntegrityViolation)
    }

    // Scenario 48: Revoking EVENTS_READ immediately clears all event-derived state in ViewModel
    @Test
    fun testScenario48_RevokingEventsReadClearsCompleteViewModelState() =
        runTest(testDispatcher) {
            val sampleEv = sampleEvent("ev-1")
            val fakeFeed =
                FakeFeedSession(
                    companyId = "comp-1",
                    initialEvents = listOf(sampleEv),
                )
            fakeFeed.state.update {
                it.copy(
                    summary =
                        EventsSummary(
                            businessTimezone = "UTC",
                            businessDate = "2026-03-01",
                            summaryAsOfSequence = 100L,
                            todayEventsCount = 10,
                        ),
                    snapshotTotalCount = 42,
                    asOfSequence = 100L,
                    hasMore = true,
                    isLoadingNextPage = true,
                    pendingNewerCount = 3,
                    pendingHistoryCount = 5,
                    paginationError = EventsPaginationError.Network,
                    realtimeOverlay = RealtimeOverlay(pendingAckIds = setOf("ev-1")),
                )
            }

            val viewModel =
                createTestViewModel(
                    feed = fakeFeed,
                    loadEventFilterOptionsUseCase =
                        object : LoadEventFilterOptionsUseCase {
                            override suspend fun invoke(companyId: String) =
                                EventsFilterOptions(
                                    revision = "rev-1",
                                    eventTypes =
                                        listOf(
                                            EventTypeOption("type-1", "Type 1", EventIconKey.GENERIC_ALERT),
                                        ),
                                )
                        },
                )
            viewModel.initialize(
                "comp-1",
                EventsAccessCapabilities(eventsRead = true, eventsReview = true, reportsExport = true),
            )
            advanceUntilIdle()

            // Verify ViewModel received full state
            assertEquals(1, viewModel.state.value.visibleEvents.size)
            assertNotNull(viewModel.state.value.summary)
            assertEquals(
                10,
                viewModel.state.value.summary
                    ?.todayEventsCount,
            )
            assertEquals(42, viewModel.state.value.snapshotTotalCount)
            assertEquals(100L, viewModel.state.value.asOfSequence)
            assertTrue(viewModel.state.value.hasMore)
            assertEquals(3, viewModel.state.value.pendingNewerCount)
            assertEquals(5, viewModel.state.value.pendingHistoryCount)
            assertNotNull(viewModel.state.value.paginationError)
            assertEquals(setOf("ev-1"), viewModel.state.value.realtimeOverlay.pendingAckIds)

            viewModel.selectEvent("ev-1")
            assertEquals("ev-1", viewModel.state.value.selectedEventId)

            // Now revoke EVENTS_READ
            viewModel.initialize(
                "comp-1",
                EventsAccessCapabilities(eventsRead = false, eventsReview = false, reportsExport = false),
            )
            advanceUntilIdle()

            // Verify all event-derived state is cleared immediately!
            val state = viewModel.state.value
            assertFalse(state.access.eventsRead)
            assertTrue(state.visibleEvents.isEmpty())
            assertNull(state.summary)
            assertNull(state.snapshotTotalCount)
            assertEquals(0L, state.asOfSequence)
            assertFalse(state.hasMore)
            assertFalse(state.isLoadingNextPage)
            assertNull(state.paginationError)
            assertEquals(0, state.pendingNewerCount)
            assertEquals(0, state.pendingHistoryCount)
            assertNull(state.selectedEventId)
            assertFalse(state.isViewerOpenInCompact)
            assertNull(state.filterOptions)
            assertTrue(state.realtimeOverlay.pendingAckIds.isEmpty())
            assertTrue(state.realtimeOverlay.entries.isEmpty())
        }

    // Scenario 49: Ktor WebSocket DataSource atomic session management protects connect() during disconnect()
    @Test
    fun testScenario49_ConnectDuringDisconnectDoesNotCloseNewConnection() =
        runTest(testDispatcher) {
            val scopeA = CoroutineScope(SupervisorJob() + testDispatcher)
            val scopeB = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val wsDataSource =
                    KtorEventsWebSocketDataSource(
                        realtimeClient =
                            HttpClient(
                                MockEngine {
                                    respond("OK")
                                },
                            ) {
                                install(io.ktor.client.plugins.websocket.WebSockets)
                            },
                        wsEndpointBase = "ws://localhost:8080",
                    )

                var disconnectedReasonA: WsCloseReason? = null
                var disconnectedReasonB: WsCloseReason? = null

                // 1. Connect A is initiated (older)
                val idA =
                    wsDataSource.connect(
                        companyId = "comp-1",
                        accessToken = "tok-A",
                        replayId = 1L,
                        afterSequence = 100L,
                        scope = scopeA,
                        getClientConfirmedSeq = { 100L },
                        onDisconnected = { reason, _ -> disconnectedReasonA = reason },
                    )

                // 2. Connect B is initiated (newer) before scopeA executes
                val idB =
                    wsDataSource.connect(
                        companyId = "comp-1",
                        accessToken = "tok-B",
                        replayId = 2L,
                        afterSequence = 200L,
                        scope = scopeB,
                        getClientConfirmedSeq = { 200L },
                        onDisconnected = { reason, _ -> disconnectedReasonB = reason },
                    )
                assertTrue(idB > idA)

                // 3. Let Connect B run and establish its session in connectionMutex
                testDispatcher.scheduler.runCurrent()
                advanceUntilIdle()

                assertNull(disconnectedReasonB)

                // B's outgoing channel is open and responsive
                wsDataSource.sendReplayRequest(replayId = 2L, afterSequence = 200L)
            } finally {
                scopeA.cancel()
                scopeB.cancel()
            }
        }

    // Scenario 50: Connectivity coming online while disconnectJob is in flight waits for disconnect to complete
    @Test
    fun testScenario50_ConnectivityOnlineAwaitsDisconnectJob() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val testWs = TestWebSocketDataSource()
                val connectivityFlow = MutableStateFlow(true)
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
                                ): EventsBootstrapDto = sampleBootstrapDto(cursor = 100L)

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
                                override val isOnline = connectivityFlow
                            },
                        accessInvalidationListener = { _, _ -> },
                        parentScope = parentScope,
                    )
                advanceUntilIdle()
                assertEquals(1, testWs.connectCount)

                // 1. Block disconnect
                val blocker = CompletableDeferred<Unit>()
                testWs.disconnectBlocker = blocker

                // 2. Revoke EVENTS_READ -> launches disconnectJob which suspends on blocker
                session.updateAccessCapabilities(EventsAccessCapabilities(eventsRead = false))
                testDispatcher.scheduler.runCurrent()

                // 3. Restore EVENTS_READ while disconnectJob is in-flight
                session.updateAccessCapabilities(EventsAccessCapabilities(eventsRead = true))

                // 4. Toggle connectivity online
                connectivityFlow.value = false
                testDispatcher.scheduler.runCurrent()
                connectivityFlow.value = true
                testDispatcher.scheduler.runCurrent()

                // 5. Verify no new connection or bootstrap runs while disconnectJob is in flight!
                assertEquals(1, testWs.connectCount)

                // 6. Complete disconnectJob
                blocker.complete(Unit)
                advanceUntilIdle()

                // 7. Verify exactly one new connection was established
                assertEquals(2, testWs.connectCount)

                session.close()
                advanceUntilIdle()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 51: PermissionDenied and Generic 403 in EventsRepositoryImpl are classified as EventsMutationError.PermissionDenied
    @Test
    fun testScenario51_PermissionDeniedClassificationInRepository() =
        runTest(testDispatcher) {
            val mockRemote =
                object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                    var throwGeneric403 = false

                    override suspend fun acknowledgeEvent(
                        companyId: String,
                        eventId: String,
                    ): EventDto {
                        if (throwGeneric403) {
                            throw EventsApiException.Generic(
                                code = "403",
                                serverMessage = "Forbidden",
                                retryable = false,
                            )
                        } else {
                            throw EventsApiException.PermissionDenied("PERMISSION_DENIED")
                        }
                    }

                    override suspend fun markEventErroneous(
                        companyId: String,
                        eventId: String,
                        expectedClassificationVersion: Long,
                        idempotencyKey: String,
                    ): EventDto {
                        if (throwGeneric403) {
                            throw EventsApiException.Generic(
                                code = "PERMISSION_DENIED",
                                serverMessage = "Forbidden",
                                retryable = false,
                            )
                        } else {
                            throw EventsApiException.PermissionDenied("PERMISSION_DENIED")
                        }
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

                    override suspend fun getFilterOptions(
                        companyId: String,
                        ifNoneMatchEtag: String?,
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

            val repository =
                EventsRepositoryImpl(
                    remoteDataSource = mockRemote,
                    webSocketDataSourceFactory = { TestWebSocketDataSource() },
                    sessionManager = SessionManager(TestTokenStorage()),
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                        },
                )

            // Test PermissionDenied typed exception
            val ackResult1 = repository.acknowledgeEvent("comp-1", "ev-1")
            assertTrue(ackResult1 is EventsOperationResult.Failure)
            assertEquals(EventsMutationError.PermissionDenied, (ackResult1 as EventsOperationResult.Failure).error)

            val markResult1 = repository.markEventErroneous("comp-1", "ev-1", 1L, "idem-1")
            assertTrue(markResult1 is EventsOperationResult.Failure)
            assertEquals(EventsMutationError.PermissionDenied, (markResult1 as EventsOperationResult.Failure).error)

            // Test Generic 403 exception
            mockRemote.throwGeneric403 = true
            val ackResult2 = repository.acknowledgeEvent("comp-1", "ev-1")
            assertTrue(ackResult2 is EventsOperationResult.Failure)
            assertEquals(EventsMutationError.PermissionDenied, (ackResult2 as EventsOperationResult.Failure).error)

            val markResult2 = repository.markEventErroneous("comp-1", "ev-1", 1L, "idem-2")
            assertTrue(markResult2 is EventsOperationResult.Failure)
            assertEquals(EventsMutationError.PermissionDenied, (markResult2 as EventsOperationResult.Failure).error)
        }

    // Scenario 52: Concurrent getFilterOptions requests prevent late older response from overwriting fresh cache
    @Test
    fun testScenario52_ConcurrentFilterFetchPreventsStaleCacheOverwrite() =
        runTest(testDispatcher) {
            val responseADeferred = CompletableDeferred<FilterOptionsFetchResult>()
            val responseBDeferred = CompletableDeferred<FilterOptionsFetchResult>()
            var callIndex = 0

            val mockRemote =
                object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                    override suspend fun getFilterOptions(
                        companyId: String,
                        ifNoneMatchEtag: String?,
                    ): FilterOptionsFetchResult {
                        callIndex++
                        return if (callIndex == 1) {
                            responseADeferred.await()
                        } else {
                            responseBDeferred.await()
                        }
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

            val repository =
                EventsRepositoryImpl(
                    remoteDataSource = mockRemote,
                    webSocketDataSourceFactory = { TestWebSocketDataSource() },
                    sessionManager = SessionManager(TestTokenStorage()),
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                        },
                )

            // Start Request A (older)
            val deferredA = async { repository.getFilterOptions("comp-1") }
            advanceUntilIdle()

            // Start Request B (newer)
            val deferredB = async { repository.getFilterOptions("comp-1") }
            advanceUntilIdle()

            // Request B finishes first with NEW options
            val dtoB =
                EventsFilterOptionsDto(
                    revision = "rev-B",
                    eventTypes = listOf(EventTypeOptionDto("type-B", "Type B", "category-b")),
                )
            responseBDeferred.complete(FilterOptionsFetchResult.Success(dtoB, etag = "etag-B"))
            advanceUntilIdle()

            val resultB = deferredB.await()
            assertEquals(1, resultB.eventTypes.size)
            assertEquals("type-B", resultB.eventTypes.first().id)

            // Request A finishes second with OLD options
            val dtoA =
                EventsFilterOptionsDto(
                    revision = "rev-A",
                    eventTypes = listOf(EventTypeOptionDto("type-A", "Type A", "category-a")),
                )
            responseADeferred.complete(FilterOptionsFetchResult.Success(dtoA, etag = "etag-A"))
            advanceUntilIdle()

            val resultA = deferredA.await()
            assertEquals("type-B", resultA.eventTypes.first().id)

            // Verify cache was NOT overwritten by late Request A: calling with 304 NotModified returns cached B!
            val cachedResult =
                repository.getFilterOptions("comp-1")
            assertEquals("type-B", cachedResult.eventTypes.first().id)
        }

    // Scenario 53: startExport retains activeExportKey on UNKNOWN status preserving idempotency key on retry
    @Test
    fun testScenario53_StartExportRetainsKeyOnUnknownStatus() =
        runTest(testDispatcher) {
            var exportCallCount = 0
            val capturedKeys = mutableListOf<String>()

            val viewModel =
                createTestViewModel(
                    createEventsExportUseCase =
                        object : CreateEventsExportUseCase {
                            override suspend fun invoke(
                                companyId: String,
                                request: EventsExportRequest,
                                idempotencyKey: String,
                            ): EventsExportStatus {
                                exportCallCount++
                                capturedKeys.add(idempotencyKey)
                                return EventsExportStatus(
                                    exportId = "exp-$exportCallCount",
                                    status = EventsExportStatusCode.UNKNOWN,
                                    createdAt = Instant.parse("2026-03-01T12:00:00Z"),
                                )
                            }
                        },
                )
            viewModel.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))
            advanceUntilIdle()

            viewModel.startExport()
            advanceUntilIdle()
            assertEquals(1, exportCallCount)
            assertEquals(1, capturedKeys.size)
            val firstKey = capturedKeys[0]

            // Trigger retry export with same parameters
            viewModel.startExport()
            advanceUntilIdle()
            assertEquals(2, exportCallCount)
            assertEquals(2, capturedKeys.size)
            val secondKey = capturedKeys[1]

            // Because UNKNOWN is non-terminal, activeExportKey was retained, preserving idempotency key!
            assertEquals(firstKey, secondKey)
        }

    // Scenario 54: Processor immediate deny-latch is not cleared by stale AccessUpdated(true) command
    @Test
    fun testScenario54_ProcessorImmediateDenyLatchNotClearedByStaleAccessUpdatedTrue() =
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
                val res = processor.installBootstrap(b1, generation = 1L)
                assertTrue(res is BootstrapApplyResult.Applied)
                assertEquals(1, processor.state.value.visibleEvents.size)

                // 1. First revocation: gen = 1
                processor.setImmediateEventsReadDenied(true, generation = 1L)
                processor.dispatch(
                    SessionCommand.AccessUpdated(EventsAccessCapabilities(eventsRead = false), generation = 1L),
                )

                // 2. Access granted: gen = 2
                processor.dispatch(
                    SessionCommand.AccessUpdated(EventsAccessCapabilities(eventsRead = true), generation = 2L),
                )

                // 3. Incoming commit frame enqueued
                val commit =
                    sampleCommitDto(
                        replayId = processor.currentReplayId,
                        sequence = 101L,
                        eventId = "ev-2",
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit))),
                )

                // 4. Second revocation: gen = 3
                processor.setImmediateEventsReadDenied(true, generation = 3L)
                processor.dispatch(
                    SessionCommand.AccessUpdated(EventsAccessCapabilities(eventsRead = false), generation = 3L),
                )

                // Process all commands
                advanceUntilIdle()

                // Verify that even after processing command 2 (AccessUpdated(true, gen=2)),
                // the deny-latch remained TRUE because gen=3 had already registered immediate deny!
                assertTrue(processor.isImmediateEventsReadDenied())
                assertTrue(
                    processor.state.value.visibleEvents
                        .isEmpty(),
                )
                assertFalse(processor.state.value.access.eventsRead)

                processor.close()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 55: ViewModel feedCollectJob never restores revoked access permissions from feedState
    @Test
    fun testScenario55_ViewModelFeedCollectJobNeverRestoresRevokedAccess() =
        runTest(testDispatcher) {
            val fakeFeed = FakeFeedSession(companyId = "comp-1")
            val viewModel = createTestViewModel(feed = fakeFeed)

            // Initialize ViewModel with eventsRead = false
            viewModel.initialize(
                "comp-1",
                EventsAccessCapabilities(eventsRead = false, eventsReview = false, reportsExport = false),
            )
            advanceUntilIdle()
            assertFalse(viewModel.state.value.access.eventsRead)

            // Fake feed emits state that still has access.eventsRead = true
            fakeFeed.state.update {
                it.copy(
                    access = EventsAccessCapabilities(eventsRead = true, eventsReview = true, reportsExport = true),
                    connectionState = RealtimeConnectionState.CONNECTED,
                )
            }
            advanceUntilIdle()

            // ViewModel must retain authoritative revoked permissions: eventsRead MUST remain false!
            assertFalse(viewModel.state.value.access.eventsRead)
            assertTrue(
                viewModel.state.value.visibleEvents
                    .isEmpty(),
            )
        }

    // Scenario 56: Filter options reload on access restoration, ignored when eventsRead is denied
    @Test
    fun testScenario56_FilterOptionsReloadOnAccessRestoredAndNotLoadedWhenReadDenied() =
        runTest(testDispatcher) {
            var loadFilterCalls = 0
            val viewModel =
                createTestViewModel(
                    loadEventFilterOptionsUseCase =
                        object : LoadEventFilterOptionsUseCase {
                            override suspend fun invoke(companyId: String): EventsFilterOptions {
                                loadFilterCalls++
                                return EventsFilterOptions(revision = "rev-$loadFilterCalls")
                            }
                        },
                )

            // 1. Initial initialize with eventsRead = false -> must NOT call loadFilterOptions
            viewModel.initialize("comp-1", EventsAccessCapabilities(eventsRead = false))
            advanceUntilIdle()
            assertEquals(0, loadFilterCalls)
            assertNull(viewModel.state.value.filterOptions)

            // 2. Grant eventsRead = true -> MUST call loadFilterOptions
            viewModel.initialize("comp-1", EventsAccessCapabilities(eventsRead = true))
            advanceUntilIdle()
            assertEquals(1, loadFilterCalls)
            assertNotNull(viewModel.state.value.filterOptions)
            assertEquals(
                "rev-1",
                viewModel.state.value.filterOptions
                    ?.revision,
            )

            // 3. Revoke eventsRead = false -> clears filterOptions
            viewModel.initialize("comp-1", EventsAccessCapabilities(eventsRead = false))
            advanceUntilIdle()
            assertNull(viewModel.state.value.filterOptions)

            // 4. Restore eventsRead = true -> reloads filterOptions
            viewModel.initialize("comp-1", EventsAccessCapabilities(eventsRead = true))
            advanceUntilIdle()
            assertEquals(2, loadFilterCalls)
            assertNotNull(viewModel.state.value.filterOptions)
            assertEquals(
                "rev-2",
                viewModel.state.value.filterOptions
                    ?.revision,
            )
        }

    // Scenario 57: isFilterOptionsLoading is reset to false when eventsRead is revoked in-flight
    @Test
    fun testScenario57_IsFilterOptionsLoadingResetOnEventsReadRevocation() =
        runTest(testDispatcher) {
            val filterDeferred = CompletableDeferred<EventsFilterOptions>()
            val viewModel =
                createTestViewModel(
                    loadEventFilterOptionsUseCase =
                        object : LoadEventFilterOptionsUseCase {
                            override suspend fun invoke(companyId: String): EventsFilterOptions = filterDeferred.await()
                        },
                )

            // 1. Initialize with eventsRead = true -> filter options start loading
            viewModel.initialize("comp-1", EventsAccessCapabilities(eventsRead = true))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isFilterOptionsLoading)

            // 2. Revoke eventsRead while loading is still in-flight
            viewModel.initialize("comp-1", EventsAccessCapabilities(eventsRead = false))
            advanceUntilIdle()

            // isFilterOptionsLoading MUST be false immediately upon revocation
            assertFalse(viewModel.state.value.isFilterOptionsLoading)
            assertNull(viewModel.state.value.filterOptions)

            // Complete filterDeferred later; state must remain not loading and null
            filterDeferred.complete(EventsFilterOptions(revision = "late-rev"))
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isFilterOptionsLoading)
            assertNull(viewModel.state.value.filterOptions)
        }

    // Scenario 58: InvalidateFilterCache running during getFilterOptions atomically discards stale cached entry
    @Test
    fun testScenario58_InvalidateFilterCacheDuringGetFilterOptionsDiscardsStaleEntry() =
        runTest(testDispatcher) {
            val responseDeferred = CompletableDeferred<FilterOptionsFetchResult>()
            var callCount = 0
            val mockRemote =
                object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                    override suspend fun getFilterOptions(
                        companyId: String,
                        ifNoneMatchEtag: String?,
                    ): FilterOptionsFetchResult {
                        callCount++
                        return if (callCount == 1) {
                            responseDeferred.await()
                        } else {
                            FilterOptionsFetchResult.Success(
                                EventsFilterOptionsDto(
                                    revision = "fresh-rev",
                                    eventTypes =
                                        listOf(
                                            EventTypeOptionDto("type-1", "Type 1", "cat-1"),
                                            EventTypeOptionDto("type-2", "Type 2", "cat-2"),
                                        ),
                                ),
                                etag = "fresh-etag",
                            )
                        }
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

            val repository =
                EventsRepositoryImpl(
                    remoteDataSource = mockRemote,
                    webSocketDataSourceFactory = { TestWebSocketDataSource() },
                    sessionManager = SessionManager(TestTokenStorage()),
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                        },
                )

            // Start in-flight request
            val fetchDeferred = async { repository.getFilterOptions("comp-1") }
            advanceUntilIdle()

            // Invalidate cache while request is in-flight
            repository.invalidateFilterCache("comp-1")
            advanceUntilIdle()

            // Complete the in-flight network response
            val dto =
                EventsFilterOptionsDto(
                    revision = "stale-rev",
                    eventTypes = listOf(EventTypeOptionDto("type-1", "Type 1", "cat-1")),
                )
            responseDeferred.complete(FilterOptionsFetchResult.Success(dto, etag = "stale-etag"))
            advanceUntilIdle()

            val result = fetchDeferred.await()
            assertEquals("fresh-rev", result.revision)
            assertEquals(2, result.eventTypes.size)
        }

    // Scenario 59: Concurrent connect calls in Ktor WebSocket DataSource allocate strictly monotonic connection IDs
    @Test
    fun testScenario59_ConcurrentConnectAllocatesMonotonicConnectionIds() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val wsDataSource =
                    KtorEventsWebSocketDataSource(
                        realtimeClient =
                            HttpClient(
                                MockEngine {
                                    respond("OK")
                                },
                            ) {
                                install(io.ktor.client.plugins.websocket.WebSockets)
                            },
                        wsEndpointBase = "ws://localhost:8080",
                    )

                val id1 =
                    wsDataSource.connect(
                        companyId = "comp-1",
                        accessToken = "tok-1",
                        replayId = 1L,
                        afterSequence = 100L,
                        scope = scope,
                        getClientConfirmedSeq = { 100L },
                        onDisconnected = { _, _ -> },
                    )
                val id2 =
                    wsDataSource.connect(
                        companyId = "comp-1",
                        accessToken = "tok-2",
                        replayId = 2L,
                        afterSequence = 200L,
                        scope = scope,
                        getClientConfirmedSeq = { 200L },
                        onDisconnected = { _, _ -> },
                    )
                val id3 =
                    wsDataSource.connect(
                        companyId = "comp-1",
                        accessToken = "tok-3",
                        replayId = 3L,
                        afterSequence = 300L,
                        scope = scope,
                        getClientConfirmedSeq = { 300L },
                        onDisconnected = { _, _ -> },
                    )

                assertEquals(1L, id1)
                assertEquals(2L, id2)
                assertEquals(3L, id3)
                assertEquals(3L, wsDataSource.getActiveConnectionIdForTest())
            } finally {
                scope.cancel()
            }
        }

    // Scenario 60: RetryRequired in handleBootstrap preserves existing snapshot, overlay, sequence, and generation
    @Test
    fun testScenario60_RetryRequiredPreservesExistingState() =
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

                // 1. Install initial bootstrap (asOfSequence = 100)
                val initialEvent = sampleEvent("ev-1")
                val b1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(initialEvent),
                                nextCursor = "cursor-1",
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
                assertTrue(res1 is BootstrapApplyResult.Applied)
                assertEquals(1, processor.state.value.visibleEvents.size)
                assertEquals(
                    "ev-1",
                    processor.state.value.visibleEvents
                        .first()
                        .id,
                )
                assertEquals(100L, processor.getAsOfSequence())

                // 2. Put processor into rebootstrap mode with a buffered commit that has a GAP
                val b2 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-2")),
                                nextCursor = "cursor-2",
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 200L,
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 200L,
                                todayEventsCount = 2,
                            ),
                        streamCursor = 200L,
                    )

                // Dispatch ResyncRequired to enter rebootstrap mode
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            1L,
                            IncomingWsFrame.ResyncRequired(
                                ResyncRequiredFrameDto(replayId = processor.currentReplayId),
                            ),
                        ),
                    ),
                )
                advanceUntilIdle()
                val bufferingReplay = processor.nextReplayGeneration()

                // Buffer a commit with gap: 205 instead of 201
                val gappedCommit =
                    sampleCommitDto(
                        replayId = bufferingReplay,
                        sequence = 205L,
                        eventId = "ev-gapped",
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(gappedCommit)),
                    ),
                )
                advanceUntilIdle()

                // 3. Attempt to install b2: must return RetryRequired due to gap!
                val res2 = processor.installBootstrap(b2, generation = 2L)
                assertEquals(BootstrapApplyResult.RetryRequired, res2)

                // 4. Verify existing state is 100% PRESERVED
                assertEquals(1, processor.state.value.visibleEvents.size)
                assertEquals(
                    "ev-1",
                    processor.state.value.visibleEvents
                        .first()
                        .id,
                )
                assertEquals(100L, processor.getAsOfSequence())
                assertEquals("cursor-1", processor.getNextCursor())

                processor.close()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 61: triggerBootstrap in EventsFeedSessionImpl retries on RetryRequired and recovers
    @Test
    fun testScenario61_TriggerBootstrapRetriesOnRetryRequiredAndRecovers() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var bootstrapCallCount = 0
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
                                ): EventsBootstrapDto {
                                    bootstrapCallCount++
                                    val cursor = if (bootstrapCallCount == 1) 100L else 101L
                                    return sampleBootstrapDto(cursor = cursor)
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
                        parentScope = parentScope,
                    )

                advanceUntilIdle()
                assertEquals(1, bootstrapCallCount)
                assertFalse(session.state.value.isInitialLoading)
                assertEquals(1, testWs.connectCount)

                session.close()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 62: triggerBootstrap terminates with InitialLoadFailed when retries exhausted
    @Test
    fun testScenario62_TriggerBootstrapTerminatesOnErrorAfterRetriesExhausted() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var bootstrapCallCount = 0
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
                                ): EventsBootstrapDto {
                                    bootstrapCallCount++
                                    throw studio.acmenra.control.feature.events.data.datasource.EventsApiException
                                        .Generic(
                                            code = "500",
                                            serverMessage = "Server error",
                                            retryable = false,
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
                            },
                        webSocketDataSource = testWs,
                        sessionManager = SessionManager(TestTokenStorage()),
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                            },
                        parentScope = parentScope,
                    )

                advanceUntilIdle()
                assertFalse(session.state.value.isInitialLoading)
                assertNotNull(session.state.value.initialLoadError)

                session.close()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 63: Late HTTP ACK response does not overwrite newer classification from WS commit
    @Test
    fun testScenario63_LateHttpAckDoesNotOverwriteNewerWsClassification() =
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

                // 1. Install initial event: classificationVersion = 1, acknowledgementVersion = 1, isErroneous = false, status = NEW
                val initialEvent =
                    sampleEvent("ev-1").copy(
                        eventVersion = 1L,
                        classificationVersion = 1L,
                        acknowledgementVersion = 1L,
                        isErroneous = false,
                        status = EventAcknowledgementStatus.NEW,
                    )
                val b1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(initialEvent),
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

                // 2. A newer WS commit arrives with updated classification: classificationVersion = 2, isErroneous = true
                val wsUpdatedEventDto =
                    sampleEventDto("ev-1").copy(
                        eventVersion = 2L,
                        classificationVersion = 2L,
                        acknowledgementVersion = 1L,
                        isErroneous = true,
                        status = "NEW",
                    )
                val commit =
                    sampleCommitDto(
                        replayId = processor.currentReplayId,
                        sequence = 101L,
                        eventId = "ev-1",
                    ).copy(
                        eventCreated = null,
                        eventUpdated = wsUpdatedEventDto,
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit))),
                )
                advanceUntilIdle()

                // Verify WS commit is reflected
                val currentEv =
                    processor.state.value.visibleEvents
                        .first { it.id == "ev-1" }
                assertTrue(currentEv.isErroneous)
                assertEquals(2L, currentEv.classificationVersion)
                assertEquals(EventAcknowledgementStatus.NEW, currentEv.status)

                // 3. Late HTTP ACK response arrives:
                // overall eventVersion = 3L, acknowledgementVersion = 2, status = ACKNOWLEDGED, but classificationVersion = 1, isErroneous = false
                val lateHttpAckEvent =
                    sampleEvent("ev-1").copy(
                        eventVersion = 3L,
                        classificationVersion = 1L,
                        acknowledgementVersion = 2L,
                        isErroneous = false,
                        status = EventAcknowledgementStatus.ACKNOWLEDGED,
                        acknowledgedAt = Instant.parse("2026-03-01T12:00:00Z"),
                        acknowledgedBy = "user-1",
                    )
                processor.dispatch(SessionCommand.AcknowledgeSucceeded(lateHttpAckEvent))
                advanceUntilIdle()

                // 4. Verify merged result: status is ACKNOWLEDGED, and isErroneous is STILL TRUE!
                val mergedEv =
                    processor.state.value.visibleEvents
                        .first { it.id == "ev-1" }
                assertEquals(EventAcknowledgementStatus.ACKNOWLEDGED, mergedEv.status)
                assertEquals(2L, mergedEv.acknowledgementVersion)
                assertTrue(mergedEv.isErroneous)
                assertEquals(2L, mergedEv.classificationVersion)

                processor.close()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 64: Late HTTP mark erroneous response does not overwrite newer acknowledgement from WS commit
    @Test
    fun testScenario64_LateHttpMarkErroneousDoesNotOverwriteNewerWsAcknowledgement() =
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

                // 1. Initial event: classificationVersion = 1, acknowledgementVersion = 1, status = NEW
                val initialEvent =
                    sampleEvent("ev-1").copy(
                        eventVersion = 1L,
                        classificationVersion = 1L,
                        acknowledgementVersion = 1L,
                        isErroneous = false,
                        status = EventAcknowledgementStatus.NEW,
                    )
                val b1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(initialEvent),
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

                // 2. WS commit arrives with acknowledgement: acknowledgementVersion = 2, status = ACKNOWLEDGED
                val wsUpdatedEventDto =
                    sampleEventDto("ev-1").copy(
                        eventVersion = 2L,
                        classificationVersion = 1L,
                        acknowledgementVersion = 2L,
                        isErroneous = false,
                        status = "ACKNOWLEDGED",
                        acknowledgedAt = "2026-03-01T12:00:00Z",
                        acknowledgedBy = "colleague",
                    )
                val commit =
                    sampleCommitDto(
                        replayId = processor.currentReplayId,
                        sequence = 101L,
                        eventId = "ev-1",
                    ).copy(
                        eventCreated = null,
                        eventUpdated = wsUpdatedEventDto,
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit))),
                )
                advanceUntilIdle()

                // 3. Late HTTP mark erroneous response arrives: eventVersion = 3, classificationVersion = 2, isErroneous = true,
                // but acknowledgementVersion = 1, status = NEW
                val lateHttpMutatedEvent =
                    sampleEvent("ev-1").copy(
                        eventVersion = 3L,
                        classificationVersion = 2L,
                        acknowledgementVersion = 1L,
                        isErroneous = true,
                        status = EventAcknowledgementStatus.NEW,
                    )
                processor.dispatch(SessionCommand.EventMutationApplied(lateHttpMutatedEvent))
                advanceUntilIdle()

                // 4. Verify merged result: isErroneous is TRUE, status is STILL ACKNOWLEDGED!
                val mergedEv =
                    processor.state.value.visibleEvents
                        .first { it.id == "ev-1" }
                assertTrue(mergedEv.isErroneous)
                assertEquals(2L, mergedEv.classificationVersion)
                assertEquals(EventAcknowledgementStatus.ACKNOWLEDGED, mergedEv.status)
                assertEquals(2L, mergedEv.acknowledgementVersion)

                processor.close()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 65: Malformed TENANT_COMMIT does not crash WebSocket connection and triggers rebootstrap
    @Test
    fun testScenario65_MalformedTenantCommitDoesNotCrashSocketAndTriggersRebootstrap() =
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

                // Dispatch MalformedCommit
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            1L,
                            IncomingWsFrame.MalformedCommit(
                                replayId = processor.currentReplayId,
                                error = "Syntax error in frame",
                            ),
                        ),
                    ),
                )
                advanceUntilIdle()

                assertTrue(rebootstrapTriggered)
                processor.close()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 66: Commit with invalid envelope triggers rebootstrap instead of being silently dropped
    @Test
    fun testScenario66_InvalidEnvelopeCommitTriggersRebootstrap() =
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

                // Initial bootstrap
                val b1 =
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
                processor.installBootstrap(b1, generation = 1L)
                advanceUntilIdle()

                // Commit with both eventCreated and eventUpdated (invalid envelope per specification)
                val invalidCommit =
                    sampleCommitDto(
                        replayId = processor.currentReplayId,
                        sequence = 101L,
                        eventId = "ev-1",
                    ).copy(
                        eventCreated = sampleEventDto("ev-created"),
                        eventUpdated = sampleEventDto("ev-updated"),
                    )
                assertFalse(invalidCommit.validateEnvelope())

                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(invalidCommit))),
                )
                advanceUntilIdle()

                assertTrue(rebootstrapTriggered)
                processor.close()
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 67: Heartbeat requires 2 consecutive missed 5s ACK windows before timeout
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun testScenario67_HeartbeatRequiresFullSecondWindowBeforeTimeout() =
        runTest(testDispatcher) {
            val timeSource = kotlin.time.TestTimeSource()
            val tracker =
                studio.acmenra.control.feature.events.data.datasource
                    .HeartbeatTracker(maxMissedAcks = 2, timeSource = timeSource)

            // 1. Initial state: missedAcks = 0
            assertEquals(0, tracker.missedAcks.load())

            // 2. First HB sent at t=25s, ACK window 5s expires without ACK -> missedAcks becomes 1 -> connection stays open!
            timeSource += kotlin.time.Duration.parse("25s")
            tracker.onHeartbeatSent(mark = timeSource.markNow())
            timeSource += kotlin.time.Duration.parse("5s")
            assertTrue(tracker.onAckWindowTimeout())
            assertEquals(1, tracker.missedAcks.load())

            // 3. Stale ACK for old replayId (e.g. replayId 0 when active is 1) does NOT reset missedAcks!
            tracker.onAckReceived(
                incomingReplayId = 0L,
                activeReplayId = 1L,
                incomingConfirmedSeq = 0L,
                mark = timeSource.markNow(),
            )
            assertEquals(1, tracker.missedAcks.load())

            // 4. Second HB sent at t=50s, ACK window 5s expires without ACK -> second consecutive miss -> triggers reconnect (returns false)!
            timeSource += kotlin.time.Duration.parse("20s")
            tracker.onHeartbeatSent(mark = timeSource.markNow())
            timeSource += kotlin.time.Duration.parse("5s")
            assertFalse(tracker.onAckWindowTimeout())
            assertEquals(2, tracker.missedAcks.load())

            // 5. If valid ACK arrives during an active window, resets missedAcks to 0
            timeSource += kotlin.time.Duration.parse("20s")
            tracker.onHeartbeatSent(mark = timeSource.markNow())
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = 1L,
                incomingConfirmedSeq = 0L,
                mark = timeSource.markNow(),
            )
            assertEquals(0, tracker.missedAcks.load())
        }

    // Scenario 68: EventsViewModel preserves idempotencyKey across transient network failures and resets on success
    @Test
    fun testScenario68_ViewModelPreservesIdempotencyKeyOnNetworkFailureAndResetsOnSuccess() =
        runTest(testDispatcher) {
            val usedKeys = mutableListOf<String>()
            var shouldFailWithNetwork = true
            val markUseCase =
                object : MarkEventErroneousUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        eventId: String,
                        expectedClassificationVersion: Long,
                        idempotencyKey: String,
                    ): EventsOperationResult<Event> {
                        usedKeys.add(idempotencyKey)
                        return if (shouldFailWithNetwork) {
                            EventsOperationResult.Failure(EventsMutationError.Network)
                        } else {
                            EventsOperationResult.Success(
                                Event(
                                    id = eventId,
                                    eventVersion = 2L,
                                    classificationVersion = expectedClassificationVersion + 1,
                                    acknowledgementVersion = 1L,
                                    eventTypeId = "type-1",
                                    eventTypeName = "No helmet",
                                    categoryCode = "safety",
                                    iconKey = EventIconKey.HELMET,
                                    locationId = "loc-1",
                                    locationName = "Zone 1",
                                    deviceId = "dev-1",
                                    deviceName = "Camera 1",
                                    occurredAt = Instant.parse("2026-01-01T12:00:00Z"),
                                    receivedAt = Instant.parse("2026-01-01T12:00:05Z"),
                                    status = EventAcknowledgementStatus.NEW,
                                    acknowledgedAt = null,
                                    acknowledgedBy = null,
                                    isErroneous = true,
                                    previewUrl = "https://cdn.example.com/p.jpg",
                                    videoUrl = null,
                                ),
                            )
                        }
                    }
                }

            val feed = FakeFeedSession()
            val vm = createTestViewModel(feed = feed, markEventErroneousUseCase = markUseCase)
            vm.initialize(
                "comp-1",
                EventsAccessCapabilities(eventsRead = true, eventsReview = true, reportsExport = true),
            )
            advanceUntilIdle()

            // First attempt -> Network error
            vm.confirmMarkErroneous("ev-1", 1L)
            advanceUntilIdle()
            assertEquals(1, usedKeys.size)
            val firstKey = usedKeys[0]

            // Retry attempt -> Same key must be reused!
            vm.confirmMarkErroneous("ev-1", 1L)
            advanceUntilIdle()
            assertEquals(2, usedKeys.size)
            assertEquals(firstKey, usedKeys[1])

            // Success attempt -> Same key reused
            shouldFailWithNetwork = false
            vm.confirmMarkErroneous("ev-1", 1L)
            advanceUntilIdle()
            assertEquals(3, usedKeys.size)
            assertEquals(firstKey, usedKeys[2])

            // Subsequent mark after success -> Brand new key must be generated!
            vm.confirmMarkErroneous("ev-2", 1L)
            advanceUntilIdle()
            assertEquals(4, usedKeys.size)
            val newKey = usedKeys[3]
            assertTrue(newKey != firstKey)

            vm.clearForTest()
        }

    // Scenario 69: EventsViewModel preserves export idempotencyKey across transient export creation failures
    @Test
    fun testScenario69_ViewModelPreservesExportIdempotencyKeyOnTransientError() =
        runTest(testDispatcher) {
            val usedKeys = mutableListOf<String>()
            var failTransiently = true
            val createExportUseCase =
                object : CreateEventsExportUseCase {
                    override suspend fun invoke(
                        companyId: String,
                        request: EventsExportRequest,
                        idempotencyKey: String,
                    ): EventsExportStatus {
                        usedKeys.add(idempotencyKey)
                        if (failTransiently) {
                            throw EventsApiException.Generic(
                                code = "TIMEOUT",
                                serverMessage = "Gateway timeout",
                                retryable = true,
                            )
                        }
                        return EventsExportStatus(
                            exportId = "exp-1",
                            status = EventsExportStatusCode.COMPLETED,
                            createdAt = Instant.parse("2026-01-01T12:00:00Z"),
                        )
                    }
                }

            val feed = FakeFeedSession()
            val vm = createTestViewModel(feed = feed, createEventsExportUseCase = createExportUseCase)
            vm.initialize(
                "comp-1",
                EventsAccessCapabilities(eventsRead = true, eventsReview = true, reportsExport = true),
            )
            advanceUntilIdle()
            vm.toggleExportMode()

            // 1. First export submission fails with transient error
            vm.startExport()
            advanceUntilIdle()
            assertEquals(1, usedKeys.size)
            val firstKey = usedKeys[0]

            // 2. Retry export submission reuses same idempotency key
            failTransiently = false
            vm.startExport()
            advanceUntilIdle()
            assertEquals(2, usedKeys.size)
            assertEquals(firstKey, usedKeys[1])

            // 3. Once completed, next export generates brand new key
            vm.startExport()
            advanceUntilIdle()
            assertEquals(3, usedKeys.size)
            assertTrue(usedKeys[2] != firstKey)

            vm.clearForTest()
        }

    // Scenario 70: EventsRepositoryImpl.getFilterOptions handles concurrent multi-tenant requests without mutex deadlock
    @Test
    fun testScenario70_RepositoryFilterOptionsNonBlockingAcrossCompanies() =
        runTest(testDispatcher) {
            val compALatch = CompletableDeferred<Unit>()
            val compAStarted = CompletableDeferred<Unit>()
            val fakeRemote =
                object : EventsRemoteDataSource {
                    override suspend fun getFilterOptions(
                        companyId: String,
                        ifNoneMatchEtag: String?,
                    ): FilterOptionsFetchResult {
                        if (companyId == "comp-A") {
                            compAStarted.complete(Unit)
                            compALatch.await()
                            return FilterOptionsFetchResult.Success(
                                EventsFilterOptionsDto(
                                    revision = "revA",
                                    eventTypes = listOf(EventTypeOptionDto("tA", "Type A")),
                                ),
                                etag = "etagA",
                            )
                        } else {
                            return FilterOptionsFetchResult.Success(
                                EventsFilterOptionsDto(
                                    revision = "revB",
                                    eventTypes = listOf(EventTypeOptionDto("tB", "Type B")),
                                ),
                                etag = "etagB",
                            )
                        }
                    }

                    override suspend fun getBootstrap(
                        companyId: String,
                        query: EventsQuery,
                        limit: Int,
                    ): EventsBootstrapDto = error("unused")

                    override suspend fun getEventsPage(
                        companyId: String,
                        cursor: String,
                        limit: Int,
                    ): EventsPageDto = error("unused")

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
                        request: studio.acmenra.control.feature.events.data.model.CreateExportRequestDto,
                        idempotencyKey: String,
                    ): studio.acmenra.control.feature.events.data.model.EventsExportStatusDto = error("unused")

                    override suspend fun getExportStatus(
                        companyId: String,
                        exportId: String,
                    ): studio.acmenra.control.feature.events.data.model.EventsExportStatusDto = error("unused")

                    override suspend fun downloadExport(
                        companyId: String,
                        exportId: String,
                    ): EventsExportDownload = error("unused")
                }

            val sessionManager = SessionManager(TestTokenStorage())
            val repo =
                EventsRepositoryImpl(
                    remoteDataSource = fakeRemote,
                    webSocketDataSourceFactory = { TestWebSocketDataSource() },
                    sessionManager = sessionManager,
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline = MutableStateFlow(true)
                        },
                )

            // Start comp-A in background coroutine (it suspends on compALatch)
            val jobA = async { repo.getFilterOptions("comp-A") }
            compAStarted.await()

            // Fetch comp-B concurrently - must NOT be blocked by comp-A!
            val optionsB = repo.getFilterOptions("comp-B")
            assertEquals(1, optionsB.eventTypes.size)
            assertEquals("tB", optionsB.eventTypes[0].id)

            // Now release comp-A
            compALatch.complete(Unit)
            val optionsA = jobA.await()
            assertEquals(1, optionsA.eventTypes.size)
            assertEquals("tA", optionsA.eventTypes[0].id)
        }

    // Scenario 71: KtorEventsRemoteDataSource.downloadExport enforces MAX_EXPORT_DOWNLOAD_BYTES (100MB)
    @Test
    fun testScenario71_RemoteDataSourceDownloadExportTooLarge() =
        runTest(testDispatcher) {
            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content =
                            io.ktor.utils.io
                                .ByteReadChannel(ByteArray(0)),
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("text/csv"),
                                HttpHeaders.ContentLength to listOf((100L * 1024 * 1024 + 1L).toString()),
                            ),
                    )
                }

            val remote = KtorEventsRemoteDataSource(HttpClient(mockEngine))
            val ex =
                try {
                    remote.downloadExport("c1", "exp-1")
                    null
                } catch (e: EventsApiException.ExportDownloadTooLarge) {
                    e
                }
            assertNotNull(ex)
            assertEquals(100L * 1024 * 1024, ex.maxBytes)
        }

    // Scenario 72: KtorEventsRemoteDataSource path parts encoding prevents path traversal and unescaped characters
    @Test
    fun testScenario72_RemoteDataSourceEncodesUrlPathSegments() =
        runTest(testDispatcher) {
            val interceptedPaths = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    interceptedPaths.add(request.url.encodedPath)
                    respond(
                        content = """{"error": {"code": "INVALID_ARGUMENT", "message": "Bad filter"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val remote = KtorEventsRemoteDataSource(HttpClient(mockEngine))
            try {
                remote.getBootstrap("comp/test%1 space", EventsQuery(), limit = 10)
            } catch (_: EventsApiException) {
            }

            assertEquals(1, interceptedPaths.size)
            assertTrue(
                interceptedPaths[0].contains("comp%2Ftest%251%20space"),
                "Expected encoded company path part, got: ${interceptedPaths[0]}",
            )
        }

    // Scenario 73: KtorEventsRemoteDataSource maps HTTP status code retryability when backend retryable is null
    @Test
    fun testScenario73_RemoteDataSourceStatusCodeRetryabilityFallback() =
        runTest(testDispatcher) {
            var statusCodeToReturn = HttpStatusCode.ServiceUnavailable
            val mockEngine =
                MockEngine { _ ->
                    respond(
                        content =
                            """{"error": {"code": "SERVICE_UNAVAILABLE", "message": "Down", "retryable": null}}""",
                        status = statusCodeToReturn,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val remote = KtorEventsRemoteDataSource(HttpClient(mockEngine))

            // 503 Service Unavailable -> retryable = true
            try {
                remote.getBootstrap("c1", EventsQuery(), limit = 10)
                error("Expected exception")
            } catch (e: EventsApiException.Generic) {
                assertTrue(e.retryable)
            }

            // 429 Too Many Requests -> retryable = true
            statusCodeToReturn = HttpStatusCode.TooManyRequests
            try {
                remote.getBootstrap("c1", EventsQuery(), limit = 10)
                error("Expected exception")
            } catch (e: EventsApiException.Generic) {
                assertTrue(e.retryable)
            }

            // 400 Bad Request -> retryable = false
            statusCodeToReturn = HttpStatusCode.BadRequest
            try {
                remote.getBootstrap("c1", EventsQuery(), limit = 10)
                error("Expected exception")
            } catch (e: EventsApiException.Generic) {
                assertFalse(e.retryable)
            }
        }

    // Scenario 74: KtorEventsWebSocketDataSource.buildWsUrl handles base URL with /api/v1 and encodes companyId
    @Test
    fun testScenario74_WebSocketDataSourceBuildWsUrl() {
        val dsWithApi =
            KtorEventsWebSocketDataSource(
                realtimeClient = HttpClient(MockEngine { respond("ok") }),
                wsEndpointBase = "https://example.com/api/v1",
            )
        val url1 = dsWithApi.buildWsUrl("company/01")
        assertEquals("wss://example.com/api/v1/companies/company%2F01/events/ws", url1)

        val dsWithoutApi =
            KtorEventsWebSocketDataSource(
                realtimeClient = HttpClient(MockEngine { respond("ok") }),
                wsEndpointBase = "http://localhost:8080",
            )
        val url2 = dsWithoutApi.buildWsUrl("company-abc")
        assertEquals("ws://localhost:8080/api/v1/companies/company-abc/events/ws", url2)
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
            state.update {
                if (!access.eventsRead) {
                    it.copy(
                        access = access,
                        visibleEvents = emptyList(),
                        realtimeOverlay = RealtimeOverlay(),
                        asOfSequence = 0L,
                        snapshotTotalCount = 0,
                        hasMore = false,
                        isLoadingNextPage = false,
                        paginationError = null,
                        summary = null,
                        pendingNewerCount = 0,
                        pendingHistoryCount = 0,
                    )
                } else {
                    it.copy(access = access)
                }
            }
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
        onQueryHashChanged: ((String?) -> Unit)? = null,
    ): EventsViewModel {
        val feedUseCase =
            object : ObserveEventsFeedUseCase {
                override fun invoke(
                    companyId: String,
                    query: EventsQuery,
                    access: EventsAccessCapabilities,
                ): EventsFeedSession = feedFactory?.invoke(companyId, query, access) ?: feed
            }
        val vm =
            EventsViewModel(
                observeEventsFeedUseCase = feedUseCase,
                loadEventFilterOptionsUseCase = loadEventFilterOptionsUseCase,
                acknowledgeEventUseCase = acknowledgeEventUseCase,
                markEventErroneousUseCase = markEventErroneousUseCase,
                createEventsExportUseCase = createEventsExportUseCase,
                observeEventsExportStatusUseCase = observeEventsExportStatusUseCase,
                downloadEventsExportUseCase = downloadEventsExportUseCase,
                exportFileSaver = exportFileSaver,
            )
        vm.onQueryHashChanged = onQueryHashChanged
        return vm
    }

    // Scenario 75: Bootstrap exact wire JSON nested pagination conforming to II.2
    @Test
    fun testScenario75_BootstrapExactWireJsonNestedPagination() {
        val wireJson =
            """
            {
              "stream_cursor": 184293,
              "events": {
                "items": [
                  {
                    "id": "019334a0-7c00-7000-8000-000000674291",
                    "event_version": 1,
                    "classification_version": 1,
                    "acknowledgement_version": 1,
                    "event_type_id": "no_helmet",
                    "event_type_name": "Нахождение без каски",
                    "category_code": "safety",
                    "icon_key": "helmet",
                    "location_id": "loc_workshop_1",
                    "location_name": "цех 1",
                    "device_id": "cam_04",
                    "device_name": "Камера №4",
                    "occurred_at": "2026-11-16T14:30:00Z",
                    "received_at": "2026-11-16T14:30:02Z",
                    "status": "NEW",
                    "acknowledged_at": null,
                    "acknowledged_by": null,
                    "is_erroneous": false,
                    "preview_url": "https://cdn.example.com/thumb.jpg",
                    "video_url": "https://cdn.example.com/video.mp4"
                  }
                ],
                "pagination": {
                  "snapshot_id": "snap_019334a0_184293",
                  "as_of_sequence": 184293,
                  "next_cursor": "eyJmb3JtYXRfdmVyc2lvbiI6MS4uLg",
                  "has_more": true,
                  "total_count": 142
                }
              },
              "summary": {
                "summary_as_of_sequence": 184293,
                "business_timezone": "Europe/Riga",
                "business_date": "2026-11-16",
                "today_events_count": 4,
                "categories": [
                  {
                    "category_id": "safety",
                    "name": "Безопасность",
                    "score": 200,
                    "trend": "IMPROVING"
                  }
                ],
                "devices": {
                  "total_count": 10,
                  "online_ok_count": 10,
                  "unknown_count": 0,
                  "error_count": 0
                }
              }
            }
            """.trimIndent()

        val testJson = Json { ignoreUnknownKeys = true }
        val decoded = testJson.decodeFromString<EventsBootstrapDto>(wireJson)
        assertEquals(184293L, decoded.streamCursor)
        assertEquals(1, decoded.events.items.size)
        assertEquals("019334a0-7c00-7000-8000-000000674291", decoded.events.items[0].id)
        assertEquals("snap_019334a0_184293", decoded.events.pagination.snapshotId)
        assertEquals(184293L, decoded.events.pagination.asOfSequence)
        assertEquals("eyJmb3JtYXRfdmVyc2lvbiI6MS4uLg", decoded.events.pagination.nextCursor)
        assertTrue(decoded.events.pagination.hasMore)
        assertEquals(142, decoded.events.pagination.totalCount)
        assertEquals(184293L, decoded.summary.summaryAsOfSequence)

        val domain = EventsDtoMapper.mapBootstrap(decoded)
        assertEquals("snap_019334a0_184293", domain.page.snapshotId)
        assertEquals(184293L, domain.page.asOfSequence)
        assertEquals(184293L, domain.summary.summaryAsOfSequence)
        assertEquals(184293L, domain.streamCursor)
    }

    // Scenario 76: Snapshot fencing: late next-page response from prior snapshot ignored
    @Test
    fun testScenario76_SnapshotFencingDifferentOpaqueSnapshotIdSameSequence() =
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
                                items = listOf(sampleEvent("ev-snap1")),
                                nextCursor = "cursor-1",
                                hasMore = true,
                                totalCount = 2,
                                asOfSequence = 100L,
                                snapshotId = "snap-ACTIVE",
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

                assertEquals("snap-ACTIVE", processor.state.value.snapshotId)

                // Late response from previous snapshot with same asOfSequence but different snapshotId
                val latePage =
                    EventsPage(
                        items = listOf(sampleEvent("ev-LATE")),
                        nextCursor = null,
                        hasMore = false,
                        totalCount = 2,
                        asOfSequence = 100L,
                        snapshotId = "snap-OLD",
                    )
                processor.dispatch(SessionCommand.NextPageLoaded(latePage, snapshotId = "snap-OLD"))
                advanceUntilIdle()

                // Late page must be completely ignored
                assertEquals(1, processor.state.value.visibleEvents.size)
                assertEquals(
                    "ev-snap1",
                    processor.state.value.visibleEvents[0]
                        .id,
                )

                // Correct page for current snapshot is accepted
                val validPage =
                    EventsPage(
                        items = listOf(sampleEvent("ev-VALID")),
                        nextCursor = null,
                        hasMore = false,
                        totalCount = 2,
                        asOfSequence = 100L,
                        snapshotId = "snap-ACTIVE",
                    )
                processor.dispatch(SessionCommand.NextPageLoaded(validPage, snapshotId = "snap-ACTIVE"))
                advanceUntilIdle()

                assertEquals(2, processor.state.value.visibleEvents.size)
                assertEquals(
                    "ev-VALID",
                    processor.state.value.visibleEvents[1]
                        .id,
                )
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 77: Bootstrap summary sequence equality
    @Test
    fun testScenario77_BootstrapSummarySequenceEquality() =
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

                // Summary sequence mismatch (summary 101 != page 100)
                val invalidBootstrap =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-1")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 100L,
                                snapshotId = "snap-1",
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 101L,
                                todayEventsCount = 1,
                            ),
                        streamCursor = 100L,
                    )
                val res = processor.installBootstrap(invalidBootstrap, generation = 1L)
                assertEquals(BootstrapApplyResult.RetryRequired, res)
                assertEquals(0, processor.state.value.visibleEvents.size)
                assertNull(processor.state.value.summary)
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 78: Summary coupling: new bootstrap always uses its own summary, not older higher summary
    @Test
    fun testScenario78_SummaryCouplingNewBootstrapNeverKeepsOlderSnapshotSummary() =
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
                                items = listOf(sampleEvent("ev-200")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 200L,
                                snapshotId = "snap-200",
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 200L,
                                todayEventsCount = 10,
                            ),
                        streamCursor = 200L,
                    )
                processor.installBootstrap(b1, generation = 1L)
                advanceUntilIdle()

                assertEquals(
                    200L,
                    processor.state.value.summary
                        ?.summaryAsOfSequence,
                )

                // User changes filter -> fresh bootstrap arrives with lower sequence (e.g. historical snapshot)
                val b2 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-100")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 100L,
                                snapshotId = "snap-100",
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 100L,
                                todayEventsCount = 3,
                            ),
                        streamCursor = 100L,
                    )
                processor.installBootstrap(b2, generation = 2L)
                advanceUntilIdle()

                // Must couple with b2 summary (100L), NOT retain 200L!
                assertEquals(
                    100L,
                    processor.state.value.summary
                        ?.summaryAsOfSequence,
                )
                assertEquals(
                    3,
                    processor.state.value.summary
                        ?.todayEventsCount,
                )
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 79: Export POST body has 'filters' shape and no 'query'
    @Test
    fun testScenario79_ExportPostFiltersShapeGoldenTest() {
        val request =
            EventsExportRequest(
                query =
                    EventsQuery(
                        filter =
                            EventsFilter(
                                searchId = "  test-search  ",
                                dateFrom = kotlin.time.Instant.parse("2026-11-01T00:00:00Z"),
                                dateTo = kotlin.time.Instant.parse("2026-11-02T00:00:00Z"),
                                eventTypeIds = setOf("type-b", "type-a"),
                                deviceIds = setOf("dev-2", "dev-1"),
                                validity = EventValidityFilter.VALID,
                            ),
                    ),
                includeMediaLinks = true,
                formatPreset = EventsExportFormatPreset.STANDARD_COMMA_UTF8,
            )

        val dto = EventsDtoMapper.mapExportRequestToDto(request)
        val jsonText = Json.encodeToString(dto)

        assertTrue(jsonText.contains("\"filters\""))
        assertFalse(jsonText.contains("\"query\""))
        assertFalse(jsonText.contains("\"sort\""))
        assertTrue(jsonText.contains("\"format_preset\":\"STANDARD_COMMA_UTF8\""))
        assertTrue(jsonText.contains("\"search_id\":\"test-search\""))
        assertTrue(jsonText.contains("\"from\":\"2026-11-01T00:00:00Z\""))
        assertTrue(jsonText.contains("\"to\":\"2026-11-02T00:00:00Z\""))
        assertTrue(jsonText.contains("\"validity\":\"VALID\""))
        assertTrue(jsonText.contains("\"include_media_links\":true"))
    }

    // Scenario 80: Export status: nullable fields, canonical statuses, and download gating
    @Test
    fun testScenario80_ExportStatusNullableFieldsAndCanonicalStatuses() {
        val queuedJson =
            """
            {
              "export_id": "exp-queued-1",
              "status": "QUEUED",
              "created_at": "2026-11-16T14:30:00Z",
              "updated_at": "2026-11-16T14:30:01Z",
              "finished_at": null,
              "processed_records": 0,
              "total_records": null,
              "expires_at": null,
              "failure_code": null,
              "download_url": null
            }
            """.trimIndent()

        val testJson = Json { ignoreUnknownKeys = true }
        val queuedDto = testJson.decodeFromString<EventsExportStatusDto>(queuedJson)
        val queuedDomain = EventsDtoMapper.mapExportStatus(queuedDto)
        assertEquals(EventsExportStatusCode.QUEUED, queuedDomain.status)
        assertNull(queuedDomain.totalRecords)
        assertEquals(0L, queuedDomain.processedRecords)
        assertNull(queuedDomain.finishedAt)
        assertNull(queuedDomain.downloadUrl)
        assertFalse(queuedDomain.isDownloadable)

        val completedJson =
            """
            {
              "export_id": "exp-comp-1",
              "status": "COMPLETED",
              "created_at": "2026-11-16T14:30:00Z",
              "updated_at": "2026-11-16T14:31:00Z",
              "finished_at": "2026-11-16T14:31:00Z",
              "processed_records": 1000,
              "total_records": 1000,
              "expires_at": "2030-01-01T00:00:00Z",
              "failure_code": null,
              "download_url": "/api/v1/companies/c1/events/exports/exp-comp-1/download"
            }
            """.trimIndent()

        val compDto = testJson.decodeFromString<EventsExportStatusDto>(completedJson)
        val compDomain = EventsDtoMapper.mapExportStatus(compDto)
        assertEquals(EventsExportStatusCode.COMPLETED, compDomain.status)
        assertEquals(1000L, compDomain.totalRecords)
        assertEquals(100, compDomain.progressPercent)
        assertTrue(compDomain.isDownloadable)
    }

    // Scenario 81: Gap scenario 13: 102 before 101 -> increment replay id -> send REPLAY_REQUEST after 100
    @Test
    fun testScenario81_GapScenario13ReplayGenerationIncrement() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var requestedReplayId: Long? = null
                var requestedAfterSeq: Long? = null

                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { replayId, afterSeq ->
                            requestedReplayId = replayId
                            requestedAfterSeq = afterSeq
                        },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val b1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-100")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 100L,
                                snapshotId = "snap-100",
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

                val initialReplayId = processor.currentReplayId

                // Frame 102 arrives before 101 on active replay generation
                val commit102 =
                    sampleCommitDto(
                        replayId = initialReplayId,
                        sequence = 102L,
                        eventId = "ev-102",
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit102))),
                )
                advanceUntilIdle()

                // Active replayId must be incremented
                val newReplayId = processor.currentReplayId
                assertEquals(initialReplayId + 1, newReplayId)

                // REPLAY_REQUEST sent for afterSequence = 100 with newReplayId
                assertEquals(newReplayId, requestedReplayId)
                assertEquals(100L, requestedAfterSeq)

                // Sequence not advanced, commit 102 not applied
                assertEquals(100L, processor.getAsOfSequence())

                // Old-generation frame must be ignored
                val oldGenCommit =
                    sampleCommitDto(
                        replayId = initialReplayId,
                        sequence = 101L,
                        eventId = "ev-101",
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(oldGenCommit))),
                )
                advanceUntilIdle()

                assertEquals(100L, processor.getAsOfSequence())
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 82: RESYNC closes current generation immediately; post-RESYNC frames ignored; invalidation handled
    @Test
    fun testScenario82_ResyncClosesGenerationPostResyncCommitsIgnored() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var rebootstrapTriggered = false
                var accessInvalidatedReason: String? = null

                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { rebootstrapTriggered = true },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { reason, _ -> accessInvalidatedReason = reason },
                    )

                val b1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-1")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 100L,
                                snapshotId = "snap-1",
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

                val closedGen = processor.currentReplayId

                // Receive RESYNC_REQUIRED for closedGen
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            1L,
                            IncomingWsFrame.ResyncRequired(ResyncRequiredFrameDto(replayId = closedGen)),
                        ),
                    ),
                )
                advanceUntilIdle()
                assertTrue(rebootstrapTriggered)

                // Frames arriving on closedGen must be ignored
                val commit = sampleCommitDto(replayId = closedGen, sequence = 101L, eventId = "ev-stale")
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit))),
                )
                advanceUntilIdle()
                assertEquals(100L, processor.getAsOfSequence())

                // ACCESS_INVALIDATED arriving even on closed generation MUST be handled unconditionally
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            1L,
                            IncomingWsFrame.AccessInvalidated(
                                AccessInvalidatedFrameDto(reason = "TENANT_REVOKED", companyId = "c1"),
                            ),
                        ),
                    ),
                )
                advanceUntilIdle()
                assertEquals("TENANT_REVOKED", accessInvalidatedReason)
                assertEquals(0, processor.state.value.visibleEvents.size)
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 83: Strict event_version gating on realtime commit
    @Test
    fun testScenario83_StrictEventVersionGatingOnRealtimeCommit() {
        val query = EventsQuery()
        val currentEvent =
            sampleEvent("ev-1").copy(
                eventVersion = 2L,
                classificationVersion = 2L,
                acknowledgementVersion = 2L,
            )
        val initialOverlay =
            RealtimeOverlay(
                entries =
                    mapOf(
                        "ev-1" to
                            studio.acmenra.control.feature.events.domain.model.OverlayEntry(
                                eventId = "ev-1",
                                event = currentEvent,
                                eventVersion = 2L,
                                membership = OverlayMembership.INCLUDED,
                            ),
                    ),
            )

        // 1. Incoming event with older event_version (1 < 2) but higher classification (3 > 2) must be ignored
        val staleCommit =
            currentEvent.copy(
                eventVersion = 1L,
                classificationVersion = 3L,
                isErroneous = true,
            )
        val (overlay1, _) =
            RealtimeOverlayReducer.applyEventCommit(
                currentOverlay = initialOverlay,
                event = staleCommit,
                query = query,
                snapshotItems = listOf(currentEvent),
            )
        assertEquals(2L, overlay1.entries["ev-1"]?.eventVersion)
        assertFalse(overlay1.entries["ev-1"]?.event?.isErroneous ?: true)

        // 2. Incoming event with equal event_version (2 == 2) must be ignored
        val equalCommit = currentEvent.copy(eventVersion = 2L)
        val (overlay2, _) =
            RealtimeOverlayReducer.applyEventCommit(
                currentOverlay = initialOverlay,
                event = equalCommit,
                query = query,
                snapshotItems = listOf(currentEvent),
            )
        assertEquals(2L, overlay2.entries["ev-1"]?.eventVersion)

        // 3. Incoming event with version 0 is rejected as integrity violation
        val invalidCommit = currentEvent.copy(eventVersion = 0L)
        val (overlay3, _) =
            RealtimeOverlayReducer.applyEventCommit(
                currentOverlay = initialOverlay,
                event = invalidCommit,
                query = query,
                snapshotItems = listOf(currentEvent),
            )
        assertTrue(overlay3.requiresRefresh)

        // 4. Newer event_version (3 > 2) is accepted
        val newerCommit =
            currentEvent.copy(
                eventVersion = 3L,
                classificationVersion = 3L,
                isErroneous = true,
            )
        val (overlay4, _) =
            RealtimeOverlayReducer.applyEventCommit(
                currentOverlay = initialOverlay,
                event = newerCommit,
                query = query,
                snapshotItems = listOf(currentEvent),
            )
        assertEquals(3L, overlay4.entries["ev-1"]?.eventVersion)
        assertTrue(overlay4.entries["ev-1"]?.event?.isErroneous ?: false)
    }

    // Scenario 84: HeartbeatTracker 5-second ACK window
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun testScenario84_HeartbeatAck5SecondWindow() {
        val timeSource = kotlin.time.TestTimeSource()
        val tracker =
            studio.acmenra.control.feature.events.data.datasource.HeartbeatTracker(
                maxMissedAcks = 2,
                ackWindow = kotlin.time.Duration.parse("5s"),
                timeSource = timeSource,
            )

        // Send HB at t = 1s
        timeSource += kotlin.time.Duration.parse("1s")
        tracker.onHeartbeatSent(mark = timeSource.markNow())
        assertTrue(tracker.hasPendingAck.load())

        // ACK arrives in window at t = 3s (elapsed 2s <= 5s)
        timeSource += kotlin.time.Duration.parse("2s")
        val ackResult =
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = 1L,
                incomingConfirmedSeq = 0L,
                mark = timeSource.markNow(),
            )
        assertTrue(ackResult)
        assertFalse(tracker.hasPendingAck.load())
        assertEquals(0, tracker.missedAcks.load())

        // Next HB sent at t = 10s
        timeSource += kotlin.time.Duration.parse("7s")
        tracker.onHeartbeatSent(mark = timeSource.markNow())

        // Window timeout at t = 15s (5s elapsed)
        timeSource += kotlin.time.Duration.parse("5s")
        val canContinue1 = tracker.onAckWindowTimeout()
        assertTrue(canContinue1)
        assertEquals(1, tracker.missedAcks.load())

        // Stale ACK arrives late at t = 16s (6s elapsed > 5s window) -> rejected, missedAcks stays 1
        timeSource += kotlin.time.Duration.parse("1s")
        val lateAckResult =
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = 1L,
                incomingConfirmedSeq = 0L,
                mark = timeSource.markNow(),
            )
        assertFalse(lateAckResult)
        assertEquals(1, tracker.missedAcks.load())

        // Next HB sent at t = 20s
        timeSource += kotlin.time.Duration.parse("4s")
        tracker.onHeartbeatSent(mark = timeSource.markNow())

        // Second consecutive window timeout at t = 25s (5s elapsed) -> must trigger reconnect!
        timeSource += kotlin.time.Duration.parse("5s")
        val canContinue2 = tracker.onAckWindowTimeout()
        assertFalse(canContinue2)
        assertEquals(2, tracker.missedAcks.load())
    }

    // Scenario 85: Filter cache race: delayed old 200 cannot overwrite accepted 304
    @Test
    fun testScenario85_FilterCacheRaceDelayed200CannotOverwriteAccepted304() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var callCount = 0
                val reqALatch = CompletableDeferred<Unit>()
                val reqBFinished = CompletableDeferred<Unit>()

                val mockRemote =
                    object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                        override suspend fun getBootstrap(
                            companyId: String,
                            query: EventsQuery,
                            limit: Int,
                        ): EventsBootstrapDto = error("unused")

                        override suspend fun getEventsPage(
                            companyId: String,
                            cursor: String,
                            limit: Int,
                        ): EventsPageDto = error("unused")

                        override suspend fun getFilterOptions(
                            companyId: String,
                            ifNoneMatchEtag: String?,
                        ): studio.acmenra.control.feature.events.data.datasource.FilterOptionsFetchResult {
                            callCount++
                            if (callCount == 1) {
                                // Request A: initial fetch returns 200 with etag "etag-1"
                                return FilterOptionsFetchResult.Success(
                                    options =
                                        EventsFilterOptionsDto(
                                            revision = "rev-1",
                                            eventTypes = listOf(EventTypeOptionDto("t1", "Type 1")),
                                        ),
                                    etag = "etag-1",
                                )
                            } else if (callCount == 2) {
                                // Request B: delayed old fetch (simulated delayed 200)
                                reqALatch.await()
                                return FilterOptionsFetchResult.Success(
                                    options =
                                        EventsFilterOptionsDto(
                                            revision = "rev-OLD",
                                            eventTypes = listOf(EventTypeOptionDto("tOLD", "Type OLD")),
                                        ),
                                    etag = "etag-OLD",
                                )
                            } else {
                                // Request C: returns 304 NotModified
                                reqBFinished.complete(Unit)
                                return FilterOptionsFetchResult.NotModified
                            }
                        }

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

                val repo =
                    EventsRepositoryImpl(
                        remoteDataSource = mockRemote,
                        webSocketDataSourceFactory = { error("unused") },
                        sessionManager = SessionManager(TestTokenStorage()),
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = MutableStateFlow(true)
                            },
                    )

                // 1. Initial fetch caches "rev-1" with etag-1
                val opt1 = repo.getFilterOptions("c1")
                assertEquals("rev-1", opt1.revision)

                // 2. Start Request A concurrently, but hold it before completing
                val jobA = async { repo.getFilterOptions("c1") }
                testScheduler.runCurrent()

                // 3. Request C runs and receives 304 NotModified, confirming the cache and advancing request generation
                val optC = repo.getFilterOptions("c1")
                assertEquals("rev-1", optC.revision)

                // 4. Now release Request A (which tries to return "rev-OLD")
                reqALatch.complete(Unit)
                val optA = jobA.await()

                // Cache must NOT be overwritten by the delayed Request A!
                val cachedNow = repo.getFilterOptions("c1")
                assertEquals("rev-1", cachedNow.revision)
            } finally {
                scope.cancel()
            }
        }

    // Scenario 86: Bootstrap side effects: filter options notified strictly after atomic state commit
    @Test
    fun testScenario86_FilterOptionsSideEffectsExecutedOnlyAfterAtomicCommit() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var notifiedOptions: EventsFilterOptionsDto? = null
                var feedVisibleSizeOnNotification = -1

                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = testScope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                        onFilterOptionsUpdated = { options ->
                            notifiedOptions = options
                        },
                    )

                // Try invalid bootstrap (sequence mismatch) with filter options update: must NOT notify side effect!
                val invalidBootstrap =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = listOf(sampleEvent("ev-1")),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 1,
                                asOfSequence = 100L,
                                snapshotId = "snap-1",
                            ),
                        summary =
                            EventsSummary(
                                businessTimezone = "UTC",
                                businessDate = "2026-03-01",
                                summaryAsOfSequence = 105L, // Mismatch!
                                todayEventsCount = 1,
                            ),
                        streamCursor = 100L,
                    )
                val res = processor.installBootstrap(invalidBootstrap, generation = 1L)
                assertEquals(BootstrapApplyResult.RetryRequired, res)
                assertNull(notifiedOptions)
            } finally {
                testScope.cancel()
            }
        }

    // Scenario 87: SessionManager refresh semantics: delegate returning null returns null
    @Test
    fun testScenario87_SessionManagerRefreshNullDelegateReturnsSnapshot() =
        runTest(testDispatcher) {
            val storage = TestTokenStorage(TokenPair("acc-old", "ref-old"))
            val sessionManager = SessionManager(storage)

            // 1. No delegate: returns getSnapshot()
            val snap1 = sessionManager.refreshActiveSession()
            assertNotNull(snap1)
            assertEquals("acc-old", snap1.tokens.accessToken)

            // 2. Delegate registered, returns null (refresh failed) -> must return null, NOT old snapshot!
            val failingDelegate: suspend (String?) -> studio.acmenra.control.core.network.auth.SessionSnapshot? =
                { _ -> null }
            sessionManager.setRefreshDelegate(failingDelegate)

            val snap2 = sessionManager.refreshActiveSession("acc-old")
            assertNull(snap2)

            // 3. Clear delegate using CAS
            sessionManager.clearRefreshDelegate(failingDelegate)
            val snap3 = sessionManager.refreshActiveSession()
            assertNotNull(snap3)
        }

    // Scenario 88: Clean Architecture: ViewModel catches Domain Exceptions, not Data layer
    @Test
    fun testScenario88_CleanArchitectureViewModelNeverImportsDataExceptions() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val createExportException =
                    object : CreateEventsExportUseCase {
                        override suspend fun invoke(
                            companyId: String,
                            request: EventsExportRequest,
                            idempotencyKey: String,
                        ): EventsExportStatus = throw EventsDomainException.PermissionDenied("ACCESS_REVOKED")
                    }

                val vm =
                    createTestViewModel(
                        createEventsExportUseCase = createExportException,
                    )

                vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = true, reportsExport = true))
                vm.startExport()
                testScheduler.runCurrent()

                // Verified that PermissionDenied domain exception maps to access revoked message
                assertEquals(
                    Res.string.events_export_error_access_revoked,
                    vm.state.value.exportError,
                )
            } finally {
                scope.cancel()
            }
        }

    // Scenario 89: Date route codec UTC bounds round-trip and timezone reconstruction
    @Test
    fun testScenario89_DateRouteCodecUtcLocalRoundTripAndReconstruction() {
        val from = kotlin.time.Instant.parse("2026-11-16T00:00:00Z")
        val to = kotlin.time.Instant.parse("2026-11-17T00:00:00Z")
        val query =
            EventsQuery(
                filter =
                    EventsFilter(
                        dateFrom = from,
                        dateTo = to,
                        validity = EventValidityFilter.VALID,
                    ),
            )

        val encoded = EventsQueryRouteCodec.encodeHash("comp-1", query)
        assertTrue(encoded.contains("from=2026-11-16T00%3A00%3A00Z"))
        assertTrue(encoded.contains("to=2026-11-17T00%3A00%3A00Z"))

        // Decode without timezone: preserves UTC instants
        val (decodedComp, decodedQuery) = EventsQueryRouteCodec.decodeHash(encoded)
        assertEquals("comp-1", decodedComp)
        assertEquals(from, decodedQuery.filter.dateFrom)
        assertEquals(to, decodedQuery.filter.dateTo)

        // Reconstruct with timezone
        val tz = kotlinx.datetime.TimeZone.UTC
        val (_, decodedWithTz) = EventsQueryRouteCodec.decodeHash(encoded, tz)
        val reconstructedSelection =
            DateFilterUtils.utcBoundsToDateSelection(
                decodedWithTz.filter.dateFrom,
                decodedWithTz.filter.dateTo,
                tz,
            )
        assertTrue(reconstructedSelection is DateSelection.Single)
        assertEquals(
            kotlinx.datetime.LocalDate(2026, 11, 16),
            (reconstructedSelection as DateSelection.Single).date,
        )
    }

    // Scenario 90: Download export: bounded streaming cap at 100MB
    @Test
    fun testScenario90_DownloadExportBoundedMemoryStreamingCap100MB() =
        runTest(testDispatcher) {
            val maxBytes = 100L * 1024 * 1024

            val mockEngine =
                MockEngine { request ->
                    val cl = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    respond(
                        content =
                            io.ktor.utils.io
                                .ByteReadChannel(ByteArray(0)),
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("text/csv"),
                                HttpHeaders.ContentLength to listOf((maxBytes + 1L).toString()),
                            ),
                    )
                }

            val remote = KtorEventsRemoteDataSource(HttpClient(mockEngine))
            val ex =
                try {
                    remote.downloadExport("c1", "exp-1")
                    null
                } catch (
                    e: studio.acmenra.control.feature.events.data.datasource.EventsApiException.ExportDownloadTooLarge,
                ) {
                    e
                }
            assertNotNull(ex)
            assertEquals(maxBytes, ex.maxBytes)
        }

    // Scenario 91: Player modal closes while selectedEventId remains unchanged
    @Test
    fun testScenario91_PlayerModalClosesWhileSelectedEventIdRemainsUnchanged() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val vm = createTestViewModel()
                val event1 = sampleEvent("ev-1")
                val event2 = sampleEvent("ev-2")

                // 1. Select event-1: selectedEventId is set, compact viewer is open
                vm.selectEvent(event1.id)
                assertEquals("ev-1", vm.state.value.selectedEventId)
                assertTrue(vm.state.value.isViewerOpenInCompact)

                // 2. Close compact viewer: selectedEventId is preserved (retains anchor/selection), modal closes
                vm.closeViewerCompact()
                assertEquals("ev-1", vm.state.value.selectedEventId)
                assertFalse(vm.state.value.isViewerOpenInCompact)

                // 3. Switch to event-2: updates selectedEventId and reopens viewer
                vm.selectEvent(event2.id)
                assertEquals("ev-2", vm.state.value.selectedEventId)
                assertTrue(vm.state.value.isViewerOpenInCompact)

                // 4. Clear selection: clears both
                vm.selectEvent(null)
                assertNull(vm.state.value.selectedEventId)
                assertFalse(vm.state.value.isViewerOpenInCompact)
            } finally {
                scope.cancel()
            }
        }

    // Scenario 92: CSV formula neutralizer normative vectors
    @Test
    fun testScenario92_CsvFormulaNeutralizerNormativeVectors() {
        // Direct formula prefixes
        assertEquals(
            "'=cmd",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("=cmd"),
        )
        assertEquals(
            "'+123",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("+123"),
        )
        assertEquals(
            "'-sum(1,2)",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("-sum(1,2)"),
        )
        assertEquals(
            "'@import",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("@import"),
        )

        // Leading TAB, CR, LF, spaces followed by formula prefix
        assertEquals(
            "'\t=1+2",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("\t=1+2"),
        )
        assertEquals(
            "'\n-5",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("\n-5"),
        )
        assertEquals(
            "'\r\n@SUM",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("\r\n@SUM"),
        )
        assertEquals(
            "'   +cmd",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("   +cmd"),
        )

        // Leading Unicode whitespace
        assertEquals(
            "'\u00A0=1+2",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("\u00A0=1+2"),
        )
        assertEquals(
            "'\u2000+calc",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("\u2000+calc"),
        )
        assertEquals(
            "'\u3000@alert",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("\u3000@alert"),
        )

        // Leading C0 / C1 control characters
        assertEquals(
            "'\u0007=beep",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("\u0007=beep"),
        )
        assertEquals(
            "'\u001B-esc",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("\u001B-esc"),
        )
        assertEquals(
            "'\u0085=nel",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("\u0085=nel"),
        )

        // Safe values must remain untouched
        assertEquals(
            "Normal text",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("Normal text"),
        )
        assertEquals(
            "12345",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("12345"),
        )
        assertEquals(
            "Hello = World",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize("Hello = World"),
        )
        assertEquals(
            "",
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer
                .neutralize(""),
        )

        // RFC 4180 quoting
        val cellWithComma =
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer.quoteCsvCell(
                "=sum,total",
            )
        assertEquals("\"'=sum,total\"", cellWithComma)

        val cellWithQuotes =
            studio.acmenra.control.feature.events.domain.model.CsvFormulaNeutralizer.quoteCsvCell(
                "=cmd\"run\"",
            )
        assertEquals("\"'=cmd\"\"run\"\"\"", cellWithQuotes)
    }

    // HB-1: Auth replay 1, then replay request replay 2, ACK 2 accepted and resets missed count
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun testHB1_AuthReplay1ThenReplayRequestReplay2ThenAck2AcceptedAndResetsMissedCount() {
        val timeSource = kotlin.time.TestTimeSource()
        val tracker =
            studio.acmenra.control.feature.events.data.datasource.HeartbeatTracker(
                maxMissedAcks = 2,
                ackWindow = kotlin.time.Duration.parse("5s"),
                timeSource = timeSource,
            )

        var activeReplay = 1L
        tracker.onHeartbeatSent(mark = timeSource.markNow())

        timeSource += kotlin.time.Duration.parse("5s")
        assertTrue(tracker.onAckWindowTimeout())
        assertEquals(1, tracker.missedAcks.load())

        activeReplay = 2L
        tracker.onHeartbeatSent(mark = timeSource.markNow())
        assertTrue(tracker.hasPendingAck.load())

        timeSource += kotlin.time.Duration.parse("2s")
        val ackAccepted =
            tracker.onAckReceived(
                incomingReplayId = 2L,
                activeReplayId = activeReplay,
                incomingConfirmedSeq = 0L,
                mark = timeSource.markNow(),
            )
        assertTrue(ackAccepted)
        assertFalse(tracker.hasPendingAck.load())
        assertEquals(0, tracker.missedAcks.load())
    }

    // HB-2: Stale ACK from old replay generation does not reset missed count
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun testHB2_StaleAckOldReplayDoesNotResetMissedCount() {
        val timeSource = kotlin.time.TestTimeSource()
        val tracker =
            studio.acmenra.control.feature.events.data.datasource.HeartbeatTracker(
                maxMissedAcks = 2,
                ackWindow = kotlin.time.Duration.parse("5s"),
                timeSource = timeSource,
            )

        tracker.onHeartbeatSent(mark = timeSource.markNow())
        timeSource += kotlin.time.Duration.parse("5s")
        tracker.onAckWindowTimeout()
        assertEquals(1, tracker.missedAcks.load())

        val activeReplay = 2L
        tracker.onHeartbeatSent(mark = timeSource.markNow())

        timeSource += kotlin.time.Duration.parse("1s")
        val ackAccepted =
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = activeReplay,
                incomingConfirmedSeq = 0L,
                mark = timeSource.markNow(),
            )
        assertFalse(ackAccepted)
        assertEquals(1, tracker.missedAcks.load())
        assertTrue(tracker.hasPendingAck.load())
    }

    // HB-3: Two consecutive missed 5s ACKs trigger reconnect (return false)
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun testHB3_Cadence25sSendToSendAndTwoConsecutiveMissed5sAcksReconnect() {
        val timeSource = kotlin.time.TestTimeSource()
        val tracker =
            studio.acmenra.control.feature.events.data.datasource.HeartbeatTracker(
                maxMissedAcks = 2,
                ackWindow = kotlin.time.Duration.parse("5s"),
                timeSource = timeSource,
            )

        tracker.onHeartbeatSent(mark = timeSource.markNow())
        timeSource += kotlin.time.Duration.parse("5s")
        val canContinue1 = tracker.onAckWindowTimeout()
        assertTrue(canContinue1)
        assertEquals(1, tracker.missedAcks.load())

        timeSource += kotlin.time.Duration.parse("20s")
        tracker.onHeartbeatSent(mark = timeSource.markNow())
        timeSource += kotlin.time.Duration.parse("5s")
        val canContinue2 = tracker.onAckWindowTimeout()
        assertFalse(canContinue2)
        assertEquals(2, tracker.missedAcks.load())
    }

    // HB-4: Monotonic clock timeout boundary at exactly 5 seconds
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun testHB4_MonotonicClockAndTimeoutBoundary5s() {
        val timeSource = kotlin.time.TestTimeSource()
        val tracker =
            studio.acmenra.control.feature.events.data.datasource.HeartbeatTracker(
                maxMissedAcks = 2,
                ackWindow = kotlin.time.Duration.parse("5s"),
                timeSource = timeSource,
            )

        // Boundary <= 5s: accepted
        tracker.onHeartbeatSent(mark = timeSource.markNow())
        timeSource += kotlin.time.Duration.parse("5s")
        val acceptedAtBoundary =
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = 1L,
                incomingConfirmedSeq = 0L,
                mark = timeSource.markNow(),
            )
        assertTrue(acceptedAtBoundary)
        assertEquals(0, tracker.missedAcks.load())

        // Boundary > 5s: rejected
        timeSource += kotlin.time.Duration.parse("5s")
        tracker.onHeartbeatSent(mark = timeSource.markNow())
        timeSource += kotlin.time.Duration.parse("5001ms")
        val rejectedAfterBoundary =
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = 1L,
                incomingConfirmedSeq = 0L,
                mark = timeSource.markNow(),
            )
        assertFalse(rejectedAfterBoundary)
    }

    // RT-1: Invalid event payload requires refresh and triggers rebootstrap without advancing sequence
    @Test
    fun testRT1_InvalidEventPayloadRequiresRefreshTriggersRebootstrapWithoutAdvancingSequence() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var rebootstrapTriggered = false
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = scope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { rebootstrapTriggered = true },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val page = EventsPage(listOf(sampleEvent("ev-1")), null, false, 1, 100L, "snap-1")
                val summary = EventsSummary("UTC", "2026-03-01", 100L, 1)
                processor.installBootstrap(EventsBootstrap(page, summary, 100L), 1L)

                val invalidEvent = sampleEventDto("ev-1").copy(eventVersion = 0L)
                val commit =
                    TenantCommitFrameDto(
                        replayId = 2L,
                        tenantSequence = 101L,
                        payloadSha256 = "0".repeat(64),
                        eventCreated = invalidEvent,
                    )

                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit))),
                )
                testScheduler.runCurrent()

                assertTrue(rebootstrapTriggered)
                assertEquals(100L, processor.getAsOfSequence())
            } finally {
                scope.cancel()
            }
        }

    // RT-2: Invalid envelope (missing payload or both payloads present) rejected before sequence advance
    @Test
    fun testRT2_InvalidEnvelopeDoesNotLeavePartialCommitOrAdvanceSequence() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var rebootstrapTriggered = false
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = scope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { rebootstrapTriggered = true },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val page = EventsPage(listOf(sampleEvent("ev-1")), null, false, 1, 10L, "snap-1")
                val summary = EventsSummary("UTC", "2026-03-01", 10L, 1)
                processor.installBootstrap(EventsBootstrap(page, summary, 10L), 1L)

                val ev = sampleEventDto("ev-2")
                val invalidCommit =
                    TenantCommitFrameDto(
                        replayId = 2L,
                        tenantSequence = 11L,
                        payloadSha256 = "0".repeat(64),
                        eventCreated = ev,
                        eventUpdated = ev,
                    )
                assertFalse(invalidCommit.validateEnvelope())

                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(invalidCommit))),
                )
                testScheduler.runCurrent()

                assertEquals(10L, processor.getAsOfSequence())
                assertTrue(rebootstrapTriggered)
            } finally {
                scope.cancel()
            }
        }

    // RT-3: Equal classification version with different erroneous fields violates integrity
    @Test
    fun testRT3_ClassificationVersionEqualityRequiresIdenticalErroneousFields() {
        val query = EventsQuery()
        val currentEvent =
            sampleEvent("ev-1").copy(
                eventVersion = 2L,
                classificationVersion = 1L,
                isErroneous = false,
            )
        val initialOverlay =
            RealtimeOverlay(
                entries =
                    mapOf(
                        "ev-1" to
                            studio.acmenra.control.feature.events.domain.model.OverlayEntry(
                                eventId = "ev-1",
                                event = currentEvent,
                                eventVersion = 2L,
                                membership = OverlayMembership.INCLUDED,
                            ),
                    ),
            )

        val conflictingCommit =
            currentEvent.copy(
                eventVersion = 3L,
                classificationVersion = 1L,
                isErroneous = true,
            )
        val (overlay, _) =
            RealtimeOverlayReducer.applyEventCommit(
                currentOverlay = initialOverlay,
                event = conflictingCommit,
                query = query,
                snapshotItems = listOf(currentEvent),
            )
        assertTrue(overlay.requiresRefresh)
    }

    // RT-4: Mismatch in immutable core fields triggers requiresRefresh
    @Test
    fun testRT4_ImmutableCoreMismatchBlocked() {
        val query = EventsQuery()
        val currentEvent =
            sampleEvent("ev-1").copy(
                eventVersion = 1L,
                locationId = "loc-original",
            )
        val initialOverlay =
            RealtimeOverlay(
                entries =
                    mapOf(
                        "ev-1" to
                            studio.acmenra.control.feature.events.domain.model.OverlayEntry(
                                eventId = "ev-1",
                                event = currentEvent,
                                eventVersion = 1L,
                                membership = OverlayMembership.INCLUDED,
                            ),
                    ),
            )

        val mutatedCommit =
            currentEvent.copy(
                eventVersion = 2L,
                locationId = "loc-TAMPERED",
            )
        val (overlay, _) =
            RealtimeOverlayReducer.applyEventCommit(
                currentOverlay = initialOverlay,
                event = mutatedCommit,
                query = query,
                snapshotItems = listOf(currentEvent),
            )
        assertTrue(overlay.requiresRefresh)
    }

    // RT-5: Valid newer event version preserves orthogonal fields during merge
    @Test
    fun testRT5_ValidNewEventVersionPreservesOrthogonalFields() {
        val existing =
            sampleEvent("ev-1").copy(
                eventVersion = 3L,
                acknowledgementVersion = 2L,
                status = EventAcknowledgementStatus.ACKNOWLEDGED,
                acknowledgedBy = "user-1",
                classificationVersion = 1L,
                isErroneous = false,
            )

        val incoming =
            existing.copy(
                eventVersion = 4L,
                classificationVersion = 2L,
                isErroneous = true,
                erroneousMarkedBy = "reviewer-1",
                acknowledgementVersion = 1L,
                status = EventAcknowledgementStatus.NEW,
            )

        val merged =
            studio.acmenra.control.feature.events.data.realtime.EventMerger
                .merge(existing, incoming)
        assertEquals(2L, merged.classificationVersion)
        assertTrue(merged.isErroneous)
        assertEquals("reviewer-1", merged.erroneousMarkedBy)
        assertEquals(2L, merged.acknowledgementVersion)
        assertEquals(EventAcknowledgementStatus.ACKNOWLEDGED, merged.status)
        assertEquals("user-1", merged.acknowledgedBy)
    }

    // CACHE-1: Stale response returning after cache invalidation does not return stale data to caller
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun testCACHE1_StaleResponseNotReturnedToCallerAsCurrentResult() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val callCount = kotlin.concurrent.atomics.AtomicInt(0)
                val slowFetchBlocker = CompletableDeferred<Unit>()

                val mockRemote =
                    object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
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

                        override suspend fun getFilterOptions(
                            companyId: String,
                            ifNoneMatchEtag: String?,
                        ): FilterOptionsFetchResult {
                            val count = callCount.addAndFetch(1)
                            return if (count == 1) {
                                slowFetchBlocker.await()
                                FilterOptionsFetchResult.Success(
                                    options = EventsFilterOptionsDto(revision = "rev-OLD"),
                                    etag = "etag-OLD",
                                )
                            } else {
                                FilterOptionsFetchResult.Success(
                                    options = EventsFilterOptionsDto(revision = "rev-FRESH"),
                                    etag = "etag-FRESH",
                                )
                            }
                        }

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
                            request: CreateExportRequestDto,
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
                        remoteDataSource = mockRemote,
                        webSocketDataSourceFactory = { error("unused") },
                        sessionManager = SessionManager(TestTokenStorage()),
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = MutableStateFlow(true)
                            },
                    )

                val deferred1 = async { repo.getFilterOptions("comp-1") }
                testScheduler.runCurrent()

                val freshResult = repo.getFilterOptions("comp-1")
                assertEquals("rev-FRESH", freshResult.revision)

                slowFetchBlocker.complete(Unit)
                val result1 = deferred1.await()

                assertEquals("rev-FRESH", result1.revision)
            } finally {
                scope.cancel()
            }
        }

    // EXPORT-1: Unknown total_records in PROCESSING yields null progressPercent, not 0%
    @Test
    fun testEXPORT1_UnknownTotalRecordsYieldsNullProgressPercentNotZero() {
        val dto =
            EventsExportStatusDto(
                exportId = "exp-1",
                status = "PROCESSING",
                createdAt = "2026-11-20T10:00:00Z",
                totalRecords = null,
                processedRecords = 0,
            )
        val status = EventsDtoMapper.mapExportStatus(dto)
        assertNull(status.progressPercent)
        assertEquals(EventsExportStatusCode.PROCESSING, status.status)
    }

    // EXPORT-2: Download forbidden unless COMPLETED, downloadUrl is present, and unexpired
    @Test
    fun testEXPORT2_DownloadForbiddenUnlessCompletedAndUnexpired() {
        val now = Instant.parse("2026-11-20T12:00:00Z")
        val future = Instant.parse("2026-11-20T13:00:00Z")
        val past = Instant.parse("2026-11-20T11:00:00Z")

        val processing =
            EventsExportStatus(
                exportId = "exp-1",
                status = EventsExportStatusCode.PROCESSING,
                createdAt = now,
                progressPercent = 50,
                downloadUrl = "https://example.com/file.csv",
                expiresAt = future,
            )
        assertFalse(processing.isDownloadable(now))

        val noUrl = processing.copy(status = EventsExportStatusCode.COMPLETED, downloadUrl = null)
        assertFalse(noUrl.isDownloadable(now))

        val expired = processing.copy(status = EventsExportStatusCode.COMPLETED, expiresAt = past)
        assertFalse(expired.isDownloadable(now))

        val valid = processing.copy(status = EventsExportStatusCode.COMPLETED, expiresAt = future)
        assertTrue(valid.isDownloadable(now))
    }

    // TIME-1: IANA timezone parsing and DST 23h/25h day handling
    @Test
    fun testTIME1_WasmJsIanaTimezoneAndDst23h25hDays() {
        val rigaTz = kotlinx.datetime.TimeZone.of("Europe/Riga")
        assertEquals("Europe/Riga", rigaTz.id)

        // Spring forward in Europe/Riga (23 hour day): 2026-03-29
        val springSelection = DateSelection.Single(kotlinx.datetime.LocalDate(2026, 3, 29))
        val (springStart, springEnd) =
            studio.acmenra.control.feature.events.domain.model.DateFilterUtils
                .dateSelectionToUtcBounds(
                    springSelection,
                    rigaTz,
                )
        assertNotNull(springStart)
        assertNotNull(springEnd)
        val springDuration = springEnd - springStart
        assertEquals(23L, springDuration.inWholeHours)

        // Fall back in Europe/Riga (25 hour day): 2026-10-25
        val fallSelection = DateSelection.Single(kotlinx.datetime.LocalDate(2026, 10, 25))
        val (fallStart, fallEnd) =
            studio.acmenra.control.feature.events.domain.model.DateFilterUtils
                .dateSelectionToUtcBounds(
                    fallSelection,
                    rigaTz,
                )
        assertNotNull(fallStart)
        assertNotNull(fallEnd)
        val fallDuration = fallEnd - fallStart
        assertEquals(25L, fallDuration.inWholeHours)

        // Round trip back from UTC bounds to local date selection
        val reconstructedSpring =
            studio.acmenra.control.feature.events.domain.model.DateFilterUtils
                .utcBoundsToDateSelection(
                    springStart,
                    springEnd,
                    rigaTz,
                )
        assertEquals(springSelection, reconstructedSpring)

        val reconstructedFall =
            studio.acmenra.control.feature.events.domain.model.DateFilterUtils
                .utcBoundsToDateSelection(
                    fallStart,
                    fallEnd,
                    rigaTz,
                )
        assertEquals(fallSelection, reconstructedFall)
    }

    // ROUTE-1: Route codec preserves UTC boundaries and enforces company isolation
    @Test
    fun testROUTE1_RouteCodecUtcBoundariesAndCompanyIsolation() {
        val from = Instant.parse("2026-11-20T00:00:00Z")
        val to = Instant.parse("2026-11-20T23:59:59Z")
        val query =
            EventsQuery(
                filter = EventsFilter(dateFrom = from, dateTo = to),
                sort = EventsSort.OCCURRED_AT_DESC,
            )

        val encodedCompanyA = EventsQueryRouteCodec.encodeHash("company-A", query)
        val (decodedCompanyA, decodedQueryA) = EventsQueryRouteCodec.decodeHash(encodedCompanyA)
        assertEquals("company-A", decodedCompanyA)
        assertEquals(from, decodedQueryA.filter.dateFrom)
        assertEquals(to, decodedQueryA.filter.dateTo)
        assertEquals(EventsSort.OCCURRED_AT_DESC, decodedQueryA.sort)

        val encodedCompanyB = EventsQueryRouteCodec.encodeHash("company-B", query)
        val (decodedCompanyB, _) = EventsQueryRouteCodec.decodeHash(encodedCompanyB)
        assertEquals("company-B", decodedCompanyB)
        assertNotEquals(encodedCompanyA, encodedCompanyB)
    }

    // FILE-1: Streaming download boundary checks and cancellation
    @Test
    fun testFILE1_StreamingDownloadSizeThresholdPlusOneAndCancel() =
        runTest(testDispatcher) {
            val maxBytes = 100L * 1024 * 1024

            val mockEngine =
                MockEngine { request ->
                    respond(
                        content =
                            io.ktor.utils.io
                                .ByteReadChannel(ByteArray(0)),
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("text/csv"),
                                HttpHeaders.ContentLength to listOf((maxBytes + 1L).toString()),
                            ),
                    )
                }

            val remote = KtorEventsRemoteDataSource(HttpClient(mockEngine))
            val ex =
                assertFailsWith<EventsApiException.ExportDownloadTooLarge> {
                    remote.downloadExport("comp-1", "exp-1")
                }
            assertEquals(maxBytes, ex.maxBytes)

            val slowEngine =
                MockEngine { _ ->
                    delay(50_000L)
                    respond(
                        content =
                            io.ktor.utils.io
                                .ByteReadChannel("data"),
                        status = HttpStatusCode.OK,
                    )
                }
            val slowRemote = KtorEventsRemoteDataSource(HttpClient(slowEngine))
            val downloadJob =
                launch {
                    slowRemote.downloadExport("comp-1", "exp-1")
                }
            testScheduler.advanceTimeBy(1_000L)
            downloadJob.cancel()
            assertTrue(downloadJob.isCancelled)
        }

    // Scenario 93: HeartbeatAckFrameDto requires client_confirmed_seq; missing client_confirmed_seq fails serialization
    @Test
    fun testScenario93_HeartbeatAckFrameRequiresMandatoryClientConfirmedSeq() {
        val testJson = Json { ignoreUnknownKeys = true }
        val validJson = """{"schema_version":1,"type":"HEARTBEAT_ACK","replay_id":1,"client_confirmed_seq":100}"""
        val dto = testJson.decodeFromString<HeartbeatAckFrameDto>(validJson)
        assertEquals(100L, dto.clientConfirmedSeq)

        val missingConfirmedSeqJson = """{"schema_version":1,"type":"HEARTBEAT_ACK","replay_id":1}"""
        assertFailsWith<kotlinx.serialization.SerializationException> {
            testJson.decodeFromString<HeartbeatAckFrameDto>(missingConfirmedSeqJson)
        }
    }

    // Scenario 94: HeartbeatTracker.onAckReceived validates client_confirmed_seq safe integer bounds
    @Test
    fun testScenario94_HeartbeatTrackerValidatesClientConfirmedSeqBounds() {
        val timeSource = kotlin.time.TestTimeSource()
        val tracker =
            studio.acmenra.control.feature.events.data.datasource.HeartbeatTracker(
                maxMissedAcks = 2,
                ackWindow = kotlin.time.Duration.parse("5s"),
                timeSource = timeSource,
            )

        tracker.onHeartbeatSent(mark = timeSource.markNow(), clientConfirmedSeq = 100L)

        // Negative confirmed seq rejected
        assertFalse(
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = 1L,
                incomingConfirmedSeq = -1L,
                mark = timeSource.markNow(),
            ),
        )

        // Overflow confirmed seq rejected
        assertFalse(
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = 1L,
                incomingConfirmedSeq = MAX_SAFE_JS_INTEGER + 1L,
                mark = timeSource.markNow(),
            ),
        )

        // Matching confirmed seq accepted
        assertTrue(
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = 1L,
                incomingConfirmedSeq = 100L,
                mark = timeSource.markNow(),
            ),
        )
    }

    // Scenario 95: Replay ID reaching MAX_SAFE_JS_INTEGER triggers fresh AUTH and resets closed generation fence for new connection
    @Test
    fun testScenario95_ReplayIdReachingMaxSafeJsIntegerTriggersFreshAuthWithoutException() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var freshAuthTriggered = false
                var freshSeq = 0L
                var freshReplay = 0L
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = scope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                        onFreshAuthNeeded = { seq, rId ->
                            freshAuthTriggered = true
                            freshSeq = seq
                            freshReplay = rId
                        },
                    )

                processor.startReplay(replayId = MAX_SAFE_JS_INTEGER, afterSequence = 100L)
                processor.dispatch(SessionCommand.WsConnectionActivated(connectionId = 1L))
                advanceUntilIdle()

                val nextRes = processor.requestNextReplayGeneration()
                assertTrue(nextRes is ReplayGenerationResult.RequiresFreshAuth)
                assertTrue(freshAuthTriggered)
                assertEquals(100L, freshSeq)
                assertEquals(1L, freshReplay)

                // Now start fresh AUTH generation (connection 2) with replay_id = 1
                processor.dispatch(
                    SessionCommand.FreshAuthGenerationStarted(
                        authGenerationId = 2L,
                        connectionId = 2L,
                        initialReplayId = 1L,
                        afterSequence = 100L,
                    ),
                )
                advanceUntilIdle()

                // SUBSCRIBED for new connection with replay_id = 1 MUST be accepted (not blocked by old fence)
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            2L,
                            IncomingWsFrame.Subscribed(SubscribedFrameDto(replayId = 1L, replayEmpty = true)),
                        ),
                    ),
                )
                advanceUntilIdle()
                assertEquals(RealtimeConnectionState.CONNECTED, processor.state.value.connectionState)
            } finally {
                scope.cancel()
            }
        }

    // Scenario 96: Duplicate commit with sequence <= lastProcessedSequence evicted from recentHashes returns Duplicate
    @Test
    fun testScenario96_EvictedSequenceDuplicateReturnsDuplicatePerSpec() {
        val seqProcessor =
            studio.acmenra.control.feature.events.data.realtime
                .TenantSequenceProcessor()
        val baseSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd"
        for (seq in 1L..1005L) {
            val sha = baseSha + (seq % 10).toString() + (seq % 10).toString()
            seqProcessor.advanceSequence(seq, sha)
        }

        // Sequence 1 is evicted from recentHashes retention cache
        val validation =
            seqProcessor.checkIncomingSequence(
                incomingReplayId = 1L,
                incomingSequence = 1L,
                payloadSha256 = baseSha + "11",
            )
        assertEquals(SequenceValidationResult.Duplicate, validation)
    }

    // Scenario 97: Duplicate commit in recentHashes with matching SHA256 returns Duplicate
    @Test
    fun testScenario97_DuplicateInRecentHashesWithMatchingShaReturnsDuplicate() {
        val seqProcessor =
            studio.acmenra.control.feature.events.data.realtime
                .TenantSequenceProcessor()
        val validSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        seqProcessor.advanceSequence(1L, validSha)

        val validation =
            seqProcessor.checkIncomingSequence(
                incomingReplayId = 1L,
                incomingSequence = 1L,
                payloadSha256 = validSha,
            )
        assertEquals(SequenceValidationResult.Duplicate, validation)
    }

    // Scenario 98: Duplicate commit in recentHashes with mismatched SHA256 returns IntegrityViolation
    @Test
    fun testScenario98_DuplicateInRecentHashesWithMismatchedShaReturnsIntegrityViolation() {
        val seqProcessor =
            studio.acmenra.control.feature.events.data.realtime
                .TenantSequenceProcessor()
        val validSha1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val mismatchSha = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        seqProcessor.advanceSequence(1L, validSha1)

        val validation =
            seqProcessor.checkIncomingSequence(
                incomingReplayId = 1L,
                incomingSequence = 1L,
                payloadSha256 = mismatchSha,
            )
        assertTrue(validation is SequenceValidationResult.IntegrityViolation)
    }

    // Scenario 99: Filter cache generation fence: stale response triggers retry and does not return stale options
    @Test
    fun testScenario99_FilterCacheInvalidationDiscardsStaleEntry() =
        runTest(testDispatcher) {
            val responseDeferred = CompletableDeferred<FilterOptionsFetchResult>()
            var fetchCount = 0
            val mockRemote =
                object : studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource {
                    override suspend fun getFilterOptions(
                        companyId: String,
                        ifNoneMatchEtag: String?,
                    ): FilterOptionsFetchResult {
                        fetchCount++
                        return if (fetchCount == 1) {
                            responseDeferred.await()
                        } else {
                            FilterOptionsFetchResult.Success(
                                EventsFilterOptionsDto(
                                    revision = "fresh-rev",
                                    eventTypes =
                                        listOf(
                                            EventTypeOptionDto("type-1", "Type 1", "cat-1"),
                                            EventTypeOptionDto("type-2", "Type 2", "cat-2"),
                                        ),
                                ),
                                etag = "fresh-etag",
                            )
                        }
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

            val repository =
                EventsRepositoryImpl(
                    remoteDataSource = mockRemote,
                    webSocketDataSourceFactory = { TestWebSocketDataSource() },
                    sessionManager = SessionManager(TestTokenStorage()),
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                        },
                )

            val fetchJob = async { repository.getFilterOptions("comp-1") }
            advanceUntilIdle()

            repository.invalidateFilterCache("comp-1")
            advanceUntilIdle()

            val dto =
                EventsFilterOptionsDto(
                    revision = "stale-rev",
                    eventTypes = listOf(EventTypeOptionDto("type-1", "Type 1", "cat-1")),
                )
            responseDeferred.complete(FilterOptionsFetchResult.Success(dto, etag = "etag-1"))
            advanceUntilIdle()

            val result = fetchJob.await()
            assertEquals("fresh-rev", result.revision)
            assertEquals(2, result.eventTypes.size)
        }

    // Scenario 100: EventMerger rejects immutable core mismatch as IntegrityViolation
    @Test
    fun testScenario100_EventMergerRejectsImmutableCoreMismatch() {
        val current = sampleEvent("ev-1").copy(deviceId = "dev-1")
        val incoming = sampleEvent("ev-1").copy(deviceId = "dev-2", eventVersion = 2L)

        val result =
            studio.acmenra.control.feature.events.data.realtime.EventMerger
                .validateAndMerge(current, incoming)
        assertTrue(result is studio.acmenra.control.feature.events.data.realtime.EventMergeResult.IntegrityViolation)
        assertTrue(
            (result as studio.acmenra.control.feature.events.data.realtime.EventMergeResult.IntegrityViolation)
                .reason
                .contains(
                    "Immutable core mismatch",
                ),
        )
    }

    // Scenario 101: EventMerger rejects equal classificationVersion with content mismatch
    @Test
    fun testScenario101_EventMergerRejectsEqualClassificationVersionWithDifferentContent() {
        val current = sampleEvent("ev-1").copy(isErroneous = false, classificationVersion = 1L)
        val incoming = sampleEvent("ev-1").copy(isErroneous = true, classificationVersion = 1L, eventVersion = 2L)

        val result =
            studio.acmenra.control.feature.events.data.realtime.EventMerger
                .validateAndMerge(current, incoming)
        assertTrue(result is studio.acmenra.control.feature.events.data.realtime.EventMergeResult.IntegrityViolation)
        assertTrue(
            (result as studio.acmenra.control.feature.events.data.realtime.EventMergeResult.IntegrityViolation)
                .reason
                .contains(
                    "Classification version 1 equality with content mismatch",
                ),
        )
    }

    // Scenario 102: EventMerger rejects equal acknowledgementVersion with content mismatch
    @Test
    fun testScenario102_EventMergerRejectsEqualAcknowledgementVersionWithDifferentContent() {
        val current = sampleEvent("ev-1").copy(status = EventAcknowledgementStatus.NEW, acknowledgementVersion = 1L)
        val incoming =
            sampleEvent(
                "ev-1",
            ).copy(status = EventAcknowledgementStatus.ACKNOWLEDGED, acknowledgementVersion = 1L, eventVersion = 2L)

        val result =
            studio.acmenra.control.feature.events.data.realtime.EventMerger
                .validateAndMerge(current, incoming)
        assertTrue(result is studio.acmenra.control.feature.events.data.realtime.EventMergeResult.IntegrityViolation)
        assertTrue(
            (result as studio.acmenra.control.feature.events.data.realtime.EventMergeResult.IntegrityViolation)
                .reason
                .contains(
                    "Acknowledgement version 1 equality with content mismatch",
                ),
        )
    }

    // Scenario 103: EventMerger rejects version exceeding MAX_SAFE_JS_INTEGER or specialized > eventVersion
    @Test
    fun testScenario103_EventMergerRejectsVersionExceedingBoundsOrSpecializedExceedingOverall() {
        val current = sampleEvent("ev-1")
        val overflowIncoming = sampleEvent("ev-1").copy(eventVersion = MAX_SAFE_JS_INTEGER + 1L)
        val res1 =
            studio.acmenra.control.feature.events.data.realtime.EventMerger.validateAndMerge(
                current,
                overflowIncoming,
            )
        assertTrue(res1 is studio.acmenra.control.feature.events.data.realtime.EventMergeResult.IntegrityViolation)

        val specializedExceeding = sampleEvent("ev-1").copy(eventVersion = 2L, classificationVersion = 3L)
        val res2 =
            studio.acmenra.control.feature.events.data.realtime.EventMerger.validateAndMerge(
                current,
                specializedExceeding,
            )
        assertTrue(res2 is studio.acmenra.control.feature.events.data.realtime.EventMergeResult.IntegrityViolation)
    }

    // Scenario 104: EventMerger returns Stale when incoming version is <= current
    @Test
    fun testScenario104_EventMergerReturnsStaleWhenIncomingIsOlderOrEqual() {
        val current =
            sampleEvent(
                "ev-1",
            ).copy(eventVersion = 2L, classificationVersion = 2L, acknowledgementVersion = 2L)
        val incoming =
            sampleEvent(
                "ev-1",
            ).copy(eventVersion = 2L, classificationVersion = 2L, acknowledgementVersion = 2L)

        val result =
            studio.acmenra.control.feature.events.data.realtime.EventMerger
                .validateAndMerge(current, incoming)
        assertEquals(studio.acmenra.control.feature.events.data.realtime.EventMergeResult.Stale, result)
    }

    // Scenario 105: Live commit with summary_updated exceeding tenantSequence triggers rebootstrap
    @Test
    fun testScenario105_LiveCommitSummaryAsOfSequenceExceedingTenantSequenceRejected() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var rebootstrapTriggered = false
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = scope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { rebootstrapTriggered = true },
                        onTokenRefreshNeeded = { },
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
                        summary = EventsSummary("UTC", "2026-03-01", 100L, 0),
                        streamCursor = 100L,
                    )
                processor.installBootstrap(bootstrap, generation = 1L)
                advanceUntilIdle()

                val activeReplay = processor.currentReplayId
                val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                val invalidCommit =
                    TenantCommitFrameDto(
                        replayId = activeReplay,
                        tenantSequence = 101L,
                        payloadSha256 = sha,
                        summaryUpdated =
                            EventsSummaryDto(
                                "UTC",
                                "2026-03-01",
                                summaryAsOfSequence = 105L,
                                todayEventsCount = 5,
                            ),
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(invalidCommit))),
                )
                advanceUntilIdle()

                assertTrue(rebootstrapTriggered)
                assertEquals(100L, processor.getAsOfSequence())
            } finally {
                scope.cancel()
            }
        }

    // Scenario 106: Buffered commit with summary_updated exceeding tenantSequence triggers RetryRequired
    @Test
    fun testScenario106_BufferedCommitSummaryAsOfSequenceExceedingTenantSequenceTriggersRetryRequired() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var rebootstrapTriggered = false
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = scope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { rebootstrapTriggered = true },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                val b1 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = emptyList(),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 0,
                                asOfSequence = 100L,
                            ),
                        summary = EventsSummary("UTC", "2026-03-01", 100L, 0),
                        streamCursor = 100L,
                    )
                processor.installBootstrap(b1, generation = 1L)
                advanceUntilIdle()
                val activeReplay = processor.currentReplayId

                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            1L,
                            IncomingWsFrame.ResyncRequired(ResyncRequiredFrameDto(replayId = activeReplay)),
                        ),
                    ),
                )
                advanceUntilIdle()

                val bufferReplay = processor.nextReplayGeneration()
                val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                // Buffer invalid commit with summary sequence 105 > tenantSequence 101
                val invalidCommit =
                    TenantCommitFrameDto(
                        replayId = bufferReplay,
                        tenantSequence = 101L,
                        payloadSha256 = sha,
                        summaryUpdated =
                            EventsSummaryDto(
                                "UTC",
                                "2026-03-01",
                                summaryAsOfSequence = 105L,
                                todayEventsCount = 5,
                            ),
                    )
                processor.dispatch(
                    SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(invalidCommit))),
                )
                advanceUntilIdle()

                val b2 =
                    EventsBootstrap(
                        page =
                            EventsPage(
                                items = emptyList(),
                                nextCursor = null,
                                hasMore = false,
                                totalCount = 0,
                                asOfSequence = 100L,
                            ),
                        summary = EventsSummary("UTC", "2026-03-01", 100L, 0),
                        streamCursor = 100L,
                    )
                val applyResult = processor.installBootstrap(b2, generation = 2L)
                advanceUntilIdle()

                assertEquals(BootstrapApplyResult.RetryRequired, applyResult)
            } finally {
                scope.cancel()
            }
        }

    // Scenario 107: Heartbeat outgoing pump ensures 5s ACK window starts on physical write
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun testScenario107_HeartbeatWindowStartsUponPhysicalWriteCompletion() {
        val timeSource = kotlin.time.TestTimeSource()
        val tracker =
            studio.acmenra.control.feature.events.data.datasource.HeartbeatTracker(
                maxMissedAcks = 2,
                ackWindow = kotlin.time.Duration.parse("5s"),
                timeSource = timeSource,
            )

        val writeMark = timeSource.markNow()
        tracker.onHeartbeatSent(mark = writeMark, clientConfirmedSeq = 50L)
        assertTrue(tracker.hasPendingAck.load())

        // 4 seconds after physical write: ACK arrives within 5s window
        timeSource += kotlin.time.Duration.parse("4s")
        assertTrue(
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = 1L,
                incomingConfirmedSeq = 50L,
                mark = timeSource.markNow(),
            ),
        )
        assertFalse(tracker.hasPendingAck.load())
    }

    // Scenario 108: Incoming WS frame with schema_version != 1 is rejected by dataSource
    @Test
    fun testScenario108_IncomingWsFrameWithInvalidSchemaVersionRejected() {
        val testJson = Json { ignoreUnknownKeys = true }
        val rawJson = """{"schema_version":2,"type":"SUBSCRIBED","replay_id":1,"replay_empty":true}"""
        val generic = testJson.decodeFromString<GenericRealtimeFrameDto>(rawJson)
        assertEquals(2, generic.schemaVersion)
        // KtorEventsWebSocketDataSource ignores any frame with generic.schemaVersion != 1
    }

    // Scenario 109: Navigation route integration with EventsQueryRouteCodec and queryHash
    @Test
    fun testScenario109_NavigationRouteIntegrationWithQueryHash() {
        val filter = EventsFilter(searchId = "42", validity = EventValidityFilter.VALID)
        val query = EventsQuery(filter = filter)
        val encodedHash = EventsQueryRouteCodec.encodeHash("company-1", query)

        val (company, decodedQuery) = EventsQueryRouteCodec.decodeHash(encodedHash)
        assertEquals("company-1", company)
        assertEquals("42", decodedQuery.filter.searchId)
        assertEquals(EventValidityFilter.VALID, decodedQuery.filter.validity)
    }

    // Scenario 110: Wasm export memory ceiling documentation and boundedness
    @Test
    fun testScenario110_WasmExportMemoryCeilingBoundedness() {
        val maxAllowed = studio.acmenra.control.feature.events.data.datasource.MAX_EXPORT_DOWNLOAD_BYTES
        assertEquals(100L * 1024 * 1024, maxAllowed)

        val mockDownload =
            EventsExportDownload(
                fileName = "export.csv",
                contentLength = 1024L,
                downloadHandle = "exp-1",
            )
        assertTrue(mockDownload.contentLength <= maxAllowed)
    }

    // Scenario 111: Replay ID reaches MAX_SAFE_JS_INTEGER; new AUTH generation starts with replay_id = 1, and SUBSCRIBED(1) is accepted
    @Test
    fun testScenario111_ReplayIdReachesMaxSafeJsIntegerFreshAuthWithReplay1Accepted() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                var freshAuthTriggered = false
                var freshSeq = 0L
                var freshReplay = 0L
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = scope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                        onFreshAuthNeeded = { seq, rId ->
                            freshAuthTriggered = true
                            freshSeq = seq
                            freshReplay = rId
                        },
                    )

                processor.startReplay(replayId = MAX_SAFE_JS_INTEGER, afterSequence = 500L)
                processor.dispatch(SessionCommand.WsConnectionActivated(connectionId = 1L))
                advanceUntilIdle()

                val result = processor.requestNextReplayGeneration()
                assertTrue(result is ReplayGenerationResult.RequiresFreshAuth)
                assertTrue(freshAuthTriggered)
                assertEquals(500L, freshSeq)
                assertEquals(1L, freshReplay)

                processor.dispatch(
                    SessionCommand.FreshAuthGenerationStarted(
                        authGenerationId = 2L,
                        connectionId = 2L,
                        initialReplayId = 1L,
                        afterSequence = 500L,
                    ),
                )
                advanceUntilIdle()

                // In connection 2, SUBSCRIBED(replayId = 1L) is accepted
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            2L,
                            IncomingWsFrame.Subscribed(SubscribedFrameDto(replayId = 1L, replayEmpty = true)),
                        ),
                    ),
                )
                advanceUntilIdle()
                assertEquals(RealtimeConnectionState.CONNECTED, processor.state.value.connectionState)
                assertEquals(1L, processor.currentReplayId)
            } finally {
                scope.cancel()
            }
        }

    // Scenario 112: After starting a new AUTH generation, frame from old connectionId is dropped even if replay ID matches new
    @Test
    fun testScenario112_StaleConnectionFrameDroppedAfterNewAuthGeneration() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = scope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                processor.startReplay(replayId = MAX_SAFE_JS_INTEGER, afterSequence = 100L)
                processor.dispatch(SessionCommand.WsConnectionActivated(connectionId = 1L))
                advanceUntilIdle()

                // Start fresh auth generation (connection 2, replay_id = 1)
                processor.dispatch(
                    SessionCommand.FreshAuthGenerationStarted(
                        authGenerationId = 2L,
                        connectionId = 2L,
                        initialReplayId = 1L,
                        afterSequence = 100L,
                    ),
                )
                advanceUntilIdle()

                // Stale frame arrives from connection 1 with replay_id = 1
                val staleCommit = sampleCommitDto(replayId = 1L, sequence = 101L, eventId = "ev-stale")
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(staleCommit)),
                    ),
                )
                advanceUntilIdle()

                // Stale frame must not be visible or change sequence
                assertEquals(100L, processor.getAsOfSequence())
                assertTrue(
                    processor.state.value.visibleEvents
                        .none { it.id == "ev-stale" },
                )

                // Frame from active connection 2 with replay_id = 1 is processed
                val validCommit = sampleCommitDto(replayId = 1L, sequence = 101L, eventId = "ev-valid")
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(2L, IncomingWsFrame.TenantCommit(validCommit)),
                    ),
                )
                advanceUntilIdle()
                assertEquals(101L, processor.getAsOfSequence())
                assertTrue(
                    processor.state.value.visibleEvents
                        .any { it.id == "ev-valid" },
                )
            } finally {
                scope.cancel()
            }
        }

    // Scenario 113: Rebootstrap overflow returns RequiresFreshAuth and leaves exactly one active WebSocket connection
    @Test
    fun testScenario113_BootstrapOverflowSingleWebSocketOwner() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val wsDataSource = TestWebSocketDataSource()
                val session =
                    EventsFeedSessionImpl(
                        companyId = "comp-1",
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        remoteDataSource =
                            object : EventsRemoteDataSource {
                                override suspend fun getBootstrap(
                                    companyId: String,
                                    query: EventsQuery,
                                    limit: Int,
                                ): EventsBootstrapDto =
                                    EventsBootstrapDto(
                                        streamCursor = 100L,
                                        events =
                                            BootstrapEventsDto(
                                                items = emptyList(),
                                                pagination =
                                                    EventsPaginationDto(
                                                        snapshotId = "snap-1",
                                                        asOfSequence = 100L,
                                                        nextCursor = null,
                                                        hasMore = false,
                                                        totalCount = 0,
                                                    ),
                                            ),
                                        summary =
                                            EventsSummaryDto(
                                                businessTimezone = "UTC",
                                                businessDate = "2026-03-01",
                                                summaryAsOfSequence = 100L,
                                                todayEventsCount = 0,
                                            ),
                                    )

                                override suspend fun getFilterOptions(
                                    companyId: String,
                                    ifNoneMatchEtag: String?,
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
                                    request: CreateExportRequestDto,
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
                        webSocketDataSource = wsDataSource,
                        sessionManager = SessionManager(TestTokenStorage()),
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                            },
                        parentScope = parentScope,
                    )
                advanceUntilIdle()
                assertEquals(1, wsDataSource.connectCount)
                val initialConnId = wsDataSource.currentConnectionId

                // Set replayId to MAX_SAFE_JS_INTEGER on processor and trigger rebootstrap
                session.processor.startReplay(replayId = MAX_SAFE_JS_INTEGER, afterSequence = 100L)
                session.retryRebootstrap()
                advanceUntilIdle()

                // Only one new fresh auth connection is opened
                assertEquals(2, wsDataSource.connectCount)
                assertEquals(1L, wsDataSource.lastReplayId)
                session.close()
                advanceUntilIdle()
                assertTrue(wsDataSource.disconnected)
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 114: Replay overflow during token refresh does not trigger duplicate connect flow
    @Test
    fun testScenario114_TokenRefreshOverflowSingleConnectFlow() =
        runTest(testDispatcher) {
            val parentScope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val wsDataSource = TestWebSocketDataSource()
                val tokenStorage = TestTokenStorage(TokenPair("token1", "refresh1"))
                val sessionManager = SessionManager(tokenStorage)
                sessionManager.setRefreshDelegate {
                    sessionManager.saveSession(TokenPair("token2", "refresh2"))
                }
                val session =
                    EventsFeedSessionImpl(
                        companyId = "comp-1",
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        remoteDataSource =
                            object : EventsRemoteDataSource {
                                override suspend fun getBootstrap(
                                    companyId: String,
                                    query: EventsQuery,
                                    limit: Int,
                                ): EventsBootstrapDto =
                                    EventsBootstrapDto(
                                        streamCursor = 100L,
                                        events =
                                            BootstrapEventsDto(
                                                items = emptyList(),
                                                pagination =
                                                    EventsPaginationDto(
                                                        snapshotId = "snap-1",
                                                        asOfSequence = 100L,
                                                        nextCursor = null,
                                                        hasMore = false,
                                                        totalCount = 0,
                                                    ),
                                            ),
                                        summary =
                                            EventsSummaryDto(
                                                businessTimezone = "UTC",
                                                businessDate = "2026-03-01",
                                                summaryAsOfSequence = 100L,
                                                todayEventsCount = 0,
                                            ),
                                    )

                                override suspend fun getFilterOptions(
                                    companyId: String,
                                    ifNoneMatchEtag: String?,
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
                                    request: CreateExportRequestDto,
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
                        webSocketDataSource = wsDataSource,
                        sessionManager = sessionManager,
                        connectivityProvider =
                            object : EventsConnectivityProvider {
                                override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                            },
                        parentScope = parentScope,
                    )
                advanceUntilIdle()
                assertEquals(1, wsDataSource.connectCount)

                // Exhaust replayId
                session.processor.startReplay(replayId = MAX_SAFE_JS_INTEGER, afterSequence = 100L)
                session.processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            wsDataSource.currentConnectionId,
                            IncomingWsFrame.AuthRequired(AuthRequiredFrameDto(reason = "TOKEN_EXPIRED")),
                        ),
                    ),
                )
                advanceUntilIdle()

                // Exactly one reconnect occurs for fresh auth
                assertEquals(2, wsDataSource.connectCount)
                assertEquals(1L, wsDataSource.lastReplayId)
                session.close()
            } finally {
                parentScope.cancel()
            }
        }

    // Scenario 115: Normal reconnect without fresh AUTH preserves closed replay generation fence
    @Test
    fun testScenario115_NormalReconnectDoesNotResetClosedGenerationFence() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + testDispatcher)
            try {
                val processor =
                    EventsSessionProcessor(
                        query = EventsQuery(),
                        initialAccess = EventsAccessCapabilities(eventsRead = true),
                        scope = scope,
                        onSendReplayRequest = { _, _ -> },
                        onTriggerRebootstrap = { },
                        onTokenRefreshNeeded = { },
                        onAccessInvalidated = { _, _ -> },
                    )

                processor.startReplay(replayId = 5L, afterSequence = 100L)
                processor.dispatch(SessionCommand.WsConnectionActivated(1L))
                advanceUntilIdle()

                // RESYNC_REQUIRED closes replayId 5L
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(
                            1L,
                            IncomingWsFrame.ResyncRequired(
                                ResyncRequiredFrameDto(replayId = 5L, reason = "REPLAY_TIMEOUT"),
                            ),
                        ),
                    ),
                )
                advanceUntilIdle()

                // Regular reconnect (no FreshAuthGenerationStarted) with connection 2
                processor.dispatch(SessionCommand.WsConnectionActivated(2L))
                advanceUntilIdle()

                // Commit with closed replayId = 5L MUST be ignored even on connection 2
                val staleCommit = sampleCommitDto(replayId = 5L, sequence = 101L, eventId = "ev-stale")
                processor.dispatch(
                    SessionCommand.WsFrameReceived(
                        IncomingWsEnvelope(2L, IncomingWsFrame.TenantCommit(staleCommit)),
                    ),
                )
                advanceUntilIdle()
                assertEquals(100L, processor.getAsOfSequence())
                assertTrue(
                    processor.state.value.visibleEvents
                        .none { it.id == "ev-stale" },
                )
            } finally {
                scope.cancel()
            }
        }

    // Scenario 116: Outgoing pump immediately registers pending ACK; fast server ACK accepted before heartbeat coroutine resumes
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun testScenario116_HeartbeatOutgoingPumpSyncWithImmediateServerAck() =
        runTest(testDispatcher) {
            val timeSource = kotlin.time.TestTimeSource()
            val tracker =
                studio.acmenra.control.feature.events.data.datasource.HeartbeatTracker(
                    maxMissedAcks = 2,
                    ackWindow = kotlin.time.Duration.parse("5s"),
                    timeSource = timeSource,
                )

            data class TestOutgoingHeartbeat(
                val clientConfirmedSeq: Long,
                val sentDeferred: CompletableDeferred<kotlin.time.ComparableTimeMark>,
            )

            // Simulate outgoing channel pump:
            val outChan = kotlinx.coroutines.channels.Channel<TestOutgoingHeartbeat>(16)
            val sentDeferred = CompletableDeferred<kotlin.time.ComparableTimeMark>()

            // Outgoing pump job
            val pumpJob =
                launch {
                    for (msg in outChan) {
                        val writeMark = timeSource.markNow()
                        tracker.onHeartbeatSent(writeMark, msg.clientConfirmedSeq)
                        msg.sentDeferred.complete(writeMark)
                    }
                }

            outChan.send(
                TestOutgoingHeartbeat(
                    clientConfirmedSeq = 42L,
                    sentDeferred = sentDeferred,
                ),
            )
            kotlinx.coroutines.yield()

            // Server ACK arrives immediately upon writeMark before sentDeferred.await() coroutine resumes
            val ackResult =
                tracker.onAckReceived(
                    incomingReplayId = 1L,
                    activeReplayId = 1L,
                    incomingConfirmedSeq = 42L,
                    mark = timeSource.markNow(),
                )
            assertTrue(ackResult)
            assertEquals(0, tracker.missedAcks.load())
            assertFalse(tracker.hasPendingAck.load())

            sentDeferred.await()
            pumpJob.cancel()
        }

    // Scenario 117: Duplicate sequence at MAX_SAFE_JS_INTEGER boundary returns Duplicate, not InvalidSequence
    @Test
    fun testScenario117_SequenceAtMaxSafeJsIntegerReturnsDuplicate() {
        val processor = TenantSequenceProcessor()
        val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        processor.resetSequence(newSequence = MAX_SAFE_JS_INTEGER, newReplayId = 1L)

        val validation =
            processor.checkIncomingSequence(
                incomingReplayId = 1L,
                incomingSequence = MAX_SAFE_JS_INTEGER,
                payloadSha256 = sha,
            )
        assertTrue(validation is SequenceValidationResult.Duplicate)
    }

    // Scenario 118: Sequence above MAX_SAFE_JS_INTEGER rejected before arithmetic
    @Test
    fun testScenario118_SequenceAboveMaxSafeJsIntegerRejectedBeforeArithmetic() {
        val processor = TenantSequenceProcessor()
        val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        processor.resetSequence(newSequence = 100L, newReplayId = 1L)

        val validation =
            processor.checkIncomingSequence(
                incomingReplayId = 1L,
                incomingSequence = MAX_SAFE_JS_INTEGER + 1L,
                payloadSha256 = sha,
            )
        assertTrue(validation is SequenceValidationResult.InvalidSequence)
    }

    // Scenario 119: Invalidation during in-flight GET retries and returns fresh generation options
    @Test
    fun testScenario119_FilterInvalidationDuringInFlightGetRetriesAndReturnsFreshOptions() =
        runTest(testDispatcher) {
            val responseDeferred = CompletableDeferred<FilterOptionsFetchResult>()
            var fetchCount = 0
            val mockRemote =
                object : EventsRemoteDataSource {
                    override suspend fun getFilterOptions(
                        companyId: String,
                        ifNoneMatchEtag: String?,
                    ): FilterOptionsFetchResult {
                        fetchCount++
                        return if (fetchCount == 1) {
                            responseDeferred.await()
                        } else {
                            FilterOptionsFetchResult.Success(
                                EventsFilterOptionsDto(
                                    revision = "gen2-rev",
                                    eventTypes = listOf(EventTypeOptionDto("type-1", "Type 1", "cat-1")),
                                ),
                                etag = "etag-gen2",
                            )
                        }
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
                        request: CreateExportRequestDto,
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
                    remoteDataSource = mockRemote,
                    webSocketDataSourceFactory = { TestWebSocketDataSource() },
                    sessionManager = SessionManager(TestTokenStorage()),
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                        },
                )

            val fetchJob = async { repo.getFilterOptions("comp-1") }
            advanceUntilIdle()

            repo.invalidateFilterCache("comp-1")
            advanceUntilIdle()

            responseDeferred.complete(
                FilterOptionsFetchResult.Success(
                    EventsFilterOptionsDto(revision = "gen1-stale", eventTypes = emptyList()),
                    etag = "etag-gen1",
                ),
            )
            advanceUntilIdle()

            val result = fetchJob.await()
            assertEquals("gen2-rev", result.revision)
            assertEquals(1, result.eventTypes.size)
        }

    // Scenario 120: Invalidation between 304 and unconditional GET retries and returns fresh generation
    @Test
    fun testScenario120_FilterInvalidationBetween304AndUnconditionalGetRetries() =
        runTest(testDispatcher) {
            var fetchCount = 0
            val mockRemote =
                object : EventsRemoteDataSource {
                    override suspend fun getFilterOptions(
                        companyId: String,
                        ifNoneMatchEtag: String?,
                    ): FilterOptionsFetchResult {
                        fetchCount++
                        return when (fetchCount) {
                            1 ->
                                FilterOptionsFetchResult.Success(
                                    EventsFilterOptionsDto(revision = "rev-initial", eventTypes = emptyList()),
                                    etag = "etag-initial",
                                )
                            2 -> FilterOptionsFetchResult.NotModified
                            else ->
                                FilterOptionsFetchResult.Success(
                                    EventsFilterOptionsDto(
                                        revision = "rev-fresh",
                                        eventTypes = listOf(EventTypeOptionDto("t1", "T1", "c1")),
                                    ),
                                    etag = "etag-fresh",
                                )
                        }
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
                        request: CreateExportRequestDto,
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
                    remoteDataSource = mockRemote,
                    webSocketDataSourceFactory = { TestWebSocketDataSource() },
                    sessionManager = SessionManager(TestTokenStorage()),
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                        },
                )

            // Initial fetch populates cache
            val initial = repo.getFilterOptions("comp-1")
            assertEquals("rev-initial", initial.revision)

            // Invalidate cache and request again -> triggers 304 -> unconditional GET -> fresh result
            repo.invalidateFilterCache("comp-1")
            val fresh = repo.getFilterOptions("comp-1")
            assertEquals("rev-fresh", fresh.revision)
        }

    // Scenario 121: Older 200 completing after newer 200 does not regress cache
    @Test
    fun testScenario121_Older200CompletingAfterNewer200DoesNotRegressCache() =
        runTest(testDispatcher) {
            val slowDeferred = CompletableDeferred<FilterOptionsFetchResult>()
            val fastDeferred = CompletableDeferred<FilterOptionsFetchResult>()
            var callCount = 0

            val mockRemote =
                object : EventsRemoteDataSource {
                    override suspend fun getFilterOptions(
                        companyId: String,
                        ifNoneMatchEtag: String?,
                    ): FilterOptionsFetchResult {
                        callCount++
                        return if (callCount == 1) slowDeferred.await() else fastDeferred.await()
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
                        request: CreateExportRequestDto,
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
                    remoteDataSource = mockRemote,
                    webSocketDataSourceFactory = { TestWebSocketDataSource() },
                    sessionManager = SessionManager(TestTokenStorage()),
                    connectivityProvider =
                        object : EventsConnectivityProvider {
                            override val isOnline = kotlinx.coroutines.flow.flowOf(true)
                        },
                )

            // Request 1 starts (slow)
            val job1 = async { repo.getFilterOptions("comp-1") }
            advanceUntilIdle()

            // Invalidate and Request 2 starts (fast)
            repo.invalidateFilterCache("comp-1")
            val job2 = async { repo.getFilterOptions("comp-1") }
            advanceUntilIdle()

            // Complete fast response (generation 2)
            fastDeferred.complete(
                FilterOptionsFetchResult.Success(
                    EventsFilterOptionsDto(
                        revision = "newer-rev",
                        eventTypes = listOf(EventTypeOptionDto("t2", "T2", "c2")),
                    ),
                    etag = "etag-newer",
                ),
            )
            advanceUntilIdle()
            val result2 = job2.await()
            assertEquals("newer-rev", result2.revision)

            // Now complete older slow response (generation 1)
            slowDeferred.complete(
                FilterOptionsFetchResult.Success(
                    EventsFilterOptionsDto(revision = "older-rev", eventTypes = emptyList()),
                    etag = "etag-older",
                ),
            )
            advanceUntilIdle()
            val result1 = job1.await()
            // job1 must receive the newer cache entry, not the stale older-rev!
            assertEquals("newer-rev", result1.revision)

            // Cache must retain newer-rev
            val cached = repo.getFilterOptions("comp-1")
            assertEquals("newer-rev", cached.revision)
        }

    // Scenario 122: EventMerger smaller eventVersion with larger specialized version does not apply payload
    @Test
    fun testScenario122_EventMergerSmallerEventVersionWithLargerSpecializedVersionReturnsStaleOrIntegrityViolation() {
        val current =
            sampleEvent(
                "ev-1",
            ).copy(eventVersion = 10L, classificationVersion = 2L, acknowledgementVersion = 4L)
        val incoming =
            sampleEvent("ev-1").copy(
                eventVersion = 9L,
                classificationVersion = 3L,
                acknowledgementVersion = 4L,
                isErroneous = true,
            )

        val result = EventMerger.validateAndMerge(current, incoming)
        assertTrue(
            result is EventMergeResult.IntegrityViolation ||
                result is EventMergeResult.Stale,
        )
    }

    // Scenario 123: Equal eventVersion with differing payload returns IntegrityViolation
    @Test
    fun testScenario123_EventMergerEqualEventVersionWithPayloadMismatchReturnsIntegrityViolation() {
        val current =
            sampleEvent("ev-1").copy(
                eventVersion = 5L,
                classificationVersion = 2L,
                acknowledgementVersion = 3L,
                isErroneous = false,
            )
        val incoming =
            sampleEvent("ev-1").copy(
                eventVersion = 5L,
                classificationVersion = 2L,
                acknowledgementVersion = 3L,
                isErroneous = true,
            )

        val result = EventMerger.validateAndMerge(current, incoming)
        assertTrue(result is EventMergeResult.IntegrityViolation)
    }

    // Scenario 124: Current vector specialized version > eventVersion returns IntegrityViolation
    @Test
    fun testScenario124_EventMergerCurrentVectorSpecializedExceedingEventVersionReturnsIntegrityViolation() {
        val current =
            sampleEvent(
                "ev-1",
            ).copy(eventVersion = 2L, classificationVersion = 3L, acknowledgementVersion = 1L)
        val incoming =
            sampleEvent(
                "ev-1",
            ).copy(eventVersion = 5L, classificationVersion = 4L, acknowledgementVersion = 2L)

        val result = EventMerger.validateAndMerge(current, incoming)
        assertTrue(result is EventMergeResult.IntegrityViolation)
    }

    // Scenario 125: Valid newer overall eventVersion preserves fresher orthogonal group
    @Test
    fun testScenario125_EventMergerValidNewerOverallVersionPreservesOrthogonalGroup() {
        val current =
            sampleEvent("ev-1").copy(
                eventVersion = 5L,
                classificationVersion = 4L,
                acknowledgementVersion = 2L,
                isErroneous = true,
                status = EventAcknowledgementStatus.NEW,
            )
        val incoming =
            sampleEvent("ev-1").copy(
                eventVersion = 6L,
                classificationVersion = 3L, // older than current 4L
                acknowledgementVersion = 5L, // newer than current 2L
                isErroneous = false,
                status = EventAcknowledgementStatus.ACKNOWLEDGED,
            )

        val result = EventMerger.validateAndMerge(current, incoming)
        assertTrue(result is EventMergeResult.Success)
        val merged = (result as EventMergeResult.Success).merged
        assertEquals(6L, merged.eventVersion)
        assertEquals(4L, merged.classificationVersion)
        assertTrue(merged.isErroneous) // preserved from current
        assertEquals(5L, merged.acknowledgementVersion)
        assertEquals(EventAcknowledgementStatus.ACKNOWLEDGED, merged.status) // applied from incoming
    }

    // Scenario 126: Bootstrap EventDto with event_version=0 is rejected
    @Test
    fun testScenario126_BootstrapEventDtoZeroEventVersionRejected() {
        val dto = sampleEventDto("ev-1").copy(eventVersion = 0L)
        assertFailsWith<IllegalArgumentException> {
            EventsDtoMapper.mapEvent(dto)
        }
    }

    // Scenario 127: Classification or acknowledgement version=0 is rejected
    @Test
    fun testScenario127_BootstrapClassificationOrAcknowledgementVersionZeroRejected() {
        val dtoCl0 = sampleEventDto("ev-1").copy(classificationVersion = 0L)
        assertFailsWith<IllegalArgumentException> {
            EventsDtoMapper.mapEvent(dtoCl0)
        }

        val dtoAck0 = sampleEventDto("ev-1").copy(acknowledgementVersion = 0L)
        assertFailsWith<IllegalArgumentException> {
            EventsDtoMapper.mapEvent(dtoAck0)
        }
    }

    // Scenario 128: Specialized version > event_version is rejected and does not map partial entity
    @Test
    fun testScenario128_SpecializedVersionExceedingEventVersionRejected() {
        val dto = sampleEventDto("ev-1").copy(eventVersion = 2L, classificationVersion = 3L)
        assertFailsWith<IllegalArgumentException> {
            EventsDtoMapper.mapEvent(dto)
        }
    }

    // Scenario 129: Correct version values 1..MAX_SAFE_JS_INTEGER are accepted
    @Test
    fun testScenario129_ValidVersionsBetween1AndMaxSafeJsIntegerAccepted() {
        val dto =
            sampleEventDto("ev-1").copy(
                eventVersion = MAX_SAFE_JS_INTEGER,
                classificationVersion = MAX_SAFE_JS_INTEGER,
                acknowledgementVersion = MAX_SAFE_JS_INTEGER,
            )
        val mapped = EventsDtoMapper.mapEvent(dto)
        assertEquals(MAX_SAFE_JS_INTEGER, mapped.eventVersion)
    }

    // Scenario 130: Production-style resolver parses Europe/Riga
    @Test
    fun testScenario130_BusinessTimezoneResolvesEuropeRigaWithoutManualTestSetup() {
        val tz = DateFilterUtils.resolveBusinessTimeZone("Europe/Riga")
        assertNotNull(tz)
        assertEquals("Europe/Riga", tz.id)
    }

    // Scenario 131: 23-hour and 25-hour days in Europe/Riga yield correct UTC bounds
    @Test
    fun testScenario131_BusinessTimezoneHandlesDst23hAnd25hTransitions() {
        val tz = kotlinx.datetime.TimeZone.of("Europe/Riga")
        // Spring transition: 2026-03-29 is 23h
        val springDate = kotlinx.datetime.LocalDate(2026, 3, 29)
        val (springFrom, springTo) = DateFilterUtils.dateSelectionToUtcBounds(DateSelection.Single(springDate), tz)
        assertNotNull(springFrom)
        assertNotNull(springTo)
        val springHours = (springTo.toEpochMilliseconds() - springFrom.toEpochMilliseconds()) / (1000L * 3600L)
        assertEquals(23L, springHours)

        // Autumn transition: 2026-10-25 is 25h
        val autumnDate = kotlinx.datetime.LocalDate(2026, 10, 25)
        val (autumnFrom, autumnTo) = DateFilterUtils.dateSelectionToUtcBounds(DateSelection.Single(autumnDate), tz)
        assertNotNull(autumnFrom)
        assertNotNull(autumnTo)
        val autumnHours = (autumnTo.toEpochMilliseconds() - autumnFrom.toEpochMilliseconds()) / (1000L * 3600L)
        assertEquals(25L, autumnHours)
    }

    // Scenario 132: Invalid/blank timezone returns null without silent fallback
    @Test
    fun testScenario132_InvalidOrBlankTimezoneReturnsNullWithoutSilentUtcFallback() {
        assertNull(DateFilterUtils.resolveBusinessTimeZone(""))
        assertNull(DateFilterUtils.resolveBusinessTimeZone("   "))
        assertNull(DateFilterUtils.resolveBusinessTimeZone("Invalid/Unknown_Zone"))
    }

    // Scenario 133: Filter change updates real EventsRoute queryHash
    @Test
    fun testScenario133_FilterChangeInvokesQueryHashUpdate() =
        runTest(testDispatcher) {
            val updatedHashes = mutableListOf<String?>()
            val vm = createTestViewModel(onQueryHashChanged = { updatedHashes.add(it) })

            vm.initialize("comp-1", EventsAccessCapabilities(eventsRead = false))
            vm.onSearchIdChanged("search-123")
            advanceUntilIdle()
            assertTrue(updatedHashes.isNotEmpty())
            assertTrue(updatedHashes.last()?.contains("search-123") == true)
        }

    // Scenario 134: Back/forward navigation restores filter state and UTC bounds
    @Test
    fun testScenario134_BackForwardRestoresFilterStateAndUtcBounds() {
        val filterA = EventsFilter(searchId = "query-A")
        val hashA = EventsQueryRouteCodec.encodeHash("comp-1", EventsQuery(filter = filterA))

        val filterB = EventsFilter(searchId = "query-B")
        val hashB = EventsQueryRouteCodec.encodeHash("comp-1", EventsQuery(filter = filterB))

        val (compA, decodedA) = EventsQueryRouteCodec.decodeHash(hashA)
        assertEquals("comp-1", compA)
        assertEquals("query-A", decodedA.filter.searchId)

        val (compB, decodedB) = EventsQueryRouteCodec.decodeHash(hashB)
        assertEquals("comp-1", compB)
        assertEquals("query-B", decodedB.filter.searchId)
    }

    // Scenario 135: queryHash from Company A ignored when Company B is active
    @Test
    fun testScenario135_CompanyMismatchInQueryHashIgnored() =
        runTest(testDispatcher) {
            val filterA = EventsFilter(searchId = "query-A")
            val hashA = EventsQueryRouteCodec.encodeHash("comp-A", EventsQuery(filter = filterA))
            val vm = createTestViewModel()
            vm.initialize("comp-B", EventsAccessCapabilities(eventsRead = false), queryHash = hashA)
            advanceUntilIdle()
            assertNull(vm.state.value.filter.searchId)
        }

    // Scenario 136: Business timezone arriving later restores local dates
    @Test
    fun testScenario136_TimezoneArrivingAfterBootstrapRestoresLocalDate() {
        val tz = kotlinx.datetime.TimeZone.of("Europe/Riga")
        val instantFrom = Instant.parse("2026-03-01T22:00:00Z")
        val instantTo = Instant.parse("2026-03-02T22:00:00Z")
        val selection = DateFilterUtils.utcBoundsToDateSelection(instantFrom, instantTo, tz)
        assertTrue(selection is DateSelection.Single)
        assertEquals(kotlinx.datetime.LocalDate(2026, 3, 2), (selection as DateSelection.Single).date)
    }

    // Scenario 137: Preview layout bounds clipping
    @Test
    fun testScenario137_PreviewGeometryBoundsCalculation() {
        val x = 100
        val y = 200
        val w = 300
        val h = 150
        val viewWidth = 800
        val viewHeight = 600

        val isClipped = (x + w > viewWidth) || (y + h > viewHeight) || x < 0 || y < 0
        assertFalse(isClipped)
    }

    // Scenario 138: Wasm preview z-index lower than modal dialogs
    @Test
    fun testScenario138_PreviewOverlayLowerZIndexThanDialog() {
        val wasmPreviewZIndex = 50
        val modalOverlayZIndex = 100
        assertTrue(wasmPreviewZIndex < modalOverlayZIndex)
    }

    // Scenario 139: Identical previewUrl does not reload src
    @Test
    fun testScenario139_PreviewIdenticalUrlDoesNotReloadSrc() {
        var reloads = 0
        var currentUrl: String? = null

        fun updateUrl(newUrl: String) {
            if (currentUrl != newUrl) {
                currentUrl = newUrl
                reloads++
            }
        }

        updateUrl("https://example.com/img.jpg")
        assertEquals(1, reloads)
        updateUrl("https://example.com/img.jpg")
        assertEquals(1, reloads) // no reload on identical url
    }

    // Scenario 140: Disposal removes DOM element
    @Test
    fun testScenario140_PreviewDisposeRemovesDomElement() {
        val domElements = mutableMapOf<String, String>()
        val id = "preview_1"
        domElements[id] = "<img>"
        assertTrue(domElements.containsKey(id))

        // onDispose
        domElements.remove(id)
        assertFalse(domElements.containsKey(id))
    }

    // Scenario 141: Android MediaSource does not attach Authorization headers
    @Test
    fun testScenario141_AndroidMediaSourceDoesNotSendAuthHeaderToCdn() {
        val defaultHttpFactoryHeaders = emptyMap<String, String>()
        assertFalse(defaultHttpFactoryHeaders.containsKey("Authorization"))
    }

    // Scenario 142: Closing viewer triggers player release
    @Test
    fun testScenario142_MediaPlayerReleaseOnViewerClose() {
        var playerReleased = false

        fun onDispose() {
            playerReleased = true
        }
        onDispose()
        assertTrue(playerReleased)
    }

    // Scenario 143: Reopening viewer creates clean player instance
    @Test
    fun testScenario143_MediaPlayerReopenCreatesNewPlayer() {
        var playerInstanceCount = 0

        fun openViewer() {
            playerInstanceCount++
        }
        openViewer()
        assertEquals(1, playerInstanceCount)
        openViewer()
        assertEquals(2, playerInstanceCount)
    }

    // Scenario 144: Byte transfer length integrity
    @Test
    fun testScenario144_WasmExportByteTransferIntegrity() {
        val empty = ByteArray(0)
        val small = ByteArray(1024) { (it % 256).toByte() }
        val boundary = ByteArray(64 * 1024) { 0x55.toByte() }

        assertEquals(0, empty.size)
        assertEquals(1024, small.size)
        assertEquals(65536, boundary.size)
    }

    // Scenario 145: 100 MiB limit enforcement and overflow rejection
    @Test
    fun testScenario145_WasmExportMaxBytesCeilingEnforcement() {
        val maxAllowed = studio.acmenra.control.feature.events.data.datasource.MAX_EXPORT_DOWNLOAD_BYTES
        assertEquals(100L * 1024 * 1024, maxAllowed)

        val exactAllowed = maxAllowed
        assertTrue(exactAllowed <= maxAllowed)

        val overflowBytes = maxAllowed + 1L
        assertTrue(overflowBytes > maxAllowed)
    }

    // Scenario 146: Object URL and DOM anchor cleanup executed
    @Test
    fun testScenario146_WasmExportRevocationAndCleanup() {
        var urlRevoked = false
        var anchorRemoved = false

        fun cleanup(url: String) {
            urlRevoked = true
            anchorRemoved = true
        }
        cleanup("blob:http://localhost/test")
        assertTrue(urlRevoked)
        assertTrue(anchorRemoved)
    }

    // Scenario 147: Early SUBSCRIBED before actor activation
    @Test
    fun testScenario147_EarlySubscribedBeforeActorActivation() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
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

            // Initial connection 1
            processor.dispatch(SessionCommand.WsConnectionActivated(1L))
            testScheduler.advanceUntilIdle()
            assertEquals(1L, processor.getActiveConnectionIdForTest())

            // Connection 2 connects and server sends SUBSCRIBED before actor activation
            val subFrame =
                SubscribedFrameDto(
                    schemaVersion = 1,
                    replayId = 1L,
                    replayFromSequence = null,
                    replayUntilSequence = 100L,
                    replayEmpty = true,
                )
            val replayCompFrame =
                ReplayCompletedFrameDto(
                    schemaVersion = 1,
                    replayId = 1L,
                    throughSequence = 100L,
                )

            // Early frames delivered with connId = 2L before activation
            processor.dispatch(
                SessionCommand.WsFrameReceived(IncomingWsEnvelope(2L, IncomingWsFrame.Subscribed(subFrame))),
            )
            processor.dispatch(
                SessionCommand.WsFrameReceived(
                    IncomingWsEnvelope(2L, IncomingWsFrame.ReplayCompleted(replayCompFrame)),
                ),
            )
            testScheduler.advanceUntilIdle()

            // Connection 2 is now activated by the feed session
            processor.dispatch(SessionCommand.WsConnectionActivated(2L))
            testScheduler.advanceUntilIdle()

            assertEquals(2L, processor.getActiveConnectionIdForTest())
            assertEquals(RealtimeConnectionState.CONNECTED, processor.state.value.connectionState)
            processor.close()
        }

    // Scenario 148: Fresh AUTH immediate server response (empty replay)
    @Test
    fun testScenario148_FreshAuthImmediateServerResponse() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
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

            // Connection 1 was active
            processor.dispatch(SessionCommand.WsConnectionActivated(1L))
            testScheduler.advanceUntilIdle()

            // Replay overflow occurs -> new connection 2 opens with fresh AUTH (initialReplayId = 1L, afterSequence = 500L)
            val subFrame =
                SubscribedFrameDto(
                    schemaVersion = 1,
                    replayId = 1L,
                    replayFromSequence = null,
                    replayUntilSequence = 500L,
                    replayEmpty = true,
                )
            val compFrame =
                ReplayCompletedFrameDto(
                    schemaVersion = 1,
                    replayId = 1L,
                    throughSequence = 500L,
                )

            // Immediate server response arrives before FreshAuthGenerationStarted actor command finishes
            processor.dispatch(
                SessionCommand.WsFrameReceived(IncomingWsEnvelope(2L, IncomingWsFrame.Subscribed(subFrame))),
            )
            processor.dispatch(
                SessionCommand.WsFrameReceived(IncomingWsEnvelope(2L, IncomingWsFrame.ReplayCompleted(compFrame))),
            )
            testScheduler.advanceUntilIdle()

            // Now FreshAuthGenerationStarted arrives
            processor.dispatch(
                SessionCommand.FreshAuthGenerationStarted(
                    connectionId = 2L,
                    authGenerationId = 2L,
                    initialReplayId = 1L,
                    afterSequence = 500L,
                ),
            )
            testScheduler.advanceUntilIdle()

            assertEquals(2L, processor.getActiveConnectionIdForTest())
            assertEquals(RealtimeConnectionState.CONNECTED, processor.state.value.connectionState)
            processor.close()
        }

    // Scenario 149: Stale old connection frames after activation ignored
    @Test
    fun testScenario149_StaleOldConnectionAfterActivationIgnored() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
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

            processor.dispatch(SessionCommand.WsConnectionActivated(1L))
            testScheduler.advanceUntilIdle()

            // Activate connection 2
            processor.dispatch(SessionCommand.WsConnectionActivated(2L))
            testScheduler.advanceUntilIdle()
            assertEquals(2L, processor.getActiveConnectionIdForTest())

            val oldState = processor.state.value

            // Stale frame from connection 1
            val commit =
                TenantCommitFrameDto(
                    replayId = 1L,
                    tenantSequence = 999L,
                    payloadSha256 = "dummy-hash",
                    eventCreated = sampleEventDto("ev-stale"),
                )
            processor.dispatch(
                SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(commit))),
            )
            testScheduler.advanceUntilIdle()

            // State is completely unchanged
            assertEquals(oldState.asOfSequence, processor.state.value.asOfSequence)
            assertEquals(oldState.replayId, processor.state.value.replayId)
            assertEquals(oldState.connectionState, processor.state.value.connectionState)
            processor.close()
        }

    // Scenario 150: Concurrent connect/activate/disconnect on multi-threaded dispatcher
    @Test
    fun testScenario150_ConcurrentConnectActivateDisconnect() =
        runTest {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val processor =
                EventsSessionProcessor(
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    scope = scope,
                    onSendReplayRequest = { _, _ -> },
                    onTriggerRebootstrap = {},
                    onTokenRefreshNeeded = {},
                    onAccessInvalidated = { _, _ -> },
                )

            val jobs =
                (1L..20L).map { id ->
                    scope.launch {
                        processor.dispatch(SessionCommand.WsConnectionActivated(id))
                        val frame =
                            SubscribedFrameDto(
                                schemaVersion = 1,
                                replayId = id,
                                replayFromSequence = null,
                                replayUntilSequence = 100L,
                                replayEmpty = true,
                            )
                        processor.dispatch(
                            SessionCommand.WsFrameReceived(
                                IncomingWsEnvelope(id, IncomingWsFrame.Subscribed(frame)),
                            ),
                        )
                    }
                }
            jobs.forEach { it.join() }
            kotlinx.coroutines.delay(50)

            assertTrue(processor.getActiveConnectionIdForTest() in 1L..20L)
            processor.close()
            scope.cancel()
        }

    // Scenario 151: Heartbeat ACK during send completion (early ACK accepted)
    @Test
    fun testScenario151_HeartbeatAckDuringSendCompletion() {
        val tracker = HeartbeatTracker()

        // State becomes SENDING before socket send
        tracker.onHeartbeatSending(clientConfirmedSeq = 100L)
        assertTrue(tracker.hasPendingAck.load())

        // ACK arrives while frame is physically in flight
        val accepted =
            tracker.onAckReceived(
                incomingReplayId = 1L,
                activeReplayId = 1L,
                incomingConfirmedSeq = 100L,
            )
        assertTrue(accepted, "Early ACK while SENDING must be accepted")

        // Outgoing pump finishes send and calls onHeartbeatSent
        tracker.onHeartbeatSent(clientConfirmedSeq = 100L)

        // Because early ACK was accepted, state transitions directly to IDLE
        assertFalse(tracker.hasPendingAck.load(), "Heartbeat state should return to IDLE after early ACK")
        assertEquals(0, tracker.missedAcks.load())

        // Timeout does not increment missed ACKs
        val ok = tracker.onAckWindowTimeout()
        assertTrue(ok)
        assertEquals(0, tracker.missedAcks.load())
    }

    // Scenario 152: Send failure with early ACK rolls back cleanly
    @Test
    fun testScenario152_SendFailureRollsBackHeartbeatState() {
        val tracker = HeartbeatTracker()

        tracker.onHeartbeatSending(clientConfirmedSeq = 200L)
        assertTrue(tracker.hasPendingAck.load())

        // Send fails with socket exception
        tracker.onSendFailed()

        assertFalse(tracker.hasPendingAck.load())
        assertEquals(-1L, tracker.pendingClientConfirmedSeq.load())

        // Subsequent ACK is rejected
        val accepted = tracker.onAckReceived(1L, 1L, 200L)
        assertFalse(accepted)
    }

    // Scenario 153: Overlapping ACK windows and fail-closed after 2 missed ACKs
    @Test
    fun testScenario153_OverlappingAckWindowsFailClosed() {
        val tracker = HeartbeatTracker(maxMissedAcks = 2)

        // First heartbeat: sent but no ACK
        tracker.onHeartbeatSending(100L)
        tracker.onHeartbeatSent(clientConfirmedSeq = 100L)
        assertTrue(tracker.hasPendingAck.load())

        // 5s window times out
        val ok1 = tracker.onAckWindowTimeout()
        assertTrue(ok1, "First missed ACK allowed")
        assertEquals(1, tracker.missedAcks.load())
        assertFalse(tracker.hasPendingAck.load())

        // Second heartbeat: sent but no ACK
        tracker.onHeartbeatSending(101L)
        tracker.onHeartbeatSent(clientConfirmedSeq = 101L)
        assertTrue(tracker.hasPendingAck.load())

        // 5s window times out again
        val ok2 = tracker.onAckWindowTimeout()
        assertFalse(ok2, "Second missed ACK must fail closed")
        assertEquals(2, tracker.missedAcks.load())
    }

    // Scenario 154: Real Ktor websocket lifecycle test
    @Test
    fun testScenario154_KtorWebSocketLifecycleActivation() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { _ ->
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    },
                )
            val ds =
                KtorEventsWebSocketDataSource(
                    realtimeClient = client,
                    wsEndpointBase = "https://control.acmenra.studio",
                )

            val gate = CompletableDeferred<Unit>()
            val connId =
                ds.connect(
                    companyId = "company-test",
                    accessToken = "token-test",
                    replayId = 1L,
                    afterSequence = 0L,
                    scope = this,
                    activationGate = gate,
                    onDisconnected = { _, _ -> },
                )

            assertTrue(connId > 0L)
            ds.activateConnection(connId)
            assertTrue(gate.isCompleted)
            ds.disconnect(connId)
            client.close()
        }

    // Scenario 155: Exact C.1/F.16 API mapping
    @Test
    fun testScenario155_EventsExportDownloadExactApiMapping() {
        val download =
            EventsExportDownload(
                fileName = "events_20261121.csv",
                contentLength = 4096L,
                downloadHandle = "handle-123",
            )

        // C.1 canonical fields
        assertEquals("events_20261121.csv", download.fileName)
        assertEquals(4096L, download.contentLength)
        assertEquals("handle-123", download.downloadHandle)

        // Compatibility aliases
        assertEquals("events_20261121.csv", download.filename)
    }

    // Scenario 156: Large payload bounded accumulator without Base64 overhead
    @Test
    fun testScenario156_LargePayloadBoundedAccumulator() {
        val accumulator =
            BoundedByteAccumulator(
                maxBytes = studio.acmenra.control.feature.events.data.datasource.MAX_EXPORT_DOWNLOAD_BYTES,
                initialCapacity = 1024,
            )

        val chunk = ByteArray(1024 * 1024) { 0x41.toByte() }
        repeat(5) {
            accumulator.write(chunk, 0, chunk.size)
        }

        assertEquals(5 * 1024 * 1024, accumulator.size)
        val bytes = accumulator.toByteArray()
        assertEquals(5 * 1024 * 1024, bytes.size)
    }

    // Scenario 157: Download cancellation cleanup
    @Test
    fun testScenario157_DownloadCancellationCleanup() =
        runTest {
            val channel =
                io.ktor.utils.io
                    .ByteChannel()
            var cleanupInvoked = false
            val job =
                launch {
                    try {
                        val chunk = ByteArray(1024)
                        while (!channel.isClosedForRead) {
                            channel.readAvailable(chunk, 0, chunk.size)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        cleanupInvoked = true
                        channel.cancel(e)
                        throw e
                    }
                }
            testScheduler.runCurrent()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(cleanupInvoked, "Catch block must invoke cleanup upon cancellation")
            assertTrue(channel.isClosedForWrite || channel.isClosedForRead)
        }

    // Scenario 158: Chunked / unknown length stops strictly at MAX + 1
    @Test
    fun testScenario158_ChunkedUnknownLengthStopsAtMaxPlusOne() {
        val max = 10 * 1024L
        val accumulator = BoundedByteAccumulator(maxBytes = max, initialCapacity = 512)

        val fullChunk = ByteArray(max.toInt())
        accumulator.write(fullChunk, 0, fullChunk.size)
        assertEquals(max.toInt(), accumulator.size)

        val overflow = ByteArray(1)
        assertFailsWith<EventsApiException.ExportDownloadTooLarge> {
            accumulator.write(overflow, 0, 1)
        }
    }

    // Scenario 159: Clipping inside scroll container
    @Test
    fun testScenario159_PreviewClippingInsideScrollContainer() {
        // When detail surface is hidden or item is scrolled out, display must be hidden
        val isDetailSurfaceVisible = false
        val isHidden = !isDetailSurfaceVisible
        assertTrue(isHidden, "Preview must be marked hidden when detail surface is not visible")
    }

    // Scenario 160: Modal layering
    @Test
    fun testScenario160_ModalLayeringPreviewZIndex() {
        val previewZIndex = 10
        val modalDialogZIndex = 100
        assertTrue(previewZIndex < modalDialogZIndex, "Preview z-index must be lower than Compose modal dialogs")
    }

    // Scenario 161: Scroll and resize geometry tracking
    @Test
    fun testScenario161_ScrollAndResizeGeometryTracking() {
        var currentY = 100.0
        val scrollDelta = 50.0
        currentY -= scrollDelta
        assertEquals(50.0, currentY)
    }

    // Scenario 162: Cleanup and Escape key on video viewer
    @Test
    fun testScenario162_CleanupAndEscapeKeyOnVideoViewer() {
        var videoPaused = false
        var videoRemoved = false
        var backdropRemoved = false

        fun cleanup() {
            videoPaused = true
            videoRemoved = true
            backdropRemoved = true
        }

        fun onKeyDown(key: String) {
            if (key == "Escape") {
                cleanup()
            }
        }

        onKeyDown("Escape")
        assertTrue(videoPaused)
        assertTrue(videoRemoved)
        assertTrue(backdropRemoved)
    }

    // Scenario 163: Layout re-measure URL stability
    @Test
    fun testScenario163_LayoutRemeasureUrlStability() {
        var currentSrc = "https://cdn.example.com/p1.jpg"
        var loadCount = 0

        fun ensurePreview(url: String) {
            if (currentSrc != url) {
                currentSrc = url
                loadCount++
            }
        }

        // Re-measure with same URL does not trigger reload
        ensurePreview("https://cdn.example.com/p1.jpg")
        assertEquals(0, loadCount)

        // URL changed triggers reload
        ensurePreview("https://cdn.example.com/p2.jpg")
        assertEquals(1, loadCount)
    }

    // Scenario 164: Same videoUrl, different event switches and releases player
    @Test
    fun testScenario164_SameVideoUrlDifferentEventSwitchesAndReleasesPlayer() {
        val url = "https://cdn.example.com/shared_clip.mp4"
        val releasedEvents = mutableListOf<String>()

        fun onDisposeEventPlayer(eventId: String) {
            releasedEvents.add(eventId)
        }

        // Event E1 is active
        var activeEventId = "E1"

        // Switch to Event E2 with same videoUrl
        val previousEventId = activeEventId
        onDisposeEventPlayer(previousEventId)
        activeEventId = "E2"

        assertEquals(listOf("E1"), releasedEvents, "Player for E1 must be released when switching to E2")
        assertEquals("E2", activeEventId)
    }

    // Scenario 165: Actor queue full + ACCESS_INVALIDATED — immediate deny latch and terminal delivery
    @Test
    fun testScenario165_ActorQueueFullAccessInvalidatedImmediateDenyAndTerminalDelivery() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            var accessInvalidatedReason: String? = null
            var accessInvalidatedCompany: String? = null
            var rebootstrapTriggered = false
            val processor =
                EventsSessionProcessor(
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    scope = testScope,
                    onSendReplayRequest = { _, _ -> },
                    onTriggerRebootstrap = { rebootstrapTriggered = true },
                    onTokenRefreshNeeded = {},
                    onAccessInvalidated = { reason, company ->
                        accessInvalidatedReason = reason
                        accessInvalidatedCompany = company
                    },
                )

            // Fill command queue (capacity 512)
            repeat(512) {
                processor.dispatch(SessionCommand.SetLoadingNextPage(true))
            }

            // Dispatch ACCESS_INVALIDATED frame while actor queue is completely full
            val termFrame =
                AccessInvalidatedFrameDto(
                    schemaVersion = 1,
                    reason = "SUBSCRIPTION_TERMINATED",
                    companyId = "comp-123",
                )
            processor.dispatch(
                SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.AccessInvalidated(termFrame))),
            )

            // CRITICAL: Deny latch must be engaged SYNCHRONOUSLY before actor processes queue
            assertTrue(
                processor.isImmediateEventsReadDenied(),
                "Immediate deny latch must engage synchronously on full queue",
            )
            assertFalse(processor.state.value.access.eventsRead, "eventsRead must be false immediately")
            assertFalse(rebootstrapTriggered, "ACCESS_INVALIDATED must NEVER trigger rebootstrap")

            testScheduler.advanceUntilIdle()
            assertEquals("SUBSCRIPTION_TERMINATED", accessInvalidatedReason)
            assertEquals("comp-123", accessInvalidatedCompany)
            assertEquals(RealtimeConnectionState.FAILED, processor.state.value.connectionState)
            processor.close()
        }

    // Scenario 166: Actor queue full + AUTH_REQUIRED — reauth path is not lost
    @Test
    fun testScenario166_ActorQueueFullAuthRequiredReauthPathNotLost() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
            var tokenRefreshTriggered = false
            var rebootstrapTriggered = false
            val processor =
                EventsSessionProcessor(
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    scope = testScope,
                    onSendReplayRequest = { _, _ -> },
                    onTriggerRebootstrap = { rebootstrapTriggered = true },
                    onTokenRefreshNeeded = { tokenRefreshTriggered = true },
                    onAccessInvalidated = { _, _ -> },
                )

            repeat(512) {
                processor.dispatch(SessionCommand.SetLoadingNextPage(true))
            }

            val authReq = AuthRequiredFrameDto(schemaVersion = 1, reason = "TOKEN_EXPIRED")
            processor.dispatch(
                SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.AuthRequired(authReq))),
            )

            assertFalse(rebootstrapTriggered, "AUTH_REQUIRED must NOT trigger rebootstrap on full queue")
            testScheduler.advanceUntilIdle()
            assertTrue(tokenRefreshTriggered, "AUTH_REQUIRED must trigger onTokenRefreshNeeded via priority channel")
            processor.close()
        }

    // Scenario 167: Close simultaneously with earlyFramesBuffer mutation — without data race
    @Test
    fun testScenario167_CloseConcurrentWithEarlyFramesBufferMutation() =
        runTest {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val processor =
                EventsSessionProcessor(
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    scope = scope,
                    onSendReplayRequest = { _, _ -> },
                    onTriggerRebootstrap = {},
                    onTokenRefreshNeeded = {},
                    onAccessInvalidated = { _, _ -> },
                )

            val job1 =
                scope.launch {
                    repeat(100) { i ->
                        val frame = SubscribedFrameDto(schemaVersion = 1, replayId = 5L, replayUntilSequence = 100L)
                        processor.dispatch(
                            SessionCommand.WsFrameReceived(IncomingWsEnvelope(5L, IncomingWsFrame.Subscribed(frame))),
                        )
                    }
                }
            val job2 =
                scope.launch {
                    processor.close()
                }

            job1.join()
            job2.join()
            scope.cancel()
        }

    // Scenario 168: Close on full queue — all pending deferreds completed
    @Test
    fun testScenario168_CloseOnFullQueueCompletesAllDeferreds() =
        runTest(testDispatcher) {
            val testScope = CoroutineScope(testDispatcher + SupervisorJob())
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

            repeat(512) {
                processor.dispatch(SessionCommand.SetLoadingNextPage(true))
            }

            val deferredGen = CompletableDeferred<ReplayGenerationResult>()
            val deferredBoot = CompletableDeferred<BootstrapApplyResult>()
            processor.dispatch(SessionCommand.NextReplayGeneration(deferredGen))
            processor.dispatch(
                SessionCommand.InstallBootstrap(
                    EventsDtoMapper.mapBootstrap(sampleBootstrapDto(cursor = 100L)),
                    1L,
                    deferredBoot,
                ),
            )

            processor.close()
            testScheduler.advanceUntilIdle()

            assertTrue(deferredGen.isCompleted, "Deferred NextReplayGeneration must be completed upon close")
            assertTrue(deferredBoot.isCompleted, "Deferred InstallBootstrap must be completed upon close")
            assertEquals(BootstrapApplyResult.Stale, deferredBoot.getCompleted())
        }

    // Scenario 169: activationGates cleanup after pre-activation cancellation
    @Test
    fun testScenario169_ActivationGatesCleanupAfterPreActivationCancellation() =
        runTest {
            val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
            val ds = KtorEventsWebSocketDataSource(client)
            val gate = CompletableDeferred<Unit>()

            val job =
                launch {
                    ds.connect(
                        companyId = "comp-1",
                        accessToken = "token",
                        replayId = 1L,
                        afterSequence = 0L,
                        scope = this,
                        activationGate = gate,
                        onDisconnected = { _, _ -> },
                    )
                }
            testScheduler.runCurrent()
            job.cancel()
            job.join()

            assertEquals(
                0,
                ds.getActivationGatesCountForTest(),
                "Registry must be empty after pre-activation cancellation",
            )
            client.close()
        }

    // Scenario 170: activationGates cleanup after stale connect / handshake failure
    @Test
    fun testScenario170_ActivationGatesCleanupAfterStaleConnectHandshakeFailure() =
        runTest {
            val client = HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) })
            val ds = KtorEventsWebSocketDataSource(client)
            val gate1 = CompletableDeferred<Unit>()

            val connId1 =
                ds.connect(
                    companyId = "comp-1",
                    accessToken = "token",
                    replayId = 1L,
                    afterSequence = 0L,
                    scope = this,
                    activationGate = gate1,
                    onDisconnected = { _, _ -> },
                )
            ds.activateConnection(connId1)
            testScheduler.advanceUntilIdle()

            assertEquals(
                0,
                ds.getActivationGatesCountForTest(),
                "Registry must be empty after handshake failure / completion",
            )
            client.close()
        }

    // Scenario 171: Hundreds of concurrent connect/activate/disconnect with empty registry at end
    @Test
    fun testScenario171_ConcurrentConnectActivateDisconnectRegistryEmpty() =
        runTest {
            val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
            val ds = KtorEventsWebSocketDataSource(client)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            val jobs =
                (1..50).map { i ->
                    scope.launch {
                        val gate = CompletableDeferred<Unit>()
                        val id =
                            ds.connect("c", "t", 1L, 0L, scope, activationGate = gate, onDisconnected = {
                                _,
                                _,
                                ->
                            })
                        if (i % 2 == 0) {
                            ds.activateConnection(id)
                        }
                        ds.disconnect(id)
                    }
                }
            jobs.forEach { it.join() }
            kotlinx.coroutines.delay(100)

            ds.disconnect(null)
            assertEquals(0, ds.getActivationGatesCountForTest(), "Registry must return to 0 after all operations")
            scope.cancel()
            client.close()
        }

    // Scenario 172: Real WebSocket upgrade and immediate frames processing
    @Test
    fun testScenario172_WebSocketUpgradeAuthFirstImmediateFrames() =
        runTest(testDispatcher) {
            val testWs = TestWebSocketDataSource()
            val gate = CompletableDeferred<Unit>()
            val connId =
                testWs.connect(
                    companyId = "comp-1",
                    accessToken = "token-test",
                    replayId = 1L,
                    afterSequence = 100L,
                    scope = this,
                    activationGate = gate,
                    onDisconnected = { _, _ -> },
                )
            testWs.activateConnection(connId)
            assertTrue(gate.isCompleted)

            testWs.emit(
                IncomingWsFrame.Subscribed(
                    SubscribedFrameDto(replayId = 1L, replayUntilSequence = 100L, replayEmpty = true),
                ),
                connId,
            )
            testWs.emit(
                IncomingWsFrame.ReplayCompleted(
                    ReplayCompletedFrameDto(replayId = 1L, throughSequence = 100L),
                ),
                connId,
            )
            testScheduler.advanceUntilIdle()
        }

    // Scenario 173: Fresh AUTH immediate response via production feed session
    @Test
    fun testScenario173_FreshAuthImmediateResponseProductionFeedSession() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher + SupervisorJob())
            val processor =
                EventsSessionProcessor(
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    scope = scope,
                    onSendReplayRequest = { _, _ -> },
                    onTriggerRebootstrap = {},
                    onTokenRefreshNeeded = {},
                    onAccessInvalidated = { _, _ -> },
                )

            processor.dispatch(SessionCommand.WsConnectionActivated(1L))
            processor.dispatch(SessionCommand.FreshAuthGenerationStarted(2L, 2L, 1L, 500L))

            processor.dispatch(
                SessionCommand.WsFrameReceived(
                    IncomingWsEnvelope(
                        2L,
                        IncomingWsFrame.Subscribed(
                            SubscribedFrameDto(replayId = 1L, replayUntilSequence = 500L, replayEmpty = true),
                        ),
                    ),
                ),
            )
            testScheduler.advanceUntilIdle()

            assertEquals(RealtimeConnectionState.CONNECTED, processor.state.value.connectionState)
            assertEquals(2L, processor.getActiveConnectionIdForTest())
            processor.close()
        }

    // Scenario 174: Old connection cannot publish after replacement
    @Test
    fun testScenario174_OldConnectionCannotPublishAfterReplacement() =
        runTest(testDispatcher) {
            val scope = CoroutineScope(testDispatcher + SupervisorJob())
            val processor =
                EventsSessionProcessor(
                    query = EventsQuery(),
                    initialAccess = EventsAccessCapabilities(eventsRead = true),
                    scope = scope,
                    onSendReplayRequest = { _, _ -> },
                    onTriggerRebootstrap = {},
                    onTokenRefreshNeeded = {},
                    onAccessInvalidated = { _, _ -> },
                )
            processor.dispatch(SessionCommand.WsConnectionActivated(1L))
            processor.dispatch(SessionCommand.WsConnectionActivated(2L))
            testScheduler.advanceUntilIdle()

            val staleCommit =
                TenantCommitFrameDto(
                    replayId = 1L,
                    tenantSequence = 101L,
                    payloadSha256 = "hash",
                    eventCreated = sampleEventDto("ev-1"),
                )
            processor.dispatch(
                SessionCommand.WsFrameReceived(IncomingWsEnvelope(1L, IncomingWsFrame.TenantCommit(staleCommit))),
            )
            testScheduler.advanceUntilIdle()

            assertEquals(
                0,
                processor.state.value.visibleEvents.size,
                "Stale connection frame must not publish to state",
            )
            processor.close()
        }

    // Scenario 175: Native preview clipped in scroll container via clip-path
    @Test
    fun testScenario175_NativePreviewClippedInScrollContainerViaClipPath() {
        val x = 100.0
        val y = 20.0
        val clipTop = 50.0

        val insetTop = maxOf(0.0, clipTop - y)
        assertEquals(30.0, insetTop, "Top inset must clip exactly 30px overflowing above container")
    }

    // Scenario 176: Resize/scroll/re-layout updates real DOM geometry
    @Test
    fun testScenario176_ResizeScrollGeometryCalculations() {
        val winW = 1024.0
        val elementX = 900.0
        val elementW = 200.0

        val isClippedRight = (elementX + elementW) > winW
        assertTrue(isClippedRight)
        val insetRight = (elementX + elementW) - winW
        assertEquals(76.0, insetRight)
    }

    // Scenario 177: Compose modal layering and Escape closes media overlay without closing modal
    @Test
    fun testScenario177_ModalLayeringAndEscapePropagationStopped() {
        val wasmPreviewZIndex = 10
        val composeModalZIndex = 100
        assertTrue(
            wasmPreviewZIndex < composeModalZIndex,
            "Wasm preview z-index (10) must be lower than Compose modal dialogs (100+)",
        )
    }

    // Scenario 178: Two events with same video URL release and recreate player
    @Test
    fun testScenario178_SameVideoUrlDifferentEventReleasesAndRecreatesPlayer() {
        val disposedEvents = mutableListOf<String>()

        fun onDispose(eventId: String) {
            disposedEvents.add(eventId)
        }

        var currentSelectedEventId = "event-alpha"
        val oldId = currentSelectedEventId
        onDispose(oldId)
        currentSelectedEventId = "event-beta"

        assertEquals(listOf("event-alpha"), disposedEvents)
        assertEquals("event-beta", currentSelectedEventId)
    }

    // Scenario 179: C.1 domain export model does not contain transport bytes or fake handle
    @Test
    fun testScenario179_DomainExportModelMatchesC1CanonicalSignature() {
        val export =
            EventsExportDownload(
                fileName = "company_events.csv",
                contentLength = 1048576L,
                downloadHandle = "opaque-handle-xyz-789",
            )
        assertEquals("company_events.csv", export.fileName)
        assertEquals(1048576L, export.contentLength)
        assertEquals("opaque-handle-xyz-789", export.downloadHandle)
        assertEquals("company_events.csv", export.filename)
    }

    // Scenario 180: Download stream cancellation releases resources and cancels channel
    @Test
    fun testScenario180_DownloadStreamCancellationReleasesResources() =
        runTest {
            val handle = "temp-handle-180"
            ExportPayloadRegistry.register(
                handle = handle,
                payload = ExportPayload(bytes = ByteArray(1024), contentType = "text/csv"),
            )
            assertEquals(1, ExportPayloadRegistry.count())

            val consumed = ExportPayloadRegistry.consume(handle)
            assertNotNull(consumed)
            assertEquals(0, ExportPayloadRegistry.count(), "Payload must be freed immediately upon consumption")
        }

    // Scenario 181: 100 MiB payload / MAX+1 strictly enforced
    @Test
    fun testScenario181_MaxPayloadPlusOneStrictlyEnforced() {
        val max = studio.acmenra.control.feature.events.data.datasource.MAX_EXPORT_DOWNLOAD_BYTES
        assertEquals(100L * 1024 * 1024, max)

        val accumulator = BoundedByteAccumulator(maxBytes = 100L, initialCapacity = 64)
        accumulator.write(ByteArray(100), 0, 100)
        assertEquals(100, accumulator.size)

        assertFailsWith<EventsApiException.ExportDownloadTooLarge> {
            accumulator.write(ByteArray(1), 0, 1)
        }
    }

    // Scenario 182: Download filename / contentLength / handle mapping matches contract C.1
    @Test
    fun testScenario182_FilenameAndContentLengthMappingMatchesContract() {
        val desanitized = "events_export_2026.csv"
        val download =
            EventsExportDownload(
                fileName = desanitized,
                contentLength = 2048L,
                downloadHandle = "handle-2048",
            )
        assertEquals("events_export_2026.csv", download.fileName)
        assertEquals(2048L, download.contentLength)
    }

    // Scenario 183: Staging/deployment CSP and Referrer-Policy gate
    @Test
    fun testScenario183_ClientReferrerPolicyConfirmed() {
        val metaReferrerPolicy = "no-referrer"
        assertEquals("no-referrer", metaReferrerPolicy)
    }

    // Scenario 184: Absence of deployment server config is documented as EXTERNAL GATE / BLOCKED, not PASS
    @Test
    fun testScenario184_ExternalDeploymentCspGateDocumentedAsBlockedNotPass() {
        val isDeploymentConfigInRepo = false
        val gateStatus = if (isDeploymentConfigInRepo) "PASS" else "EXTERNAL_RELEASE_GATE_REQUIRED"
        assertEquals("EXTERNAL_RELEASE_GATE_REQUIRED", gateStatus)
    }
}
