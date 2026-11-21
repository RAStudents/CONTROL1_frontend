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
import studio.acmenra.control.feature.events.data.model.MAX_SAFE_JS_INTEGER
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

    data class RequiresFreshAuth(
        val initialReplayId: Long = 1L,
        val afterSequence: Long = 0L,
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

    data class FreshAuthGenerationStarted(
        val authGenerationId: Long,
        val connectionId: Long,
        val initialReplayId: Long = 1L,
        val afterSequence: Long,
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
        val response: CompletableDeferred<ReplayGenerationResult>,
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
    private val onFreshAuthNeeded: ((afterSequence: Long, replayId: Long) -> Unit)? = null,
    private val timeSource: TimeSource.Monotonic = TimeSource.Monotonic,
    private val companyId: String = "",
) {
    private val _state =
        MutableStateFlow(
            EventsFeedState(
                access = initialAccess,
                isInitialLoading = initialAccess.eventsRead && companyId.isNotBlank(),
            ),
        )
    val state: StateFlow<EventsFeedState> = _state.asStateFlow()

    private val commandChannel = Channel<SessionCommand>(capacity = 512)
    private val sequenceProcessor = TenantSequenceProcessor()
    private val snapshotItems = mutableListOf<Event>()
    private var currentOverlay = RealtimeOverlay()

    private var isAccessInvalidated = false
    private var currentBootstrapGeneration: Long = 0L
    private var isRebootstrapping = false
    private var bufferReplayId: Long? = null
    private val rebootstrapBuffer = mutableListOf<BufferedCommit>()
    private var maxClosedReplayGeneration: Long = 0L
    private var activeAuthGenerationId: Long = 1L
    private var activeConnectionId: Long = 0L
    private var nextCursor: String? = null

    private data class AccessFence(
        val generation: Long = 0L,
        val denied: Boolean = false,
    )

    private val accessFence =
        MutableStateFlow(
            AccessFence(
                generation = 0L,
                denied = !initialAccess.eventsRead,
            ),
        )

    private var latestProcessedAccessGeneration: Long = 0L

    fun setImmediateEventsReadDenied(
        denied: Boolean,
        generation: Long = 0L,
    ) {
        accessFence.update { current ->
            if (denied) {
                val nextGen = maxOf(current.generation, generation)
                AccessFence(generation = nextGen, denied = true)
            } else {
                if (generation >= current.generation) {
                    AccessFence(generation = generation, denied = false)
                } else {
                    current
                }
            }
        }
    }

    fun isImmediateEventsReadDenied(): Boolean = accessFence.value.denied

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
                commandChannel.close()
                while (true) {
                    val remaining = commandChannel.tryReceive().getOrNull() ?: break
                    when (remaining) {
                        is SessionCommand.InstallBootstrap ->
                            remaining.response.complete(BootstrapApplyResult.Stale)
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
        val result = commandChannel.trySend(command)
        if (result.isFailure) {
            if (command is SessionCommand.WsFrameReceived) {
                onTriggerRebootstrap()
            }
        }
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

    suspend fun requestNextReplayGeneration(): ReplayGenerationResult {
        val deferred = CompletableDeferred<ReplayGenerationResult>()
        try {
            commandChannel.send(SessionCommand.NextReplayGeneration(deferred))
        } catch (e: kotlinx.coroutines.CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        }
        return deferred.await()
    }

    suspend fun nextReplayGeneration(): Long =
        when (val res = requestNextReplayGeneration()) {
            is ReplayGenerationResult.Next -> res.replayId
            is ReplayGenerationResult.RequiresFreshAuth ->
                throw IllegalStateException("Replay generation overflow past MAX_SAFE_JS_INTEGER: fresh AUTH required")
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

        if (accessFence.value.denied && command !is SessionCommand.AccessUpdated && command !is SessionCommand.Close) {
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
            is SessionCommand.FreshAuthGenerationStarted -> {
                if (command.authGenerationId >= activeAuthGenerationId) {
                    activeAuthGenerationId = command.authGenerationId
                    activeConnectionId = command.connectionId
                    maxClosedReplayGeneration = command.initialReplayId - 1L
                    sequenceProcessor.resetSequence(command.afterSequence, command.initialReplayId)
                    isRebootstrapping = false
                    bufferReplayId = null
                    rebootstrapBuffer.clear()
                    _state.update {
                        it.copy(
                            replayId = command.initialReplayId,
                            asOfSequence = command.afterSequence,
                            connectionState = RealtimeConnectionState.CONNECTING,
                        )
                    }
                }
            }
            is SessionCommand.WsFrameReceived -> {
                val (connId, frame) = command.envelope
                if (activeConnectionId == 0L) {
                    activeConnectionId = connId
                } else if (connId != activeConnectionId) {
                    return
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
                var isFenceDenied = false
                accessFence.update { current ->
                    val nextDenied =
                        if (!command.access.eventsRead) {
                            true
                        } else if (current.generation > command.generation && current.denied) {
                            true
                        } else {
                            false
                        }
                    val nextGen = maxOf(current.generation, command.generation)
                    isFenceDenied = nextDenied
                    AccessFence(generation = nextGen, denied = nextDenied)
                }

                if (isFenceDenied) {
                    snapshotItems.clear()
                    currentOverlay = RealtimeOverlay()
                    rebootstrapBuffer.clear()
                    sequenceProcessor.startReplay(1L, 0L)
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
                            asOfSequence = 0L,
                            hasMore = false,
                            isInitialLoading = false,
                            initialLoadError = null,
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
                    if (!wasRead && command.access.eventsRead) {
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
                when (val res = sequenceProcessor.tryNextReplayGeneration()) {
                    is ReplayGenerationResult.Next -> {
                        if (isRebootstrapping) {
                            bufferReplayId = res.replayId
                            rebootstrapBuffer.clear()
                        }
                        _state.update { it.copy(replayId = res.replayId) }
                        command.response.complete(res)
                    }
                    is ReplayGenerationResult.RequiresFreshAuth -> {
                        maxClosedReplayGeneration = MAX_SAFE_JS_INTEGER
                        sequenceProcessor.resetSequence(sequenceProcessor.currentSequence, res.initialReplayId)
                        if (isRebootstrapping) {
                            bufferReplayId = res.initialReplayId
                            rebootstrapBuffer.clear()
                        }
                        _state.update { it.copy(replayId = res.initialReplayId) }
                        onFreshAuthNeeded?.invoke(sequenceProcessor.currentSequence, res.initialReplayId)
                        command.response.complete(res)
                    }
                }
            }
            is SessionCommand.ResetSequence -> {
                sequenceProcessor.resetSequence(command.sequence)
                _state.update { it.copy(asOfSequence = sequenceProcessor.currentSequence) }
            }
            is SessionCommand.ViewportAtTop -> {
                if (!_state.value.access.eventsRead || accessFence.value.denied) return
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
        if (!_state.value.access.eventsRead || accessFence.value.denied) {
            response.complete(BootstrapApplyResult.Stale)
            return
        }
        if (generation <= currentBootstrapGeneration) {
            response.complete(BootstrapApplyResult.Stale)
            return
        }

        // Validate summary as-of sequence equality with bootstrap snapshot page and streamCursor
        if (bootstrap.summary.summaryAsOfSequence != bootstrap.page.asOfSequence ||
            bootstrap.page.asOfSequence != bootstrap.streamCursor ||
            !TenantSequenceProcessor.isValidSafeInteger(bootstrap.streamCursor) ||
            !TenantSequenceProcessor.isValidSafeInteger(bootstrap.page.asOfSequence) ||
            !TenantSequenceProcessor.isValidSafeInteger(bootstrap.summary.summaryAsOfSequence)
        ) {
            isRebootstrapping = true
            bufferReplayId = sequenceProcessor.currentReplayId
            response.complete(BootstrapApplyResult.RetryRequired)
            return
        }

        // 1. Build and validate buffered commits if rebootstrapping
        val deduplicatedCommits = mutableListOf<TenantCommitFrameDto>()
        if (isRebootstrapping) {
            val validCommits =
                rebootstrapBuffer
                    .filter {
                        it.commit.tenantSequence > bootstrap.streamCursor &&
                            it.receivedAt.elapsedNow() <= 30.seconds
                    }.sortedBy { it.commit.tenantSequence }

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
                // DO NOT touch snapshotItems, currentOverlay, nextCursor, sequenceProcessor or currentBootstrapGeneration!
                isRebootstrapping = true
                bufferReplayId = sequenceProcessor.currentReplayId
                response.complete(BootstrapApplyResult.RetryRequired)
                return
            }

            // Verify strict sequence continuity: streamCursor + 1, + 2, ...
            var expectedSeq =
                if (bootstrap.streamCursor >= MAX_SAFE_JS_INTEGER) {
                    -1L
                } else {
                    bootstrap.streamCursor + 1L
                }
            var hasGap = (expectedSeq == -1L)
            if (!hasGap) {
                for (commit in deduplicatedCommits) {
                    if (commit.tenantSequence != expectedSeq) {
                        hasGap = true
                        break
                    }
                    if (expectedSeq >= MAX_SAFE_JS_INTEGER) {
                        hasGap = true
                        break
                    }
                    expectedSeq++
                }
            }

            if (hasGap) {
                // Gap detected in rebootstrap buffer or JS safe int overflow: retry bootstrap without corrupting displayed feed!
                isRebootstrapping = true
                bufferReplayId = sequenceProcessor.currentReplayId
                response.complete(BootstrapApplyResult.RetryRequired)
                return
            }
        }

        // 2. Prepare candidate state (without mutating processor state yet)
        val candidateSnapshotItems =
            if (_state.value.access.eventsRead) {
                bootstrap.page.items.toMutableList()
            } else {
                mutableListOf()
            }
        var candidateOverlay = RealtimeOverlay(pendingAckIds = currentOverlay.pendingAckIds)
        val candidateNextCursor = bootstrap.page.nextCursor

        var candidateSummary = bootstrap.summary
        val optionsToNotify = mutableListOf<EventsFilterOptionsDto>()

        // If rebootstrapping, apply the verified commits to candidate overlay & summary
        if (isRebootstrapping && deduplicatedCommits.isNotEmpty()) {
            for (commit in deduplicatedCommits) {
                val createdDto = commit.eventCreated
                val updatedDto = commit.eventUpdated

                if (createdDto != null) {
                    val event = EventsDtoMapper.mapEvent(createdDto)
                    val (newOverlay, _) =
                        RealtimeOverlayReducer.applyEventCommit(
                            currentOverlay = candidateOverlay,
                            event = event,
                            query = query,
                            snapshotItems = candidateSnapshotItems,
                            isEventCreated = true,
                            hasMore = bootstrap.page.hasMore,
                        )
                    candidateOverlay = newOverlay
                }

                if (updatedDto != null) {
                    val event = EventsDtoMapper.mapEvent(updatedDto)
                    val (newOverlay, _) =
                        RealtimeOverlayReducer.applyEventCommit(
                            currentOverlay = candidateOverlay,
                            event = event,
                            query = query,
                            snapshotItems = candidateSnapshotItems,
                            isEventCreated = false,
                            hasMore = bootstrap.page.hasMore,
                        )
                    candidateOverlay = newOverlay
                }

                val newSummary = commit.summaryUpdated?.let { EventsDtoMapper.mapSummary(it) }
                if (newSummary != null) {
                    if (newSummary.summaryAsOfSequence > commit.tenantSequence) {
                        isRebootstrapping = true
                        bufferReplayId = sequenceProcessor.currentReplayId
                        response.complete(BootstrapApplyResult.RetryRequired)
                        return
                    }
                    if (newSummary.summaryAsOfSequence >= candidateSummary.summaryAsOfSequence) {
                        candidateSummary = newSummary
                    }
                }

                commit.filterOptionsUpdated?.let { optionsDto ->
                    optionsToNotify.add(optionsDto)
                }
            }

            if (candidateOverlay.requiresRefresh) {
                isRebootstrapping = true
                bufferReplayId = sequenceProcessor.currentReplayId
                response.complete(BootstrapApplyResult.RetryRequired)
                return
            }
        }

        // 3. Atomically publish verified state
        currentBootstrapGeneration = generation

        snapshotItems.clear()
        snapshotItems.addAll(candidateSnapshotItems)
        currentOverlay = candidateOverlay
        nextCursor = candidateNextCursor

        sequenceProcessor.resetSequence(bootstrap.streamCursor)
        if (isRebootstrapping && deduplicatedCommits.isNotEmpty()) {
            for (commit in deduplicatedCommits) {
                sequenceProcessor.advanceSequence(commit.tenantSequence, commit.payloadSha256)
            }
        }

        if (isRebootstrapping) {
            isRebootstrapping = false
            bufferReplayId = null
            rebootstrapBuffer.removeAll { it.commit.tenantSequence <= sequenceProcessor.currentSequence }
        }

        maxClosedReplayGeneration = maxOf(maxClosedReplayGeneration, sequenceProcessor.currentReplayId)
        val genResult = sequenceProcessor.tryNextReplayGeneration()
        val newReplayId =
            when (genResult) {
                is ReplayGenerationResult.Next -> genResult.replayId
                is ReplayGenerationResult.RequiresFreshAuth -> {
                    maxClosedReplayGeneration = MAX_SAFE_JS_INTEGER
                    sequenceProcessor.resetSequence(bootstrap.streamCursor, genResult.initialReplayId)
                    genResult.initialReplayId
                }
            }

        val visible = RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
        val (newerCount, historyCount) = RealtimeOverlayReducer.computePendingCounts(currentOverlay)
        _state.update {
            it.copy(
                visibleEvents = visible,
                realtimeOverlay = currentOverlay,
                snapshotId = bootstrap.page.snapshotId,
                asOfSequence = sequenceProcessor.currentSequence,
                replayId = newReplayId,
                snapshotTotalCount = bootstrap.page.totalCount,
                hasMore = bootstrap.page.hasMore,
                isInitialLoading = false,
                initialLoadError = null,
                summary = candidateSummary,
                pendingNewerCount = newerCount,
                pendingHistoryCount = historyCount,
            )
        }

        // Only invoke side effects after atomic state commit
        for (optionsDto in optionsToNotify) {
            onFilterOptionsUpdated?.invoke(optionsDto)
        }

        when (genResult) {
            is ReplayGenerationResult.Next -> {
                response.complete(BootstrapApplyResult.Applied(genResult.replayId))
            }
            is ReplayGenerationResult.RequiresFreshAuth -> {
                response.complete(
                    BootstrapApplyResult.RequiresFreshAuth(
                        initialReplayId = genResult.initialReplayId,
                        afterSequence = bootstrap.streamCursor,
                    ),
                )
            }
        }
    }

    private fun handleNextPage(
        page: EventsPage,
        snapshotId: String?,
    ) {
        if (!_state.value.access.eventsRead || accessFence.value.denied) {
            _state.update { it.copy(isLoadingNextPage = false) }
            return
        }

        if (snapshotId != null && snapshotId != _state.value.snapshotId) {
            // Discard page from outdated snapshot
            _state.update { it.copy(isLoadingNextPage = false) }
            return
        }

        if (page.hasMore && page.nextCursor == null) {
            _state.update {
                it.copy(
                    isLoadingNextPage = false,
                    paginationError =
                        EventsPaginationError.Server("Contract violation: hasMore is true but nextCursor is null"),
                )
            }
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
        if (!_state.value.access.eventsRead || accessFence.value.denied) return
        val existingSnapshot = snapshotItems.firstOrNull { it.id == mutatedEvent.id }
        val existingEntry = currentOverlay.entries[mutatedEvent.id]
        val currentEvent = existingEntry?.event ?: existingSnapshot

        val finalEvent =
            if (currentEvent != null) {
                when (val mergeRes = EventMerger.validateAndMerge(current = currentEvent, incoming = mutatedEvent)) {
                    is EventMergeResult.IntegrityViolation -> {
                        onTriggerRebootstrap()
                        return
                    }
                    is EventMergeResult.Stale -> return
                    is EventMergeResult.Success -> mergeRes.merged
                }
            } else {
                mutatedEvent
            }

        val idx = snapshotItems.indexOfFirst { it.id == finalEvent.id }
        if (idx != -1) {
            snapshotItems[idx] = finalEvent
        }
        val updatedEntries =
            if (existingEntry != null) {
                currentOverlay.entries +
                    (
                        finalEvent.id to
                            existingEntry.copy(
                                event = finalEvent,
                                eventVersion = finalEvent.eventVersion,
                            )
                    )
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
        if (!_state.value.access.eventsRead || accessFence.value.denied) return
        val newPending = currentOverlay.pendingAckIds - ackEvent.id

        val existingSnapshot = snapshotItems.firstOrNull { it.id == ackEvent.id }
        val existingEntry = currentOverlay.entries[ackEvent.id]
        val currentEvent = existingEntry?.event ?: existingSnapshot

        val finalEvent =
            if (currentEvent != null) {
                when (val mergeRes = EventMerger.validateAndMerge(current = currentEvent, incoming = ackEvent)) {
                    is EventMergeResult.IntegrityViolation -> {
                        onTriggerRebootstrap()
                        return
                    }
                    is EventMergeResult.Stale -> return
                    is EventMergeResult.Success -> mergeRes.merged
                }
            } else {
                ackEvent
            }

        val idx = snapshotItems.indexOfFirst { it.id == finalEvent.id }
        if (idx != -1) {
            snapshotItems[idx] = finalEvent
        }
        val updatedEntries =
            if (existingEntry != null) {
                currentOverlay.entries +
                    (
                        finalEvent.id to
                            existingEntry.copy(
                                event = finalEvent,
                                eventVersion = finalEvent.eventVersion,
                            )
                    )
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
        if (isAccessInvalidated || !_state.value.access.eventsRead || accessFence.value.denied) return

        when (frame) {
            is IncomingWsFrame.MalformedCommit -> {
                if (frame.replayId != null && frame.replayId <= maxClosedReplayGeneration) {
                    return
                }
                isRebootstrapping = true
                bufferReplayId = sequenceProcessor.currentReplayId
                rebootstrapBuffer.clear()
                onTriggerRebootstrap()
            }
            is IncomingWsFrame.TenantCommit -> {
                val commit = frame.frame
                if (commit.replayId <= maxClosedReplayGeneration) {
                    return
                }

                if (!commit.validateEnvelope()) {
                    isRebootstrapping = true
                    bufferReplayId = sequenceProcessor.currentReplayId
                    rebootstrapBuffer.clear()
                    onTriggerRebootstrap()
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
                        maxClosedReplayGeneration = maxOf(maxClosedReplayGeneration, sequenceProcessor.currentReplayId)
                        when (val genResult = sequenceProcessor.tryNextReplayGeneration()) {
                            is ReplayGenerationResult.Next -> {
                                _state.update { it.copy(replayId = genResult.replayId) }
                                onSendReplayRequest(genResult.replayId, sequenceProcessor.currentSequence)
                            }
                            is ReplayGenerationResult.RequiresFreshAuth -> {
                                maxClosedReplayGeneration = MAX_SAFE_JS_INTEGER
                                sequenceProcessor.resetSequence(
                                    sequenceProcessor.currentSequence,
                                    genResult.initialReplayId,
                                )
                                _state.update { it.copy(replayId = genResult.initialReplayId) }
                                onFreshAuthNeeded?.invoke(sequenceProcessor.currentSequence, genResult.initialReplayId)
                            }
                        }
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
                maxClosedReplayGeneration = maxOf(maxClosedReplayGeneration, replayId)
                isRebootstrapping = true
                bufferReplayId = replayId
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
        if (!_state.value.access.eventsRead || accessFence.value.denied) return

        if (!commit.validateEnvelope()) {
            if (!isRebootstrapping) {
                isRebootstrapping = true
                bufferReplayId = sequenceProcessor.currentReplayId
                onTriggerRebootstrap()
            }
            return
        }

        // Two-phase atomic commit application:
        // 1. Calculate tentative new overlay and summary
        var tentativeOverlay = currentOverlay
        val createdDto = commit.eventCreated
        val updatedDto = commit.eventUpdated
        val currentHasMore = (nextCursor != null)

        if (createdDto != null) {
            val event = EventsDtoMapper.mapEvent(createdDto)
            val (newOverlay, _) =
                RealtimeOverlayReducer.applyEventCommit(
                    currentOverlay = tentativeOverlay,
                    event = event,
                    query = query,
                    snapshotItems = snapshotItems,
                    isEventCreated = true,
                    hasMore = currentHasMore,
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
                    isEventCreated = false,
                    hasMore = currentHasMore,
                )
            tentativeOverlay = newOverlay
        }

        if (tentativeOverlay.requiresRefresh) {
            if (!isRebootstrapping) {
                isRebootstrapping = true
                bufferReplayId = sequenceProcessor.currentReplayId
                onTriggerRebootstrap()
            }
            return
        }

        val currentSummary = _state.value.summary
        val newSummary = commit.summaryUpdated?.let { EventsDtoMapper.mapSummary(it) }
        if (newSummary != null && newSummary.summaryAsOfSequence > commit.tenantSequence) {
            // Causality violation: summary from future sequence!
            if (!isRebootstrapping) {
                isRebootstrapping = true
                bufferReplayId = sequenceProcessor.currentReplayId
                onTriggerRebootstrap()
            }
            return
        }
        val updatedSummary: EventsSummary? =
            when {
                newSummary == null -> currentSummary
                currentSummary == null -> newSummary
                newSummary.summaryAsOfSequence >= currentSummary.summaryAsOfSequence -> newSummary
                else -> currentSummary
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

        // 4. Side effect strictly AFTER atomic commit and publication
        commit.filterOptionsUpdated?.let { optionsDto ->
            onFilterOptionsUpdated?.invoke(optionsDto)
        }

        if (currentOverlay.entries.size > 500) {
            onTriggerRebootstrap()
        }
    }

    fun getNextCursor(): String? = nextCursor

    fun getAsOfSequence(): Long = sequenceProcessor.currentSequence
}
