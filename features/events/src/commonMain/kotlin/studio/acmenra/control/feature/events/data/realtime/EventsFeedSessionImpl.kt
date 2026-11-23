package studio.acmenra.control.feature.events.data.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.acmenra.control.core.network.auth.AccessInvalidationListener
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.client.isConnectivityError
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_connection_failed_3_attempts
import studio.acmenra.control.core.ui.generated.resources.events_connection_failed_title
import studio.acmenra.control.core.ui.generated.resources.events_http_failed_3_attempts
import studio.acmenra.control.core.ui.generated.resources.events_http_failed_title
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.data.datasource.EventsApiException
import studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.WsCloseReason
import studio.acmenra.control.feature.events.data.mapper.EventsDtoMapper
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsInitialLoadError
import studio.acmenra.control.feature.events.domain.model.EventsPaginationError
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.RealtimeConnectionState
import studio.acmenra.control.feature.events.domain.repository.EventsConnectivityProvider
import studio.acmenra.control.feature.events.domain.repository.EventsFeedSession
import studio.acmenra.control.feature.events.domain.repository.EventsFeedState
import studio.acmenra.control.feature.events.domain.repository.PendingNewerFlushResult
import studio.acmenra.control.feature.events.domain.repository.RebootstrapState
import kotlin.random.Random

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class EventsFeedSessionImpl(
    override val companyId: String,
    override val query: EventsQuery,
    initialAccess: EventsAccessCapabilities,
    private val remoteDataSource: EventsRemoteDataSource,
    private val webSocketDataSource: EventsWebSocketDataSource,
    private val sessionManager: SessionManager,
    private val connectivityProvider: EventsConnectivityProvider,
    private val accessInvalidationListener: AccessInvalidationListener? = null,
    private val onFilterOptionsInvalidated: (() -> Unit)? = null,
    parentScope: CoroutineScope? = null,
) : EventsFeedSession {
    private val sessionJob = SupervisorJob(parentScope?.coroutineContext?.get(Job))
    private val sessionScope =
        if (parentScope != null) {
            CoroutineScope(parentScope.coroutineContext + sessionJob)
        } else {
            CoroutineScope(sessionJob + Dispatchers.Default)
        }

    private var connectJob: Job? = null
    private var wsFramesJob: Job? = null
    private var connectivityJob: Job? = null
    private var connectionStateJob: Job? = null
    private var bootstrapJob: Job? = null
    private var rebootstrapJob: Job? = null

    private val connectionGeneration = kotlin.concurrent.atomics.AtomicLong(0L)
    private val authGenerationTracker = kotlin.concurrent.atomics.AtomicLong(1L)
    private var bootstrapGeneration: Long = 0L
    private val bootstrapGenMutex = Mutex()

    private suspend fun nextBootstrapGeneration(): Long = bootstrapGenMutex.withLock { ++bootstrapGeneration }

    private var lastUsedAccessToken: String? = null
    private val isTerminal = kotlin.concurrent.atomics.AtomicBoolean(false)
    private val isCleanupStarted = kotlin.concurrent.atomics.AtomicBoolean(false)
    val isClosed: Boolean get() = isTerminal.load() || isCleanupStarted.load()
    private var isOnline = true

    @kotlin.concurrent.Volatile
    private var currentAccess: EventsAccessCapabilities = initialAccess

    private fun canReadEvents(): Boolean = !isClosed && currentAccess.eventsRead && state.value.access.eventsRead

    private var accessTransitionGeneration = 0L
    private var accessCapabilitiesGeneration = 0L
    private var disconnectJob: Job? = null
    private var tokenRefreshJob: Job? = null

    private var consecutiveWsFailures = 0
    private var consecutiveHttpFailures = 0

    private val _connectionNotice = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
    override val connectionNotice: Flow<Notice> = _connectionNotice.asSharedFlow()

    private val _filterOptionsInvalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override val filterOptionsInvalidated: Flow<Unit> = _filterOptionsInvalidated.asSharedFlow()

    internal val processor =
        EventsSessionProcessor(
            query = query,
            initialAccess = initialAccess,
            companyId = companyId,
            scope = sessionScope,
            onSendReplayRequest = { replayId, afterSequence ->
                sessionScope.launch {
                    try {
                        webSocketDataSource.sendReplayRequest(replayId, afterSequence)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        triggerRebootstrap()
                    }
                }
            },
            onTriggerRebootstrap = {
                triggerRebootstrap()
            },
            onTokenRefreshNeeded = {
                handleTokenRefreshNeeded()
            },
            onSessionRevoked = {
                handleSessionRevoked()
            },
            onAccessInvalidated = { reason, targetCompanyId ->
                handleTerminalAccessInvalidated(reason, targetCompanyId)
            },
            onEventsReadRevoked = {},
            onEventsReadGranted = {
                handleEventsReadGranted()
            },
            onFilterOptionsUpdated = {
                onFilterOptionsInvalidated?.invoke()
                _filterOptionsInvalidated.tryEmit(Unit)
            },
            onFreshAuthNeeded = { afterSeq, replayId ->
                startFreshAuthSession(afterSequence = afterSeq, initialReplayId = replayId)
            },
        )

    override val state: StateFlow<EventsFeedState> = processor.state

    init {
        startSession()
    }

    private fun startSession() {
        listenToWsFrames()
        listenToConnectivity()
        listenToConnectionState()
        if (canReadEvents()) {
            triggerBootstrap()
        }
    }

    private fun listenToConnectionState() {
        connectionStateJob?.cancel()
        connectionStateJob =
            sessionScope.launch {
                processor.state.collect { feedState ->
                    if (feedState.connectionState == RealtimeConnectionState.CONNECTED) {
                        consecutiveWsFailures = 0
                    }
                }
            }
    }

    private fun listenToWsFrames() {
        wsFramesJob?.cancel()
        wsFramesJob =
            sessionScope.launch {
                webSocketDataSource.incomingFrames.collect { envelope ->
                    processor.dispatch(SessionCommand.WsFrameReceived(envelope))
                }
            }
    }

    private fun listenToConnectivity() {
        connectivityJob?.cancel()
        connectivityJob =
            sessionScope.launch {
                connectivityProvider.isOnline.collect { online ->
                    isOnline = online
                    if (!online) {
                        processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.DISCONNECTED))
                    } else {
                        if (!canReadEvents()) return@collect
                        disconnectJob?.join()
                        if (!canReadEvents() || !online) return@collect
                        if (state.value.rebootstrapState is RebootstrapState.Failed) {
                            triggerRebootstrap()
                        }
                        val currentConn = state.value.connectionState
                        if (currentConn == RealtimeConnectionState.DISCONNECTED ||
                            currentConn == RealtimeConnectionState.FAILED
                        ) {
                            reconnectWithBackoff(resetAttempt = true)
                        }
                    }
                }
            }
    }

    private fun triggerBootstrap() {
        if (!canReadEvents()) return
        bootstrapJob?.cancel()

        bootstrapJob =
            sessionScope.launch {
                var attempt = 1
                val maxAttempts = 4
                while (isActive && attempt <= maxAttempts && canReadEvents()) {
                    val generation = nextBootstrapGeneration()
                    try {
                        if (!canReadEvents()) return@launch
                        val bootstrapDto = remoteDataSource.getBootstrap(companyId, query, limit = 50)
                        if (!canReadEvents()) return@launch
                        val bootstrap = EventsDtoMapper.mapBootstrap(bootstrapDto)
                        consecutiveHttpFailures = 0
                        val applyResult = processor.installBootstrap(bootstrap, generation)
                        when (applyResult) {
                            is BootstrapApplyResult.Applied -> {
                                if (canReadEvents()) {
                                    connectWebSocket(
                                        afterSequence = processor.getAsOfSequence(),
                                        replayId = applyResult.replayId,
                                    )
                                }
                                return@launch
                            }
                            is BootstrapApplyResult.RequiresFreshAuth -> {
                                if (canReadEvents()) {
                                    startFreshAuthSession(
                                        afterSequence = applyResult.afterSequence,
                                        initialReplayId = applyResult.initialReplayId,
                                    )
                                }
                                return@launch
                            }
                            is BootstrapApplyResult.RetryRequired -> {
                                attempt++
                                if (attempt > maxAttempts) {
                                    processor.dispatch(
                                        SessionCommand.InitialLoadFailed(
                                            EventsInitialLoadError.Server("Bootstrap retry attempts exhausted"),
                                            generation = generation,
                                        ),
                                    )
                                    return@launch
                                }
                                val backoffMs = (1L shl (attempt - 1)) * 500L + Random.nextLong(50, 200)
                                delay(backoffMs)
                            }
                            is BootstrapApplyResult.Stale -> {
                                return@launch
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: EventsApiException.PermissionDenied) {
                        processor.dispatch(
                            SessionCommand.InitialLoadFailed(
                                EventsInitialLoadError.AccessDenied,
                                generation = generation,
                            ),
                        )
                        handleEventsReadRevoked()
                        return@launch
                    } catch (e: EventsApiException.SnapshotTooLarge) {
                        val err =
                            EventsInitialLoadError.SnapshotTooLarge(
                                maxItems = e.maxItems,
                                suggestedFrom =
                                    e.suggestedFrom?.let {
                                        runCatching {
                                            kotlin.time.Instant.parse(it)
                                        }.getOrNull()
                                    },
                                suggestedTo =
                                    e.suggestedTo?.let {
                                        runCatching {
                                            kotlin.time.Instant.parse(it)
                                        }.getOrNull()
                                    },
                            )
                        processor.dispatch(SessionCommand.InitialLoadFailed(err, generation = generation))
                        return@launch
                    } catch (e: EventsApiException.FilterInvalid) {
                        processor.dispatch(
                            SessionCommand.InitialLoadFailed(
                                EventsInitialLoadError.InvalidFilter(e.invalidFields),
                                generation = generation,
                            ),
                        )
                        return@launch
                    } catch (e: Throwable) {
                        consecutiveHttpFailures++
                        checkHttpFailuresNotice()
                        val err =
                            if (!isOnline) {
                                EventsInitialLoadError.Network
                            } else {
                                EventsInitialLoadError.Server(e.message)
                            }
                        processor.dispatch(SessionCommand.InitialLoadFailed(err, generation = generation))
                        return@launch
                    }
                }
            }
    }

    private fun triggerRebootstrap() {
        if (!canReadEvents() || rebootstrapJob?.isActive == true) return

        rebootstrapJob =
            sessionScope.launch {
                var attempt = 1
                val maxAttempts = 4
                while (isActive && attempt <= maxAttempts && canReadEvents()) {
                    val currentAttempt = attempt
                    processor.dispatch(SessionCommand.RebootstrapStateChanged(RebootstrapState.Loading(currentAttempt)))
                    try {
                        if (!canReadEvents()) return@launch
                        val bootstrapDto = remoteDataSource.getBootstrap(companyId, query, limit = 50)
                        if (!canReadEvents()) return@launch
                        val bootstrap = EventsDtoMapper.mapBootstrap(bootstrapDto)
                        consecutiveHttpFailures = 0
                        val generation = nextBootstrapGeneration()
                        val applyResult = processor.installBootstrap(bootstrap, generation)
                        when (applyResult) {
                            is BootstrapApplyResult.Applied -> {
                                processor.dispatch(SessionCommand.RebootstrapStateChanged(RebootstrapState.Idle))
                                if (canReadEvents()) {
                                    connectWebSocket(
                                        afterSequence = processor.getAsOfSequence(),
                                        replayId = applyResult.replayId,
                                    )
                                }
                                return@launch
                            }
                            is BootstrapApplyResult.RequiresFreshAuth -> {
                                processor.dispatch(SessionCommand.RebootstrapStateChanged(RebootstrapState.Idle))
                                if (canReadEvents()) {
                                    startFreshAuthSession(
                                        afterSequence = applyResult.afterSequence,
                                        initialReplayId = applyResult.initialReplayId,
                                    )
                                }
                                return@launch
                            }
                            is BootstrapApplyResult.RetryRequired -> {
                                attempt++
                                if (attempt > maxAttempts) {
                                    processor.dispatch(
                                        SessionCommand.RebootstrapStateChanged(
                                            RebootstrapState.Failed(IllegalStateException("Rebootstrap buffer gap")),
                                        ),
                                    )
                                    processor.dispatch(
                                        SessionCommand.ConnectionStateChanged(RealtimeConnectionState.FAILED),
                                    )
                                    return@launch
                                }
                                val backoffMs = (1L shl (attempt - 1)) * 500L + Random.nextLong(50, 200)
                                processor.dispatch(
                                    SessionCommand.RebootstrapStateChanged(
                                        RebootstrapState.RetryWaiting(attempt, backoffMs),
                                    ),
                                )
                                delay(backoffMs)
                            }
                            is BootstrapApplyResult.Stale -> {
                                processor.dispatch(SessionCommand.RebootstrapStateChanged(RebootstrapState.Idle))
                                return@launch
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: EventsApiException.PermissionDenied) {
                        processor.dispatch(SessionCommand.RebootstrapStateChanged(RebootstrapState.Idle))
                        handleEventsReadRevoked()
                        return@launch
                    } catch (e: EventsApiException.SnapshotTooLarge) {
                        processor.dispatch(SessionCommand.RebootstrapStateChanged(RebootstrapState.Failed(e)))
                        return@launch
                    } catch (e: EventsApiException.FilterInvalid) {
                        processor.dispatch(SessionCommand.RebootstrapStateChanged(RebootstrapState.Failed(e)))
                        return@launch
                    } catch (e: Throwable) {
                        consecutiveHttpFailures++
                        checkHttpFailuresNotice()
                        attempt++
                        if (attempt > maxAttempts) {
                            processor.dispatch(SessionCommand.RebootstrapStateChanged(RebootstrapState.Failed(e)))
                            processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.FAILED))
                            return@launch
                        }
                        val backoffMs = (1L shl (attempt - 1)) * 500L + Random.nextLong(50, 200)
                        processor.dispatch(
                            SessionCommand.RebootstrapStateChanged(RebootstrapState.RetryWaiting(attempt, backoffMs)),
                        )
                        delay(backoffMs)
                    }
                }
            }
    }

    override fun retryRebootstrap() {
        if (canReadEvents()) {
            triggerRebootstrap()
        }
    }

    private suspend fun openWebSocketConnection(
        generation: Long,
        afterSequence: Long,
        replayId: Long,
    ) {
        disconnectJob?.join()
        if (!canReadEvents() || generation != connectionGeneration.load()) return

        val snapshot = sessionManager.getSnapshot()
        val token = snapshot?.tokens?.accessToken
        if (token.isNullOrBlank()) {
            processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.FAILED))
            return
        }
        lastUsedAccessToken = token

        processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.CONNECTING))

        val activationGate = CompletableDeferred<Unit>()
        val connectionId =
            webSocketDataSource.connect(
                companyId = companyId,
                accessToken = token,
                replayId = replayId,
                afterSequence = afterSequence,
                scope = sessionScope,
                getClientConfirmedSeq = { processor.getAsOfSequence() },
                activationGate = activationGate,
                onDisconnected = { reason, _ ->
                    if (!isClosed && generation == connectionGeneration.load() && canReadEvents()) {
                        handleDisconnect(reason)
                    }
                },
            )
        if (!canReadEvents() || generation != connectionGeneration.load()) {
            activationGate.cancel()
            webSocketDataSource.disconnect(connectionId)
            return
        }
        processor.dispatch(SessionCommand.WsConnectionActivated(connectionId))
        activationGate.complete(Unit)
        webSocketDataSource.activateConnection(connectionId)
    }

    private fun startFreshAuthSession(
        afterSequence: Long,
        initialReplayId: Long = 1L,
    ) {
        connectJob?.cancel()
        if (!canReadEvents()) return
        val authGen = authGenerationTracker.addAndFetch(1L)
        val generation = connectionGeneration.addAndFetch(1L)

        connectJob =
            sessionScope.launch {
                disconnectJob?.join()
                if (!canReadEvents() || generation != connectionGeneration.load()) return@launch

                val snapshot = sessionManager.getSnapshot()
                val token = snapshot?.tokens?.accessToken
                if (token.isNullOrBlank()) {
                    processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.FAILED))
                    return@launch
                }
                lastUsedAccessToken = token

                processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.CONNECTING))

                val activationGate = CompletableDeferred<Unit>()
                val connectionId =
                    webSocketDataSource.connect(
                        companyId = companyId,
                        accessToken = token,
                        replayId = initialReplayId,
                        afterSequence = afterSequence,
                        scope = sessionScope,
                        getClientConfirmedSeq = { processor.getAsOfSequence() },
                        activationGate = activationGate,
                        onDisconnected = { reason, _ ->
                            if (!isClosed && generation == connectionGeneration.load() && canReadEvents()) {
                                handleDisconnect(reason)
                            }
                        },
                    )
                if (!canReadEvents() || generation != connectionGeneration.load()) {
                    activationGate.cancel()
                    webSocketDataSource.disconnect(connectionId)
                    return@launch
                }
                processor.dispatch(
                    SessionCommand.FreshAuthGenerationStarted(
                        authGenerationId = authGen,
                        connectionId = connectionId,
                        initialReplayId = initialReplayId,
                        afterSequence = afterSequence,
                    ),
                )
                activationGate.complete(Unit)
                webSocketDataSource.activateConnection(connectionId)
            }
    }

    private fun connectWebSocket(
        afterSequence: Long,
        replayId: Long,
    ) {
        connectJob?.cancel()
        if (!canReadEvents()) return
        val generation = connectionGeneration.addAndFetch(1L)

        connectJob =
            sessionScope.launch {
                openWebSocketConnection(
                    generation = generation,
                    afterSequence = afterSequence,
                    replayId = replayId,
                )
            }
    }

    private fun handleDisconnect(reason: WsCloseReason) {
        if (!canReadEvents()) return
        if (reason == WsCloseReason.REPLACED || reason == WsCloseReason.SESSION_CLOSED) {
            // Replaced by a newer connection or closed intentionally: do NOT reconnect
            return
        }
        consecutiveWsFailures++
        if (consecutiveWsFailures >= 3) {
            _connectionNotice.tryEmit(
                Notice.error(
                    title = Res.string.events_connection_failed_title,
                    message = Res.string.events_connection_failed_3_attempts,
                ),
            )
        }
        if (!isOnline) {
            processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.DISCONNECTED))
            return
        }
        reconnectWithBackoff(resetAttempt = false)
    }

    private fun checkHttpFailuresNotice() {
        if (consecutiveHttpFailures >= 3) {
            _connectionNotice.tryEmit(
                Notice.error(
                    title = Res.string.events_http_failed_title,
                    message = Res.string.events_http_failed_3_attempts,
                ),
            )
        }
    }

    private var reconnectAttempt = 0

    private fun reconnectWithBackoff(resetAttempt: Boolean) {
        if (!canReadEvents()) return
        if (resetAttempt) reconnectAttempt = 0
        connectJob?.cancel()
        val generation = connectionGeneration.addAndFetch(1L)
        connectJob =
            sessionScope.launch {
                if (!canReadEvents() || !isOnline) return@launch

                processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.RECONNECTING))

                reconnectAttempt++
                val baseDelay = (1L shl (reconnectAttempt.coerceAtMost(5) - 1)) * 1000L
                val jitter = Random.nextLong(100, 500)
                delay((baseDelay + jitter).coerceAtMost(30_000L))

                if (!isActive ||
                    !canReadEvents() ||
                    !isOnline ||
                    generation != connectionGeneration.load()
                ) {
                    return@launch
                }
                val currentSeq = processor.getAsOfSequence()
                val currentReplayId = processor.currentReplayId
                openWebSocketConnection(
                    generation = generation,
                    afterSequence = currentSeq,
                    replayId = currentReplayId,
                )
            }
    }

    private fun handleTokenRefreshNeeded() {
        if (!canReadEvents()) return
        tokenRefreshJob?.cancel()
        val generation = connectionGeneration.load()
        tokenRefreshJob =
            sessionScope.launch {
                try {
                    val refreshed = sessionManager.refreshActiveSession(failedAccessToken = lastUsedAccessToken)
                    if (!canReadEvents() || generation != connectionGeneration.load()) return@launch
                    val newToken = refreshed?.tokens?.accessToken
                    if (!newToken.isNullOrBlank() && newToken != lastUsedAccessToken) {
                        lastUsedAccessToken = newToken
                        when (val replayRes = processor.requestNextReplayGeneration()) {
                            is ReplayGenerationResult.Next -> {
                                if (!canReadEvents() || generation != connectionGeneration.load()) return@launch
                                connectWebSocket(
                                    afterSequence = processor.getAsOfSequence(),
                                    replayId = replayRes.replayId,
                                )
                                return@launch
                            }
                            is ReplayGenerationResult.RequiresFreshAuth -> {
                                // Handled cleanly via onFreshAuthNeeded callback: avoids duplicate connect
                                return@launch
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Refresh failure falls through to FAILED
                }
                if (canReadEvents() && generation == connectionGeneration.load()) {
                    processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.FAILED))
                }
            }
    }

    private var loadNextPageJob: Job? = null

    private fun handleTerminalAccessInvalidated(
        reason: String,
        targetCompanyId: String?,
    ) {
        performCleanup {
            accessInvalidationListener?.onAccessInvalidated(reason, targetCompanyId ?: companyId)
        }
    }

    private fun handleSessionRevoked() {
        performCleanup {
            sessionManager.logout()
            accessInvalidationListener?.onAccessInvalidated("SESSION_REVOKED", null)
        }
    }

    private fun handleEventsReadRevoked() {
        val gen = ++accessTransitionGeneration
        connectionGeneration.addAndFetch(1L)
        reconnectAttempt = 0
        bootstrapJob?.cancel()
        rebootstrapJob?.cancel()
        loadNextPageJob?.cancel()
        connectJob?.cancel()
        tokenRefreshJob?.cancel()
        disconnectJob?.cancel()
        disconnectJob =
            sessionScope.launch {
                try {
                    webSocketDataSource.disconnect()
                } catch (_: CancellationException) {
                }
            }
    }

    private fun handleEventsReadGranted() {
        val gen = ++accessTransitionGeneration
        bootstrapJob?.cancel()
        rebootstrapJob?.cancel()
        connectJob?.cancel()
        sessionScope.launch {
            disconnectJob?.join()
            if (gen != accessTransitionGeneration || !isActive) return@launch
            triggerBootstrap()
        }
    }

    override fun loadNextPage() {
        if (!canReadEvents()) return
        val currentState = state.value
        if (currentState.isLoadingNextPage || !currentState.hasMore || isClosed) return
        val cursor = processor.getNextCursor() ?: return
        val currentSnapshotId = currentState.snapshotId

        processor.dispatch(SessionCommand.SetLoadingNextPage(true))
        loadNextPageJob?.cancel()
        loadNextPageJob =
            sessionScope.launch {
                try {
                    if (!canReadEvents()) return@launch
                    val pageDto = remoteDataSource.getEventsPage(companyId, cursor, limit = 50)
                    if (!canReadEvents()) return@launch
                    val page = EventsDtoMapper.mapPage(pageDto)
                    consecutiveHttpFailures = 0
                    processor.dispatch(SessionCommand.NextPageLoaded(page, snapshotId = currentSnapshotId))
                } catch (e: CancellationException) {
                    processor.dispatch(SessionCommand.SetLoadingNextPage(false))
                    throw e
                } catch (e: EventsApiException.PermissionDenied) {
                    processor.dispatch(SessionCommand.SetLoadingNextPage(false))
                    handleEventsReadRevoked()
                    return@launch
                } catch (e: EventsApiException.SnapshotExpired) {
                    processor.dispatch(SessionCommand.PaginationFailed(EventsPaginationError.SnapshotExpired))
                    if (canReadEvents()) {
                        triggerRebootstrap()
                    }
                } catch (e: Throwable) {
                    consecutiveHttpFailures++
                    checkHttpFailuresNotice()
                    val err =
                        if (isConnectivityError(e) || !isOnline) {
                            EventsPaginationError.Network
                        } else {
                            EventsPaginationError.Server(e.message)
                        }
                    processor.dispatch(SessionCommand.PaginationFailed(err))
                }
            }
    }

    override fun refresh() {
        if (canReadEvents()) {
            triggerBootstrap()
        }
    }

    override fun onViewportAtTop() {
        processor.dispatch(SessionCommand.ViewportAtTop())
    }

    override suspend fun flushPendingNewer(expectedEventIds: List<String>): PendingNewerFlushResult {
        val response = CompletableDeferred<PendingNewerFlushResult>()
        processor.dispatch(SessionCommand.ViewportAtTop(expectedEventIds, response))
        return response.await()
    }

    override suspend fun acknowledgeDeltasThrough(batchId: Long) {
        val response = CompletableDeferred<Unit>()
        processor.dispatch(SessionCommand.AcknowledgeFeedDeltas(batchId, response))
        response.await()
    }

    override fun markOptimisticAckPending(eventId: String) {
        processor.dispatch(SessionCommand.OptimisticAckPending(eventId))
    }

    override fun clearOptimisticAckPending(eventId: String) {
        processor.dispatch(SessionCommand.ClearOptimisticAckPending(eventId))
    }

    override fun applyAcknowledged(event: Event) {
        processor.dispatch(SessionCommand.AcknowledgeSucceeded(event))
    }

    override fun applyEventMutationResult(event: Event) {
        processor.applyEventMutationResult(event)
    }

    override fun updateAccessCapabilities(access: EventsAccessCapabilities) {
        val wasRead = currentAccess.eventsRead
        currentAccess = access
        val gen = ++accessCapabilitiesGeneration
        if (wasRead && !access.eventsRead) {
            processor.setImmediateEventsReadDenied(true, gen)
            handleEventsReadRevoked()
        }
        processor.dispatch(SessionCommand.AccessUpdated(access, gen))
    }

    private fun performCleanup(postAction: (suspend () -> Unit)? = null) {
        if (!isCleanupStarted.compareAndSet(false, true)) return
        isTerminal.store(true)

        val gen = ++accessTransitionGeneration
        disconnectJob?.cancel()

        processor.close()
        connectJob?.cancel()
        tokenRefreshJob?.cancel()
        wsFramesJob?.cancel()
        connectivityJob?.cancel()
        connectionStateJob?.cancel()
        bootstrapJob?.cancel()
        rebootstrapJob?.cancel()
        loadNextPageJob?.cancel()

        sessionScope.launch {
            withContext(NonCancellable) {
                try {
                    webSocketDataSource.disconnect()
                    postAction?.invoke()
                } finally {
                    sessionJob.cancel()
                }
            }
        }
    }

    override fun close() {
        performCleanup()
    }
}
