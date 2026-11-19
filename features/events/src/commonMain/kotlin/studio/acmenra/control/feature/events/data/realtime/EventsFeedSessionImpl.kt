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
import studio.acmenra.control.feature.events.domain.repository.RebootstrapState
import kotlin.random.Random

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
    private val sessionScope =
        parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var connectJob: Job? = null
    private var wsFramesJob: Job? = null
    private var connectivityJob: Job? = null
    private var bootstrapJob: Job? = null
    private var rebootstrapJob: Job? = null

    private var connectionGeneration: Long = 0L
    private var bootstrapGeneration: Long = 0L

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
                handleTerminalAccessInvalidated(reason, targetCompanyId)
            },
            onFilterOptionsUpdated = {
                onFilterOptionsInvalidated?.invoke()
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
        if (isClosed) return
        bootstrapJob?.cancel()
        val generation = ++bootstrapGeneration

        bootstrapJob =
            sessionScope.launch {
                try {
                    val bootstrapDto = remoteDataSource.getBootstrap(companyId, query, limit = 50)
                    val bootstrap = EventsDtoMapper.mapBootstrap(bootstrapDto)
                    processor.dispatch(SessionCommand.BootstrapLoaded(bootstrap, generation = generation))

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
                } catch (e: EventsApiException.FilterInvalid) {
                    processor.dispatch(
                        SessionCommand.InitialLoadFailed(
                            EventsInitialLoadError.InvalidFilter(e.invalidFields),
                            generation = generation,
                        ),
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
                    processor.dispatch(SessionCommand.InitialLoadFailed(err, generation = generation))
                }
            }
    }

    private fun triggerRebootstrap() {
        if (rebootstrapJob?.isActive == true || isClosed) return

        rebootstrapJob =
            sessionScope.launch {
                var attempt = 1
                val maxAttempts = 4
                while (isActive && attempt <= maxAttempts && !isClosed) {
                    val currentAttempt = attempt
                    processor.dispatch(SessionCommand.RebootstrapStateChanged(RebootstrapState.Loading(currentAttempt)))
                    try {
                        val bootstrapDto = remoteDataSource.getBootstrap(companyId, query, limit = 50)
                        val bootstrap = EventsDtoMapper.mapBootstrap(bootstrapDto)
                        processor.dispatch(SessionCommand.RebootstrapStateChanged(RebootstrapState.Idle))
                        processor.dispatch(
                            SessionCommand.BootstrapLoaded(bootstrap, generation = ++bootstrapGeneration),
                        )
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
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
        if (!isClosed) {
            triggerRebootstrap()
        }
    }

    private fun connectWebSocket(afterSequence: Long) {
        connectJob?.cancel()
        val generation = ++connectionGeneration

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
                    onDisconnected = { reason, _ ->
                        if (!isClosed && generation == connectionGeneration) {
                            handleDisconnect(reason)
                        }
                    },
                )
            }
    }

    private fun handleDisconnect(reason: WsCloseReason) {
        if (reason == WsCloseReason.REPLACED || reason == WsCloseReason.SESSION_CLOSED) {
            // Replaced by a newer connection or closed intentionally: do NOT reconnect
            return
        }
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
                val refreshed = sessionManager.refreshActiveSession(failedAccessToken = lastUsedAccessToken)
                val newToken = refreshed?.tokens?.accessToken
                if (!newToken.isNullOrBlank() && newToken != lastUsedAccessToken) {
                    lastUsedAccessToken = newToken
                    val nextReplay = processor.nextReplayGeneration()
                    connectWebSocket(processor.getAsOfSequence())
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Refresh failure falls through to FAILED
            }
            processor.dispatch(SessionCommand.ConnectionStateChanged(RealtimeConnectionState.FAILED))
        }
    }

    private fun handleTerminalAccessInvalidated(
        reason: String,
        targetCompanyId: String?,
    ) {
        isClosed = true
        connectJob?.cancel()
        wsFramesJob?.cancel()
        connectivityJob?.cancel()
        bootstrapJob?.cancel()
        rebootstrapJob?.cancel()
        sessionScope.launch {
            webSocketDataSource.disconnect()
            accessInvalidationListener?.onAccessInvalidated(reason, targetCompanyId ?: companyId)
        }
    }

    override fun loadNextPage() {
        val currentState = state.value
        if (currentState.isLoadingNextPage || !currentState.hasMore || isClosed) return
        val cursor = processor.getNextCursor() ?: return
        val currentSnapshotId = currentState.snapshotId

        processor.dispatch(SessionCommand.SetLoadingNextPage(true))
        sessionScope.launch {
            try {
                val pageDto = remoteDataSource.getEventsPage(companyId, cursor, limit = 50)
                val page = EventsDtoMapper.mapPage(pageDto)
                processor.dispatch(SessionCommand.NextPageLoaded(page, snapshotId = currentSnapshotId))
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
        if (!isClosed) {
            triggerBootstrap()
        }
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

    override fun applyAcknowledged(event: Event) {
        processor.dispatch(SessionCommand.AcknowledgeSucceeded(event))
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
        bootstrapJob?.cancel()
        rebootstrapJob?.cancel()
        sessionScope.launch {
            webSocketDataSource.disconnect()
        }
        sessionScope.cancel()
    }
}
