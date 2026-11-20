package studio.acmenra.control.feature.events.data.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.acmenra.control.feature.events.data.datasource.IncomingWsEnvelope
import studio.acmenra.control.feature.events.data.datasource.IncomingWsFrame
import studio.acmenra.control.feature.events.data.mapper.EventsDtoMapper
import studio.acmenra.control.feature.events.data.model.EventsFilterOptionsDto
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
import studio.acmenra.control.feature.events.domain.repository.RebootstrapState
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

sealed interface BootstrapApplyResult {
    data class Applied(
        val replayId: Long,
    ) : BootstrapApplyResult

    data object RetryRequired : BootstrapApplyResult

    data object Stale : BootstrapApplyResult
}

sealed interface SessionCommand {
    data class InstallBootstrap(
        val bootstrap: EventsBootstrap,
        val generation: Long = 0L,
        val response: CompletableDeferred<BootstrapApplyResult>,
    ) : SessionCommand

    data class NextPageLoaded(
        val page: EventsPage,
        val snapshotId: String? = null,
    ) : SessionCommand

    data class InitialLoadFailed(
        val error: EventsInitialLoadError,
        val generation: Long = 0L,
    ) : SessionCommand

    data class PaginationFailed(
        val error: EventsPaginationError,
    ) : SessionCommand

    data class SetLoadingNextPage(
        val isLoading: Boolean,
    ) : SessionCommand

    data class WsConnectionActivated(
        val connectionId: Long,
    ) : SessionCommand

    data class WsFrameReceived(
        val envelope: IncomingWsEnvelope,
    ) : SessionCommand

    data class ConnectionStateChanged(
        val state: RealtimeConnectionState,
    ) : SessionCommand

    data class RebootstrapStateChanged(
        val rebootstrapState: RebootstrapState,
    ) : SessionCommand

    data class OptimisticAckPending(
        val eventId: String,
    ) : SessionCommand

    data class ClearOptimisticAckPending(
        val eventId: String,
    ) : SessionCommand

    data class AcknowledgeSucceeded(
        val event: Event,
    ) : SessionCommand

    data class EventMutationApplied(
        val event: Event,
    ) : SessionCommand

    data class AccessUpdated(
        val access: EventsAccessCapabilities,
        val generation: Long = 0L,
    ) : SessionCommand

    data class StartReplay(
        val replayId: Long,
        val afterSequence: Long,
    ) : SessionCommand

    data class NextReplayGeneration(
        val response: CompletableDeferred<Long>,
    ) : SessionCommand

    data class ResetSequence(
        val sequence: Long,
    ) : SessionCommand

    data object ViewportAtTop : SessionCommand

    data object Close : SessionCommand
}

private data class BufferedCommit(
    val commit: TenantCommitFrameDto,
    val receivedAt: TimeMark,
)

