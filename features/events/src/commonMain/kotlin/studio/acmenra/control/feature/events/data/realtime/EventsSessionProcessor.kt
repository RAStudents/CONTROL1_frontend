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
import studio.acmenra.control.feature.events.domain.model.EventsSummary
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

private data class BufferedCommit(
    val commit: TenantCommitFrameDto,
    val receivedAtMs: Long,
)

class EventsSessionProcessor(
    private val query: EventsQuery,
    initialAccess: EventsAccessCapabilities,
    private val scope: CoroutineScope,
    private val onSendReplayRequest: suspend (replayId: Long, afterSequence: Long) -> Unit,
    private val onTriggerRebootstrap: suspend () -> Unit,
    private val onTokenRefreshNeeded: suspend () -> Unit,
    private val onAccessInvalidated: (reason: String, companyId: String?) -> Unit,
    private val currentTimeProvider: () -> Long = {
        kotlin.time.Clock.System
            .now()
            .toEpochMilliseconds()
    },
) {
    private val _state = MutableStateFlow(EventsFeedState(access = initialAccess))
    val state: StateFlow<EventsFeedState> = _state.asStateFlow()

    private val commandChannel = Channel<SessionCommand>(Channel.UNLIMITED)
    private val sequenceProcessor = TenantSequenceProcessor()
    private val snapshotItems = mutableListOf<Event>()
    private var currentOverlay = RealtimeOverlay()

    private var isRebootstrapping = false
    private val rebootstrapBuffer = mutableListOf<BufferedCommit>()
    private val closedReplayGenerations = mutableSetOf<Long>()
    private var nextCursor: String? = null

    val currentReplayId: Long get() = sequenceProcessor.currentReplayId

    init {
        scope.launch {
            for (command in commandChannel) {
                if (!isActive) break
                try {
                    processCommand(command)
                } catch (e: Throwable) {
                    if (command is SessionCommand.Close) break
                    // Prevent uncaught exceptions from killing the command processing loop
                    isRebootstrapping = true
                    rebootstrapBuffer.clear()
                    onTriggerRebootstrap()
                }
                if (command is SessionCommand.Close) break
            }
        }
    }

    fun dispatch(command: SessionCommand) {
        commandChannel.trySend(command)
    }

    fun startReplay(
        replayId: Long,
        afterSequence: Long,
    ) {
        sequenceProcessor.startReplay(replayId, afterSequence)
    }

    fun nextReplayGeneration(): Long = sequenceProcessor.nextReplayGeneration()

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
                // User navigated to top: integrate all pending newer events into visible feed
                currentOverlay = RealtimeOverlayReducer.integratePendingNewer(currentOverlay)
                val visible = RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
                val (newerCount, historyCount) = RealtimeOverlayReducer.computePendingCounts(currentOverlay)
                _state.update {
                    it.copy(
                        visibleEvents = visible,
                        realtimeOverlay = currentOverlay,
                        pendingNewerCount = newerCount,
                        pendingHistoryCount = historyCount,
                    )
                }
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

        // If we were buffering commits during rebootstrap, apply them now with strict continuity checks
        if (isRebootstrapping) {
            isRebootstrapping = false
            val now = currentTimeProvider()
            val validCommits =
                rebootstrapBuffer
                    .filter {
                        it.commit.tenantSequence > bootstrap.streamCursor &&
                            it.commit.replayId !in closedReplayGenerations &&
                            (now - it.receivedAtMs) <= 30_000L
                    }.sortedBy { it.commit.tenantSequence }

            rebootstrapBuffer.clear()

            // Verify strict sequence continuity: streamCursor + 1, + 2, ...
            var expectedSeq = bootstrap.streamCursor + 1L
            var hasGap = false
            for (item in validCommits) {
                if (item.commit.tenantSequence != expectedSeq) {
                    hasGap = true
                    break
                }
                expectedSeq++
            }

            if (hasGap) {
                // Gap detected in rebootstrap buffer: trigger clean resync instead of dying with assertion
                isRebootstrapping = true
                onTriggerRebootstrap()
                return
            }

            for (item in validCommits) {
                applyCommit(item.commit)
            }

            val newReplayId = sequenceProcessor.nextReplayGeneration()
            onSendReplayRequest(newReplayId, sequenceProcessor.currentSequence)
        }

        val visible = RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
        val (newerCount, historyCount) = RealtimeOverlayReducer.computePendingCounts(currentOverlay)
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
                pendingNewerCount = newerCount,
                pendingHistoryCount = historyCount,
            )
        }
    }

    private fun handleNextPage(page: EventsPage) {
        val existingIds = snapshotItems.map { it.id }.toSet()
        val newItems = page.items.filter { it.id !in existingIds }
        snapshotItems.addAll(newItems)
        nextCursor = page.nextCursor

        val visible = RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
        val (newerCount, historyCount) = RealtimeOverlayReducer.computePendingCounts(currentOverlay)
        _state.update {
            it.copy(
                visibleEvents = visible,
                hasMore = page.hasMore,
                isLoadingNextPage = false,
                paginationError = null,
                pendingNewerCount = newerCount,
                pendingHistoryCount = historyCount,
            )
        }
    }

    private suspend fun handleWsFrame(frame: IncomingWsFrame) {
        when (frame) {
            is IncomingWsFrame.TenantCommit -> {
                val commit = frame.frame
                if (commit.replayId in closedReplayGenerations) {
                    return
                }

                if (isRebootstrapping) {
                    val now = currentTimeProvider()
                    if (rebootstrapBuffer.size < 10_000) {
                        rebootstrapBuffer.add(BufferedCommit(commit = commit, receivedAtMs = now))
                    } else {
                        // Buffer exceeded -> restart rebootstrap safely
                        rebootstrapBuffer.clear()
                        onTriggerRebootstrap()
                    }
                    return
                }

                val validation =
                    sequenceProcessor.checkIncomingSequence(
                        incomingReplayId = commit.replayId,
                        incomingSequence = commit.tenantSequence,
                        payloadSha256 = commit.payloadSha256,
                    )
                when (validation) {
                    SequenceValidationResult.InOrder -> {
                        applyCommit(commit)
                    }
                    SequenceValidationResult.Duplicate -> {
                        // Verified duplicate frame -> safely drop
                    }
                    is SequenceValidationResult.GapDetected -> {
                        onSendReplayRequest(sequenceProcessor.currentReplayId, sequenceProcessor.currentSequence)
                    }
                    is SequenceValidationResult.IntegrityViolation,
                    is SequenceValidationResult.InvalidSequence,
                    -> {
                        closedReplayGenerations.add(commit.replayId)
                        isRebootstrapping = true
                        rebootstrapBuffer.clear()
                        onTriggerRebootstrap()
                    }
                }
            }
            is IncomingWsFrame.ResyncRequired -> {
                val replayId = frame.frame.replayId
                if (replayId in closedReplayGenerations || replayId != sequenceProcessor.currentReplayId) {
                    return // Ignore stale ResyncRequired from previous generation
                }
                closedReplayGenerations.add(replayId)
                isRebootstrapping = true
                rebootstrapBuffer.clear()
                onTriggerRebootstrap()
            }
            is IncomingWsFrame.Subscribed -> {
                if (frame.frame.replayId != sequenceProcessor.currentReplayId ||
                    frame.frame.replayId in closedReplayGenerations
                ) {
                    return
                }
                _state.update { it.copy(connectionState = RealtimeConnectionState.CONNECTED) }
            }
            is IncomingWsFrame.ReplayCompleted -> {
                if (frame.frame.replayId != sequenceProcessor.currentReplayId ||
                    frame.frame.replayId in closedReplayGenerations
                ) {
                    return
                }
                _state.update { it.copy(connectionState = RealtimeConnectionState.CONNECTED) }
            }
            is IncomingWsFrame.HeartbeatAck -> {
                if (frame.frame.replayId != sequenceProcessor.currentReplayId ||
                    frame.frame.replayId in closedReplayGenerations
                ) {
                    return
                }
                // Heartbeat liveness confirmed for active generation
            }
            is IncomingWsFrame.AccessCheckUnavailable -> {
                // Transient: do not invalidate cache, check generation
                if (frame.frame.replayId != sequenceProcessor.currentReplayId ||
                    frame.frame.replayId in closedReplayGenerations
                ) {
                    return
                }
            }
            is IncomingWsFrame.AuthRequired -> {
                if (frame.frame.reason == "TOKEN_EXPIRED") {
                    onTokenRefreshNeeded()
                } else {
                    _state.update { it.copy(connectionState = RealtimeConnectionState.FAILED) }
                }
            }
            is IncomingWsFrame.AccessInvalidated -> {
                onAccessInvalidated(frame.frame.reason ?: "UNKNOWN", frame.frame.companyId)
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
        // Two-phase atomic commit application:
        // 1. Calculate tentative new overlay and summary
        var tentativeOverlay = currentOverlay
        val createdDto = commit.eventCreated
        val updatedDto = commit.eventUpdated

        if (createdDto != null) {
            val event = EventsDtoMapper.mapEvent(createdDto)
            val (newOverlay, _) =
                RealtimeOverlayReducer.applyEventCommit(
                    currentOverlay = tentativeOverlay,
                    event = event,
                    query = query,
                    snapshotItems = snapshotItems,
                    isCreated = true,
                )
            tentativeOverlay = newOverlay
        }

        if (updatedDto != null) {
            val event = EventsDtoMapper.mapEvent(updatedDto)
            val (newOverlay, _) =
                RealtimeOverlayReducer.applyEventCommit(
                    currentOverlay = tentativeOverlay,
                    event = event,
                    query = query,
                    snapshotItems = snapshotItems,
                    isCreated = false,
                )
            tentativeOverlay = newOverlay
        }

        val updatedSummary: EventsSummary? =
            commit.summaryUpdated?.let {
                EventsDtoMapper.mapSummary(it)
            } ?: _state.value.summary

        // 2. Advance sequence counter with SHA256 caching
        sequenceProcessor.advanceSequence(commit.tenantSequence, commit.payloadSha256)

        // 3. Atomically publish state
        currentOverlay = tentativeOverlay
        val visible = RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
        val (newerCount, historyCount) = RealtimeOverlayReducer.computePendingCounts(currentOverlay)

        _state.update {
            it.copy(
                visibleEvents = visible,
                realtimeOverlay = currentOverlay,
                summary = updatedSummary,
                pendingNewerCount = newerCount,
                pendingHistoryCount = historyCount,
            )
        }
    }

    fun getNextCursor(): String? = nextCursor

    fun getAsOfSequence(): Long = sequenceProcessor.currentSequence
}
