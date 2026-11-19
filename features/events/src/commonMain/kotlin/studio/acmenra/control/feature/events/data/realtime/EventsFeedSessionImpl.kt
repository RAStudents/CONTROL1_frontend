package studio.acmenra.control.feature.events.data.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.acmenra.control.core.network.auth.AccessInvalidationListener
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.feature.events.data.datasource.EventsApiException
import studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.mapper.EventsDtoMapper
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsInitialLoadError
import studio.acmenra.control.feature.events.domain.model.EventsPaginationError
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.RealtimeConnectionState
import studio.acmenra.control.feature.events.domain.repository.EventsConnectivityProvider
import studio.acmenra.control.feature.events.domain.repository.EventsFeedSession
import studio.acmenra.control.feature.events.domain.repository.EventsFeedState
import kotlin.random.Random

sealed interface RebootstrapState {
    data object Idle : RebootstrapState

    data class Loading(
        val attempt: Int,
    ) : RebootstrapState

    data class RetryWaiting(
        val attempt: Int,
        val delayMs: Long,
    ) : RebootstrapState

    data class Failed(
        val error: Throwable,
    ) : RebootstrapState
}

class EventsFeedSessionImpl(
    override val companyId: String,
    override val query: EventsQuery,
    initialAccess: EventsAccessCapabilities,
    private val remoteDataSource: EventsRemoteDataSource,
    private val webSocketDataSource: EventsWebSocketDataSource,
    private val sessionManager: SessionManager,
    private val connectivityProvider: EventsConnectivityProvider,
    private val accessInvalidationListener: AccessInvalidationListener? = null,
    parentScope: CoroutineScope? = null,
) : EventsFeedSession {
    private val sessionScope =
        parentScope?.let { CoroutineScope(SupervisorJob(it.coroutineContext[Job]) + Dispatchers.Default) }
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var connectJob: Job? = null
    private var wsFramesJob: Job? = null
    private var connectivityJob: Job? = null
    private var rebootstrapJob: Job? = null
    private var rebootstrapState: RebootstrapState = RebootstrapState.Idle
    private var lastUsedAccessToken: String? = null
    private var isClosed = false
    private var isOnline = true

    private val processor =
        EventsSessionProcessor(
            query = query,
            initialAccess = initialAccess,
            scope = sessionScope,
            onSendReplayRequest = { replayId, afterSequence ->
                webSocketDataSource.sendReplayRequest(replayId, afterSequence)
            },
            onTriggerRebootstrap = {
                triggerRebootstrap()
            },
            onTokenRefreshNeeded = {
                handleTokenRefreshNeeded()
            },
            onAccessInvalidated = { reason, targetCompanyId ->
                sessionScope.launch {
                    accessInvalidationListener?.onAccessInvalidated(reason, targetCompanyId ?: companyId)
                }
            },
        )

    override val state: StateFlow<EventsFeedState> = processor.state

    init {
        startSession()
    }

    private fun startSession() {
        listenToWsFrames()
        listenToConnectivity()
        triggerBootstrap()
    }

    private fun listenToWsFrames() {
        wsFramesJob?.cancel()
        wsFramesJob =
            sessionScope.launch {
                webSocketDataSource.incomingFrames.collect { frame ->
                    processor.dispatch(SessionCommand.WsFrameReceived(frame))
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
        sessionScope.launch {
            try {
                val bootstrapDto = remoteDataSource.getBootstrap(companyId, query, limit = 50)
                val bootstrap = EventsDtoMapper.mapBootstrap(bootstrapDto)
                processor.dispatch(SessionCommand.BootstrapLoaded(bootstrap))

                // Start WebSocket generation with replay_id = 1
                processor.startReplay(replayId = 1L, afterSequence = bootstrap.streamCursor)
                connectWebSocket(bootstrap.streamCursor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EventsApiException.SnapshotTooLarge) {
                val err =
                    EventsInitialLoadError.SnapshotTooLarge(
                        maxItems = e.maxItems,
                        suggestedFrom =
                            e.suggestedFrom?.let {
                                runCatching {
                                    kotlin.time.Instant.parse(
                                        it,
                                    )
                                }.getOrNull()
                            },
                        suggestedTo =
                            e.suggestedTo?.let {
                                runCatching {
                                    kotlin.time.Instant.parse(
                                        it,
                                    )
                                }.getOrNull()
                            },
                    )
                processor.dispatch(SessionCommand.InitialLoadFailed(err))
            } catch (e: EventsApiException.FilterInvalid) {
                processor.dispatch(
                    SessionCommand.InitialLoadFailed(EventsInitialLoadError.InvalidFilter(e.invalidFields)),
                )
            } catch (e: Throwable) {
                val err =
                    if (e.message?.contains("403") == true || e.message?.contains("AccessDenied") == true) {
                        EventsInitialLoadError.AccessDenied
                    } else if (e.message?.contains("Network") == true || !isOnline) {
                        EventsInitialLoadError.Network
                    } else {
                        EventsInitialLoadError.Server(e.message)
                    }
                processor.dispatch(SessionCommand.InitialLoadFailed(err))
            }
        }
    }

    private fun triggerRebootstrap() {
        if (rebootstrapJob?.isActive == true) return

        rebootstrapJob =
            sessionScope.launch {
                var attempt = 1
                val maxAttempts = 4
                while (isActive && attempt <= maxAttempts && !isClosed) {
                    rebootstrapState = RebootstrapState.Loading(attempt)
                    try {
                        val bootstrapDto = remoteDataSource.getBootstrap(companyId, query, limit = 50)
                        val bootstrap = EventsDtoMapper.mapBootstrap(bootstrapDto)
                        rebootstrapState = RebootstrapState.Idle
                        processor.dispatch(SessionCommand.BootstrapLoaded(bootstrap))
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        attempt++
                        if (attempt > maxAttempts) {
                            rebootstrapState = RebootstrapState.Failed(e)
                            processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.FAILED))
                            return@launch
                        }
                        val backoffMs = (1L shl (attempt - 1)) * 500L + Random.nextLong(50, 200)
                        rebootstrapState = RebootstrapState.RetryWaiting(attempt, backoffMs)
                        delay(backoffMs)
                    }
                }
            }
    }

    private fun connectWebSocket(afterSequence: Long) {
        connectJob?.cancel()
        connectJob =
            sessionScope.launch {
                if (isClosed) return@launch

                val snapshot = sessionManager.getSnapshot()
                val token = snapshot?.tokens?.accessToken
                if (token.isNullOrBlank()) {
                    processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.FAILED))
                    return@launch
                }
                lastUsedAccessToken = token

                processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.CONNECTING))
                val replayId = processor.currentReplayId

                webSocketDataSource.connect(
                    companyId = companyId,
                    accessToken = token,
                    replayId = replayId,
                    afterSequence = afterSequence,
                    scope = sessionScope,
                    getClientConfirmedSeq = { processor.getAsOfSequence() },
                    onDisconnected = { reason ->
                        if (!isClosed) {
                            handleDisconnect(reason)
                        }
                    },
                )
            }
    }

    private fun handleDisconnect(reason: String?) {
        if (!isOnline) {
            processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.DISCONNECTED))
            return
        }
        reconnectWithBackoff(resetAttempt = false)
    }

    private var reconnectAttempt = 0

    private fun reconnectWithBackoff(resetAttempt: Boolean) {
        if (resetAttempt) reconnectAttempt = 0
        connectJob?.cancel()
        connectJob =
            sessionScope.launch {
                if (isClosed || !isOnline) return@launch

                processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.RECONNECTING))

                reconnectAttempt++
                val baseDelay = (1L shl (reconnectAttempt.coerceAtMost(5) - 1)) * 1000L
                val jitter = Random.nextLong(100, 500)
                delay((baseDelay + jitter).coerceAtMost(30_000L))

                if (!isActive || isClosed) return@launch
                val currentSeq = processor.getAsOfSequence()
                connectWebSocket(currentSeq)
            }
    }

    private fun handleTokenRefreshNeeded() {
        sessionScope.launch {
            try {
                var snapshot = sessionManager.getSnapshot()
                if (snapshot?.tokens?.accessToken == lastUsedAccessToken) {
                    try {
                        remoteDataSource.getFilterOptions(companyId, ifNoneMatchEtag = null)
                    } catch (e: Throwable) {
                        // ignore, will verify snapshot next
                    }
                    snapshot = sessionManager.getSnapshot()
                }

                val newToken = snapshot?.tokens?.accessToken
                if (!newToken.isNullOrBlank() && newToken != lastUsedAccessToken) {
                    lastUsedAccessToken = newToken
                    processor.nextReplayGeneration()
                    connectWebSocket(processor.getAsOfSequence())
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Refresh failure falls through to FAILED connection state
            }
            processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.FAILED))
        }
    }

    override fun loadNextPage() {
        val currentState = state.value
        if (currentState.isLoadingNextPage || !currentState.hasMore) return
        val cursor = processor.getNextCursor() ?: return

        processor.dispatch(SessionCommand.SetLoadingNextPage(true))
        sessionScope.launch {
            try {
                val pageDto = remoteDataSource.getEventsPage(companyId, cursor, limit = 50)
                val page = EventsDtoMapper.mapPage(pageDto)
                processor.dispatch(SessionCommand.NextPageLoaded(page))
            } catch (e: CancellationException) {
                throw e
            } catch (e: EventsApiException.SnapshotExpired) {
                processor.dispatch(SessionCommand.PaginationFailed(EventsPaginationError.SnapshotExpired))
                triggerRebootstrap()
            } catch (e: Throwable) {
                val err =
                    if (e.message?.contains("Network") == true || !isOnline) {
                        EventsPaginationError.Network
                    } else {
                        EventsPaginationError.Server(e.message)
                    }
                processor.dispatch(SessionCommand.PaginationFailed(err))
            }
        }
    }

    override fun refresh() {
        triggerBootstrap()
    }

    override fun onViewportAtTop() {
        processor.dispatch(SessionCommand.ViewportAtTop)
    }

    override fun markOptimisticAckPending(eventId: String) {
        processor.dispatch(SessionCommand.OptimisticAckPending(eventId))
    }

    override fun clearOptimisticAckPending(eventId: String) {
        processor.dispatch(SessionCommand.ClearOptimisticAckPending(eventId))
    }

    override fun updateAccessCapabilities(access: EventsAccessCapabilities) {
        processor.dispatch(SessionCommand.AccessUpdated(access))
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        processor.dispatch(SessionCommand.Close)
        connectJob?.cancel()
        wsFramesJob?.cancel()
        connectivityJob?.cancel()
        rebootstrapJob?.cancel()
        sessionScope.launch {
            webSocketDataSource.disconnect()
        }
        sessionScope.cancel()
    }
}