class EventsSessionProcessor(
    private val query: EventsQuery,
    initialAccess: EventsAccessCapabilities,
    private val scope: CoroutineScope,
    private val onSendReplayRequest: (replayId: Long, afterSequence: Long) -> Unit,
    private val onTriggerRebootstrap: () -> Unit,
    private val onTokenRefreshNeeded: () -> Unit,
    private val onSessionRevoked: () -> Unit = {},
    private val onAccessInvalidated: (reason: String, companyId: String?) -> Unit,
    private val onEventsReadRevoked: () -> Unit = {},
    private val onEventsReadGranted: () -> Unit = {},
    private val onFilterOptionsUpdated: ((EventsFilterOptionsDto) -> Unit)? = null,
    private val timeSource: TimeSource.Monotonic = TimeSource.Monotonic,
) {
    private val _state = MutableStateFlow(EventsFeedState(access = initialAccess))
    val state: StateFlow<EventsFeedState> = _state.asStateFlow()

    private val commandChannel = Channel<SessionCommand>(Channel.UNLIMITED)
    private val sequenceProcessor = TenantSequenceProcessor()
    private val snapshotItems = mutableListOf<Event>()
    private var currentOverlay = RealtimeOverlay()

    private var isAccessInvalidated = false
    private var currentBootstrapGeneration: Long = 0L
    private var isRebootstrapping = false
    private var bufferReplayId: Long? = null
    private val rebootstrapBuffer = mutableListOf<BufferedCommit>()
    private var maxClosedReplayGeneration: Long = 0L
    private var activeConnectionId: Long = 0L
    private var nextCursor: String? = null

    @kotlin.concurrent.Volatile
    private var eventsReadDeniedLatch: Boolean = !initialAccess.eventsRead

    @kotlin.concurrent.Volatile
    private var latestImmediateDeniedGeneration: Long = 0L

    private var latestProcessedAccessGeneration: Long = 0L

    fun setImmediateEventsReadDenied(
        denied: Boolean,
        generation: Long = 0L,
    ) {
        eventsReadDeniedLatch = denied
        if (denied && generation > latestImmediateDeniedGeneration) {
            latestImmediateDeniedGeneration = generation
        }
    }

    fun isImmediateEventsReadDenied(): Boolean = eventsReadDeniedLatch

    val currentReplayId: Long get() = sequenceProcessor.currentReplayId

    init {
        scope.launch {
            try {
                for (command in commandChannel) {
                    if (!isActive) break
                    try {
                        processCommand(command)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        when (command) {
                            is SessionCommand.InstallBootstrap -> command.response.cancel(e)
                            is SessionCommand.NextReplayGeneration -> command.response.cancel(e)
                            else -> Unit
                        }
                        throw e
                    } catch (e: Throwable) {
                        when (command) {
                            is SessionCommand.InstallBootstrap -> command.response.completeExceptionally(e)
                            is SessionCommand.NextReplayGeneration -> command.response.completeExceptionally(e)
                            else -> Unit
                        }
                        if (command is SessionCommand.Close) break
                        // Prevent uncaught exceptions from killing the command processing loop
                        isRebootstrapping = true
                        rebootstrapBuffer.clear()
                        onTriggerRebootstrap()
                    }
                    if (command is SessionCommand.Close) break
                }
            } finally {
                for (remaining in commandChannel) {
                    when (remaining) {
                        is SessionCommand.InstallBootstrap -> remaining.response.complete(BootstrapApplyResult.Stale)
                        is SessionCommand.NextReplayGeneration ->
                            remaining.response.completeExceptionally(
                                kotlinx.coroutines.CancellationException("Processor closed"),
                            )
                        else -> Unit
                    }
                }
            }
        }
    }

    fun dispatch(command: SessionCommand) {
        commandChannel.trySend(command)
    }

    fun close() {
        commandChannel.close()
    }

    fun applyEventMutationResult(event: Event) {
        dispatch(SessionCommand.EventMutationApplied(event))
    }

    fun startReplay(
        replayId: Long,
        afterSequence: Long,
    ) {
        dispatch(SessionCommand.StartReplay(replayId, afterSequence))
    }

    suspend fun nextReplayGeneration(): Long {
        val deferred = CompletableDeferred<Long>()
        commandChannel.send(SessionCommand.NextReplayGeneration(deferred))
        return deferred.await()
    }

    suspend fun installBootstrap(
        bootstrap: EventsBootstrap,
        generation: Long,
    ): BootstrapApplyResult {
        val deferred = CompletableDeferred<BootstrapApplyResult>()
        try {
            commandChannel.send(SessionCommand.InstallBootstrap(bootstrap, generation, deferred))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            return BootstrapApplyResult.Stale
        }
        return deferred.await()
    }

    private suspend fun processCommand(command: SessionCommand) {
        if (isAccessInvalidated && command !is SessionCommand.Close) {
            // Terminal state: ignore all frames and updates
            when (command) {
                is SessionCommand.InstallBootstrap ->
                    command.response.complete(BootstrapApplyResult.Stale)
                is SessionCommand.NextReplayGeneration ->
                    command.response.completeExceptionally(
                        IllegalStateException("Processor is terminal: access invalidated"),
                    )
                else -> Unit
            }
            return
        }

        if (eventsReadDeniedLatch && command !is SessionCommand.AccessUpdated && command !is SessionCommand.Close) {
            when (command) {
                is SessionCommand.ConnectionStateChanged -> {
                    _state.update { it.copy(connectionState = command.state) }
                }
                is SessionCommand.InstallBootstrap -> {
                    command.response.complete(BootstrapApplyResult.Stale)
                }
                is SessionCommand.NextReplayGeneration -> {
                    command.response.completeExceptionally(
                        IllegalStateException("Read access is denied by latch"),
                    )
                }
                else -> {
                    // Ignore all other commands that could mutate or republish event data
                }
            }
            return
        }

        when (command) {
            is SessionCommand.InstallBootstrap -> {
                handleBootstrap(command.bootstrap, command.generation, command.response)
            }
            is SessionCommand.NextPageLoaded -> handleNextPage(command.page, command.snapshotId)
            is SessionCommand.InitialLoadFailed -> {
                if (command.generation >= currentBootstrapGeneration) {
                    currentBootstrapGeneration = command.generation
                    _state.update {
                        it.copy(
                            isInitialLoading = false,
                            initialLoadError = command.error,
                        )
                    }
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
            is SessionCommand.WsConnectionActivated -> {
                if (command.connectionId > activeConnectionId) {
                    activeConnectionId = command.connectionId
                }
            }
            is SessionCommand.WsFrameReceived -> {
                val (connId, frame) = command.envelope
                if (connId < activeConnectionId) {
                    return
                }
                if (connId > activeConnectionId) {
                    activeConnectionId = connId
                }
                handleWsFrame(frame)
            }
            is SessionCommand.ConnectionStateChanged -> {
                _state.update { it.copy(connectionState = command.state) }
            }
            is SessionCommand.RebootstrapStateChanged -> {
                _state.update { it.copy(rebootstrapState = command.rebootstrapState) }
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
            is SessionCommand.AcknowledgeSucceeded -> handleAcknowledgeSucceeded(command.event)
            is SessionCommand.EventMutationApplied -> handleEventMutationApplied(command.event)
            is SessionCommand.AccessUpdated -> {
                if (command.generation < latestProcessedAccessGeneration) {
                    return
                }
                latestProcessedAccessGeneration = command.generation

                val wasRead = _state.value.access.eventsRead
                val nowRead = command.access.eventsRead

                if (!nowRead) {
                    eventsReadDeniedLatch = true
                } else if (latestImmediateDeniedGeneration <= command.generation) {
                    eventsReadDeniedLatch = false
                }

                if (eventsReadDeniedLatch) {
                    snapshotItems.clear()
                    currentOverlay = RealtimeOverlay()
                    rebootstrapBuffer.clear()
                    isRebootstrapping = false
                    bufferReplayId = null
                    nextCursor = null
                    _state.update {
                        it.copy(
                            access = command.access.copy(eventsRead = false),
                            visibleEvents = emptyList(),
                            realtimeOverlay = RealtimeOverlay(),
                            summary = null,
                            snapshotTotalCount = 0,
                            hasMore = false,
                            isLoadingNextPage = false,
                            paginationError = null,
                            pendingNewerCount = 0,
                            pendingHistoryCount = 0,
                            snapshotId = null,
                        )
                    }
                    if (wasRead) {
                        onEventsReadRevoked()
                    }
                } else {
                    if (!wasRead && nowRead) {
                        _state.update { it.copy(access = command.access) }
                        onEventsReadGranted()
                    } else {
                        _state.update { it.copy(access = command.access) }
                    }
                }
            }
            is SessionCommand.StartReplay -> {
                sequenceProcessor.startReplay(command.replayId, command.afterSequence)
                _state.update {
                    it.copy(
                        asOfSequence = sequenceProcessor.currentSequence,
                        replayId = command.replayId,
                    )
                }
            }
            is SessionCommand.NextReplayGeneration -> {
                maxClosedReplayGeneration = maxOf(maxClosedReplayGeneration, sequenceProcessor.currentReplayId)
                val gen = sequenceProcessor.nextReplayGeneration()
                if (isRebootstrapping) {
                    bufferReplayId = gen
                    rebootstrapBuffer.clear()
                }
                _state.update { it.copy(replayId = gen) }
                command.response.complete(gen)
            }
            is SessionCommand.ResetSequence -> {
                sequenceProcessor.resetSequence(command.sequence)
                _state.update { it.copy(asOfSequence = sequenceProcessor.currentSequence) }
            }
            is SessionCommand.ViewportAtTop -> {
                if (!_state.value.access.eventsRead || eventsReadDeniedLatch) return
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

    private suspend fun handleBootstrap(
        bootstrap: EventsBootstrap,
        generation: Long,
        response: CompletableDeferred<BootstrapApplyResult>,
    ) {
        if (!_state.value.access.eventsRead || eventsReadDeniedLatch) {
            response.complete(BootstrapApplyResult.Stale)
            return
        }
        if (generation <= currentBootstrapGeneration) {
            response.complete(BootstrapApplyResult.Stale)
            return
        }
        currentBootstrapGeneration = generation

        snapshotItems.clear()
        if (_state.value.access.eventsRead) {
            snapshotItems.addAll(bootstrap.page.items)
        }
        currentOverlay = RealtimeOverlay(pendingAckIds = currentOverlay.pendingAckIds)
        sequenceProcessor.resetSequence(bootstrap.streamCursor)
        nextCursor = bootstrap.page.nextCursor

        // If we were buffering commits during rebootstrap, apply them now with strict continuity and deduplication
        if (isRebootstrapping) {
            isRebootstrapping = false
            bufferReplayId = null
            val validCommits =
                rebootstrapBuffer
                    .filter {
                        it.commit.tenantSequence > bootstrap.streamCursor &&
                            it.receivedAt.elapsedNow() <= 30.seconds
                    }.sortedBy { it.commit.tenantSequence }

            rebootstrapBuffer.clear()

            // Deduplicate commits by tenantSequence
            val deduplicatedCommits = mutableListOf<TenantCommitFrameDto>()
            var deduplicationError = false
            for (item in validCommits) {
                val existing = deduplicatedCommits.lastOrNull()
                if (existing != null && existing.tenantSequence == item.commit.tenantSequence) {
                    if (existing.payloadSha256.lowercase() == item.commit.payloadSha256.lowercase()) {
                        // Verified duplicate frame with identical SHA -> drop
                        continue
                    } else {
                        // Same sequence with different SHA -> integrity violation!
                        deduplicationError = true
                        break
                    }
                }
                deduplicatedCommits.add(item.commit)
            }

            if (deduplicationError) {
                isRebootstrapping = true
                bufferReplayId = sequenceProcessor.currentReplayId
                response.complete(BootstrapApplyResult.RetryRequired)
                return
            }

            // Verify strict sequence continuity: streamCursor + 1, + 2, ...
            var expectedSeq = bootstrap.streamCursor + 1L
            var hasGap = false
            for (commit in deduplicatedCommits) {
                if (commit.tenantSequence != expectedSeq) {
                    hasGap = true
                    break
                }
                expectedSeq++
            }

            if (hasGap) {
                // Gap detected in rebootstrap buffer: retry bootstrap
                isRebootstrapping = true
                bufferReplayId = sequenceProcessor.currentReplayId
                response.complete(BootstrapApplyResult.RetryRequired)
                return
            }

            for (commit in deduplicatedCommits) {
                applyCommit(commit)
            }
        }

        maxClosedReplayGeneration = maxOf(maxClosedReplayGeneration, sequenceProcessor.currentReplayId)
        val newReplayId = sequenceProcessor.nextReplayGeneration()

        val currentSummary = _state.value.summary
        val nextSummary =
            when {
                currentSummary == null -> bootstrap.summary
                bootstrap.summary.summaryAsOfSequence >= currentSummary.summaryAsOfSequence -> bootstrap.summary
                else -> currentSummary
            }

        val visible = RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
        val (newerCount, historyCount) = RealtimeOverlayReducer.computePendingCounts(currentOverlay)
        _state.update {
            it.copy(
                visibleEvents = visible,
                realtimeOverlay = currentOverlay,
                snapshotId = bootstrap.page.asOfSequence.toString(),
                asOfSequence = sequenceProcessor.currentSequence,
                replayId = newReplayId,
                snapshotTotalCount = bootstrap.page.totalCount,
                hasMore = bootstrap.page.hasMore,
                isInitialLoading = false,
                initialLoadError = null,
                summary = nextSummary,
                pendingNewerCount = newerCount,
                pendingHistoryCount = historyCount,
            )
        }

        response.complete(BootstrapApplyResult.Applied(newReplayId))
    }

    private fun handleNextPage(
        page: EventsPage,
        snapshotId: String?,
    ) {
        if (!_state.value.access.eventsRead || eventsReadDeniedLatch) {
            _state.update { it.copy(isLoadingNextPage = false) }
            return
        }

        if (snapshotId != null && snapshotId != _state.value.snapshotId) {
            // Discard page from outdated snapshot
            _state.update { it.copy(isLoadingNextPage = false) }
            return
        }

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

    private fun handleEventMutationApplied(mutatedEvent: Event) {
        if (!_state.value.access.eventsRead || eventsReadDeniedLatch) return
        val idx = snapshotItems.indexOfFirst { it.id == mutatedEvent.id }
        if (idx != -1) {
            val cur = snapshotItems[idx]
            if (mutatedEvent.classificationVersion >= cur.classificationVersion) {
                snapshotItems[idx] = mutatedEvent
            }
        }
        val existingEntry = currentOverlay.entries[mutatedEvent.id]
        val updatedEntries =
            if (existingEntry != null &&
                mutatedEvent.classificationVersion >= existingEntry.event.classificationVersion
            ) {
                currentOverlay.entries + (mutatedEvent.id to existingEntry.copy(event = mutatedEvent))
            } else {
                currentOverlay.entries
            }

        currentOverlay = currentOverlay.copy(entries = updatedEntries)
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

    private fun handleAcknowledgeSucceeded(ackEvent: Event) {
        if (!_state.value.access.eventsRead || eventsReadDeniedLatch) return
        val newPending = currentOverlay.pendingAckIds - ackEvent.id
        val idx = snapshotItems.indexOfFirst { it.id == ackEvent.id }
        if (idx != -1) {
            val cur = snapshotItems[idx]
            if (ackEvent.classificationVersion >= cur.classificationVersion) {
                snapshotItems[idx] = ackEvent
            }
        }
        val existingEntry = currentOverlay.entries[ackEvent.id]
        val updatedEntries =
            if (existingEntry != null && ackEvent.classificationVersion >= existingEntry.event.classificationVersion) {
                currentOverlay.entries + (ackEvent.id to existingEntry.copy(event = ackEvent))
            } else {
                currentOverlay.entries
            }

        currentOverlay =
            currentOverlay.copy(
                pendingAckIds = newPending,
                entries = updatedEntries,
            )
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

    private suspend fun handleWsFrame(frame: IncomingWsFrame) {
        if (isAccessInvalidated || !_state.value.access.eventsRead || eventsReadDeniedLatch) return

        when (frame) {
            is IncomingWsFrame.TenantCommit -> {
                val commit = frame.frame
                if (commit.replayId <= maxClosedReplayGeneration) {
                    return
                }

                if (!commit.validateEnvelope()) {
                    return
                }

                if (isRebootstrapping) {
                    if (bufferReplayId != null && commit.replayId != bufferReplayId) {
                        return
                    }
                    if (rebootstrapBuffer.size < 10_000) {
                        rebootstrapBuffer.add(BufferedCommit(commit = commit, receivedAt = timeSource.markNow()))
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
                        isRebootstrapping = true
                        bufferReplayId = sequenceProcessor.currentReplayId
                        rebootstrapBuffer.clear()
                        onTriggerRebootstrap()
                    }
                }
            }
            is IncomingWsFrame.ResyncRequired -> {
                val replayId = frame.frame.replayId
                if (replayId <= maxClosedReplayGeneration || replayId != sequenceProcessor.currentReplayId) {
                    return // Ignore stale ResyncRequired from previous generation
                }
                isRebootstrapping = true
                bufferReplayId = sequenceProcessor.currentReplayId
                rebootstrapBuffer.clear()
                onTriggerRebootstrap()
            }
            is IncomingWsFrame.Subscribed -> {
                if (frame.frame.replayId != sequenceProcessor.currentReplayId ||
                    frame.frame.replayId <= maxClosedReplayGeneration
                ) {
                    return
                }
                if (frame.frame.replayEmpty) {
                    _state.update { it.copy(connectionState = RealtimeConnectionState.CONNECTED) }
                }
            }
            is IncomingWsFrame.ReplayCompleted -> {
                val completedFrame = frame.frame
                if (completedFrame.replayId != sequenceProcessor.currentReplayId ||
                    completedFrame.replayId <= maxClosedReplayGeneration
                ) {
                    return
                }
                if (completedFrame.throughSequence > sequenceProcessor.currentSequence) {
                    // Client has not received all replay commits yet: stay in replaying and request missing range
                    onSendReplayRequest(sequenceProcessor.currentReplayId, sequenceProcessor.currentSequence)
                    return
                }
                _state.update { it.copy(connectionState = RealtimeConnectionState.CONNECTED) }
            }
            is IncomingWsFrame.HeartbeatAck -> {
                if (frame.frame.replayId != sequenceProcessor.currentReplayId ||
                    frame.frame.replayId <= maxClosedReplayGeneration
                ) {
                    return
                }
                // Heartbeat liveness confirmed for active generation
            }
            is IncomingWsFrame.AccessCheckUnavailable -> {
                // Transient: do not invalidate cache, check generation
                if (frame.frame.replayId != sequenceProcessor.currentReplayId ||
                    frame.frame.replayId <= maxClosedReplayGeneration
                ) {
                    return
                }
            }
            is IncomingWsFrame.AuthRequired -> {
                when (frame.frame.reason) {
                    "TOKEN_EXPIRED" -> onTokenRefreshNeeded()
                    "SESSION_REVOKED" -> onSessionRevoked()
                    else -> {
                        _state.update { it.copy(connectionState = RealtimeConnectionState.FAILED) }
                    }
                }
            }
            is IncomingWsFrame.AccessInvalidated -> {
                isAccessInvalidated = true
                isRebootstrapping = false
                bufferReplayId = null
                snapshotItems.clear()
                rebootstrapBuffer.clear()
                currentOverlay = RealtimeOverlay()
                onAccessInvalidated(frame.frame.reason ?: "UNKNOWN", frame.frame.companyId)
                _state.update {
                    it.copy(
                        connectionState = RealtimeConnectionState.FAILED,
                        visibleEvents = emptyList(),
                        realtimeOverlay = RealtimeOverlay(),
                        summary = null,
                        snapshotTotalCount = 0,
                        hasMore = false,
                        asOfSequence = 0L,
                        pendingNewerCount = 0,
                        pendingHistoryCount = 0,
                    )
                }
            }
        }
    }

    private fun applyCommit(commit: TenantCommitFrameDto) {
        if (!_state.value.access.eventsRead || eventsReadDeniedLatch) return

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

        val currentSummary = _state.value.summary
        val newSummary = commit.summaryUpdated?.let { EventsDtoMapper.mapSummary(it) }
        val updatedSummary: EventsSummary? =
            when {
                newSummary == null -> currentSummary
                currentSummary == null -> newSummary
                newSummary.summaryAsOfSequence >= currentSummary.summaryAsOfSequence -> newSummary
                else -> currentSummary
            }

        // Notify filter options updated if present
        commit.filterOptionsUpdated?.let { optionsDto ->
            onFilterOptionsUpdated?.invoke(optionsDto)
        }

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
                asOfSequence = sequenceProcessor.currentSequence,
                replayId = sequenceProcessor.currentReplayId,
                pendingNewerCount = newerCount,
                pendingHistoryCount = historyCount,
            )
        }
    }

    fun getNextCursor(): String? = nextCursor

    fun getAsOfSequence(): Long = sequenceProcessor.currentSequence
}
