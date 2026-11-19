package studio.acmenra.control.feature.events.data.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.acmenra.control.feature.events.data.datasource.IncomingWsFrame
import studio.acmenra.control.feature.events.data.mapper.EventsDtoMapper
import studio.acmenra.control.feature.events.data.model.TenantCommitFrameDto
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsBootstrap
import studio.acmenra.control.feature.events.domain.model.EventsInitialLoadError
import studio.acmenra.control.feature.events.domain.model.EventsPage
import studio.acmenra.control.feature.events.domain.model.EventsPaginationError
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.OverlayMembership
import studio.acmenra.control.feature.events.domain.model.RealtimeConnectionState
import studio.acmenra.control.feature.events.domain.model.RealtimeOverlay
import studio.acmenra.control.feature.events.domain.repository.EventsFeedState

sealed interface SessionCommand {
    data class BootstrapLoaded(
        val bootstrap: EventsBootstrap,
    ) : SessionCommand

    data class NextPageLoaded(
        val page: EventsPage,
    ) : SessionCommand

    data class InitialLoadFailed(
        val error: EventsInitialLoadError,
    ) : SessionCommand

    data class PaginationFailed(
        val error: EventsPaginationError,
    ) : SessionCommand

    data class SetLoadingNextPage(
        val isLoading: Boolean,
    ) : SessionCommand

    data class WsFrameReceived(
        val frame: IncomingWsFrame,
    ) : SessionCommand

    data class ConnectionStateChanged(
        val state: RealtimeConnectionState,
    ) : SessionCommand

    data class OptimisticAckPending(
        val eventId: String,
    ) : SessionCommand

    data class ClearOptimisticAckPending(
        val eventId: String,
    ) : SessionCommand

    data class AccessUpdated(
        val access: EventsAccessCapabilities,
    ) : SessionCommand

    data object ViewportAtTop : SessionCommand

    data object Close : SessionCommand
}

class EventsSessionProcessor(
    private val query: EventsQuery,
    initialAccess: EventsAccessCapabilities,
    private val scope: CoroutineScope,
    private val onSendReplayRequest: suspend (replayId: Long, afterSequence: Long) -> Unit,
    private val onTriggerRebootstrap: suspend () -> Unit,
    private val onTokenRefreshNeeded: suspend () -> Unit,
    private val onAccessInvalidated: (companyId: String?) -> Unit,
) {
    private val _state = MutableStateFlow(EventsFeedState(access = initialAccess))
    val state: StateFlow<EventsFeedState> = _state.asStateFlow()

    private val commandChannel = Channel<SessionCommand>(Channel.UNLIMITED)
    private val sequenceProcessor = TenantSequenceProcessor()
    private val snapshotItems = mutableListOf<Event>()
    private var currentOverlay = RealtimeOverlay()

    private var isRebootstrapping = false
    private val rebootstrapBuffer = mutableListOf<TenantCommitFrameDto>()
    private var nextCursor: String? = null

    init {
        scope.launch {
            for (command in commandChannel) {
                if (!isActive) break
                processCommand(command)
                if (command is SessionCommand.Close) break
            }
        }
    }

    fun dispatch(command: SessionCommand) {
        commandChannel.trySend(command)
    }

    private suspend fun processCommand(command: SessionCommand) {
        when (command) {
            is SessionCommand.BootstrapLoaded -> handleBootstrap(command.bootstrap)
            is SessionCommand.NextPageLoaded -> handleNextPage(command.page)
            is SessionCommand.InitialLoadFailed -> {
                _state.update {
                    it.copy(
                        isInitialLoading = false,
                        initialLoadError = command.error,
                    )
                }
            }
            is SessionCommand.PaginationFailed -> {
                _state.update {
                    it.copy(
                        isLoadingNextPage = false,
                        paginationError = command.error,
                    )
                }
            }
            is SessionCommand.SetLoadingNextPage -> {
                _state.update { it.copy(isLoadingNextPage = command.isLoading) }
            }
            is SessionCommand.WsFrameReceived -> handleWsFrame(command.frame)
            is SessionCommand.ConnectionStateChanged -> {
                _state.update { it.copy(connectionState = command.state) }
            }
            is SessionCommand.OptimisticAckPending -> {
                val newPending = currentOverlay.pendingAckIds + command.eventId
                currentOverlay = currentOverlay.copy(pendingAckIds = newPending)
                _state.update { it.copy(realtimeOverlay = currentOverlay) }
            }
            is SessionCommand.ClearOptimisticAckPending -> {
                val newPending = currentOverlay.pendingAckIds - command.eventId
                currentOverlay = currentOverlay.copy(pendingAckIds = newPending)
                _state.update { it.copy(realtimeOverlay = currentOverlay) }
            }
            is SessionCommand.AccessUpdated -> {
                _state.update { it.copy(access = command.access) }
            }
            is SessionCommand.ViewportAtTop -> {
                // User scrolled to top, reset pending newer count
                _state.update { it.copy(pendingNewerCount = 0) }
            }
            is SessionCommand.Close -> {
                commandChannel.close()
            }
        }
    }

    private suspend fun handleBootstrap(bootstrap: EventsBootstrap) {
        snapshotItems.clear()
        snapshotItems.addAll(bootstrap.page.items)
        currentOverlay = RealtimeOverlay(pendingAckIds = currentOverlay.pendingAckIds)
        sequenceProcessor.resetSequence(bootstrap.streamCursor)
        nextCursor = bootstrap.page.nextCursor

        // If we were buffering commits during rebootstrap, apply them now
        if (isRebootstrapping) {
            isRebootstrapping = false
            val commitsToApply = rebootstrapBuffer.filter { it.tenantSequence > bootstrap.streamCursor }
            rebootstrapBuffer.clear()
            for (commit in commitsToApply.sortedBy { it.tenantSequence }) {
                applyCommit(commit)
            }
            val newReplayId = sequenceProcessor.nextReplayId()
            onSendReplayRequest(newReplayId, sequenceProcessor.currentSequence)
        }

        val visible = RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
        _state.update {
            it.copy(
                visibleEvents = visible,
                realtimeOverlay = currentOverlay,
                asOfSequence = bootstrap.page.asOfSequence,
                snapshotTotalCount = bootstrap.page.totalCount,
                hasMore = bootstrap.page.hasMore,
                isInitialLoading = false,
                initialLoadError = null,
                summary = bootstrap.summary,
            )
        }
    }

    private fun handleNextPage(page: EventsPage) {
        val existingIds = snapshotItems.map { it.id }.toSet()
        val newItems = page.items.filter { it.id !in existingIds }
        snapshotItems.addAll(newItems)
        nextCursor = page.nextCursor

        val visible = RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
        _state.update {
            it.copy(
                visibleEvents = visible,
                hasMore = page.hasMore,
                isLoadingNextPage = false,
                paginationError = null,
            )
        }
    }

    private suspend fun handleWsFrame(frame: IncomingWsFrame) {
        when (frame) {
            is IncomingWsFrame.TenantCommit -> {
                val commit = frame.frame
                if (isRebootstrapping) {
                    if (rebootstrapBuffer.size < 10_000) {
                        rebootstrapBuffer.add(commit)
                    } else {
                        // Buffer exceeded -> restart rebootstrap safely
                        rebootstrapBuffer.clear()
                        onTriggerRebootstrap()
                    }
                    return
                }

                val validation = sequenceProcessor.checkIncomingSequence(commit.replayId, commit.tenantSequence)
                when (validation) {
                    SequenceValidationResult.InOrder -> {
                        applyCommit(commit)
                    }
                    SequenceValidationResult.Duplicate -> {
                        // Ignore duplicate frame
                    }
                    is SequenceValidationResult.GapDetected -> {
                        onSendReplayRequest(sequenceProcessor.currentReplayId, sequenceProcessor.currentSequence)
                    }
                    is SequenceValidationResult.InvalidSequence -> {
                        // Protocol or replay integrity error -> trigger rebootstrap
                        isRebootstrapping = true
                        rebootstrapBuffer.clear()
                        onTriggerRebootstrap()
                    }
                }
            }
            is IncomingWsFrame.ResyncRequired -> {
                isRebootstrapping = true
                rebootstrapBuffer.clear()
                onTriggerRebootstrap()
            }
            is IncomingWsFrame.Subscribed -> {
                _state.update { it.copy(connectionState = RealtimeConnectionState.CONNECTED) }
            }
            is IncomingWsFrame.ReplayCompleted -> {
                _state.update { it.copy(connectionState = RealtimeConnectionState.CONNECTED) }
            }
            is IncomingWsFrame.HeartbeatAck -> {
                // Heartbeat liveness confirmed
            }
            is IncomingWsFrame.AccessCheckUnavailable -> {
                // Transient: do not invalidate cache
            }
            is IncomingWsFrame.AuthRequired -> {
                if (frame.frame.reason == "TOKEN_EXPIRED") {
                    onTokenRefreshNeeded()
                } else {
                    _state.update { it.copy(connectionState = RealtimeConnectionState.FAILED) }
                }
            }
            is IncomingWsFrame.AccessInvalidated -> {
                onAccessInvalidated(frame.frame.companyId)
                _state.update {
                    it.copy(
                        connectionState = RealtimeConnectionState.FAILED,
                        visibleEvents = emptyList(),
                        realtimeOverlay = RealtimeOverlay(),
                    )
                }
            }
        }
    }

    private fun applyCommit(commit: TenantCommitFrameDto) {
        var overlayChanged = false
        var updatedSummary = _state.value.summary
        var pendingNewer = _state.value.pendingNewerCount
        var pendingHistory = _state.value.pendingHistoryCount

        val createdDto = commit.eventCreated
        val updatedDto = commit.eventUpdated

        if (createdDto != null) {
            val event = EventsDtoMapper.mapEvent(createdDto)
            val (newOverlay, membership) =
                RealtimeOverlayReducer.applyEventCommit(
                    currentOverlay = currentOverlay,
                    event = event,
                    query = query,
                    snapshotItems = snapshotItems,
                    isCreated = true,
                )
            currentOverlay = newOverlay
            overlayChanged = true
            if (membership == OverlayMembership.PENDING_NEWER) pendingNewer++
            if (membership == OverlayMembership.PENDING_HISTORY) pendingHistory++
        }

        if (updatedDto != null) {
            val event = EventsDtoMapper.mapEvent(updatedDto)
            val (newOverlay, membership) =
                RealtimeOverlayReducer.applyEventCommit(
                    currentOverlay = currentOverlay,
                    event = event,
                    query = query,
                    snapshotItems = snapshotItems,
                    isCreated = false,
                )
            currentOverlay = newOverlay
            overlayChanged = true
            if (membership == OverlayMembership.PENDING_HISTORY) pendingHistory++
        }

        commit.summaryUpdated?.let {
            updatedSummary = EventsDtoMapper.mapSummary(it)
        }

        sequenceProcessor.advanceSequence(commit.tenantSequence)

        val visible =
            if (overlayChanged) {
                RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
            } else {
                _state.value.visibleEvents
            }

        _state.update {
            it.copy(
                visibleEvents = visible,
                realtimeOverlay = currentOverlay,
                summary = updatedSummary,
                pendingNewerCount = pendingNewer,
                pendingHistoryCount = pendingHistory,
            )
        }
    }

    fun getNextCursor(): String? = nextCursor

    fun getAsOfSequence(): Long = sequenceProcessor.currentSequence
}
