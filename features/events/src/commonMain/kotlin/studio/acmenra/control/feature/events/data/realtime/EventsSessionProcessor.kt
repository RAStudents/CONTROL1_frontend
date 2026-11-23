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
import kotlinx.coroutines.selects.select
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
import studio.acmenra.control.feature.events.domain.model.OverlayEntry
import studio.acmenra.control.feature.events.domain.model.OverlayMembership
import studio.acmenra.control.feature.events.domain.model.RealtimeConnectionState
import studio.acmenra.control.feature.events.domain.model.RealtimeEventOrigin
import studio.acmenra.control.feature.events.domain.model.RealtimeOverlay
import studio.acmenra.control.feature.events.domain.repository.EventsFeedDelta
import studio.acmenra.control.feature.events.domain.repository.EventsFeedState
import studio.acmenra.control.feature.events.domain.repository.PendingNewerFlushResult
import studio.acmenra.control.feature.events.domain.repository.RebootstrapState
import studio.acmenra.control.feature.events.domain.repository.appendFeedDelta
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

    data class ViewportAtTop(
        val expectedEventIds: List<String> = emptyList(),
        val response: CompletableDeferred<PendingNewerFlushResult>? = null,
    ) : SessionCommand

    data class AcknowledgeFeedDeltas(
        val batchId: Long,
        val response: CompletableDeferred<Unit>? = null,
    ) : SessionCommand

    data object Close : SessionCommand
}

private data class BufferedCommit(
    val commit: TenantCommitFrameDto,
    val receivedAt: TimeMark,
)

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
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
    private val priorityCommandChannel = Channel<SessionCommand>(capacity = 16)
    private val isClosed = kotlin.concurrent.atomics.AtomicBoolean(false)
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
    private var currentTotalCount: Int = 0
    private var feedDeltaBatchCounter: Long = 0L
    private val earlyFramesBuffer = mutableMapOf<Long, MutableList<IncomingWsFrame>>()

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
                while (isActive) {
                    val priorityCmd = priorityCommandChannel.tryReceive().getOrNull()
                    val command =
                        if (priorityCmd != null) {
                            priorityCmd
                        } else {
                            select<SessionCommand?> {
                                priorityCommandChannel.onReceiveCatching { it.getOrNull() }
                                commandChannel.onReceiveCatching { it.getOrNull() }
                            }
                        }
                    if (command == null || command is SessionCommand.Close) break
                    try {
                        processCommand(command)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        when (command) {
                            is SessionCommand.InstallBootstrap -> command.response.cancel(e)
                            is SessionCommand.NextReplayGeneration -> command.response.cancel(e)
                            is SessionCommand.ViewportAtTop -> command.response?.cancel(e)
                            is SessionCommand.AcknowledgeFeedDeltas -> command.response?.cancel(e)
                            else -> Unit
                        }
                        throw e
                    } catch (e: Throwable) {
                        when (command) {
                            is SessionCommand.InstallBootstrap -> command.response.completeExceptionally(e)
                            is SessionCommand.NextReplayGeneration -> command.response.completeExceptionally(e)
                            is SessionCommand.ViewportAtTop -> command.response?.completeExceptionally(e)
                            is SessionCommand.AcknowledgeFeedDeltas -> command.response?.completeExceptionally(e)
                            else -> Unit
                        }
                        // Prevent uncaught exceptions from killing the command processing loop
                        isRebootstrapping = true
                        rebootstrapBuffer.clear()
                        onTriggerRebootstrap()
                    }
                }
            } finally {
                // Actor-owned collections are cleared exclusively inside the actor loop to guarantee thread safety
                commandChannel.close()
                priorityCommandChannel.close()
                earlyFramesBuffer.clear()
                rebootstrapBuffer.clear()
                snapshotItems.clear()

                drainPendingCommands(priorityCommandChannel)
                drainPendingCommands(commandChannel)
            }
        }
    }

    private fun drainPendingCommands(channel: Channel<SessionCommand>) {
        while (true) {
            val remaining = channel.tryReceive().getOrNull() ?: break
            when (remaining) {
                is SessionCommand.InstallBootstrap ->
                    remaining.response.complete(BootstrapApplyResult.Stale)
                is SessionCommand.NextReplayGeneration ->
                    remaining.response.completeExceptionally(
                        kotlinx.coroutines.CancellationException("Processor closed"),
                    )
                is SessionCommand.ViewportAtTop ->
                    remaining.response?.complete(
                        PendingNewerFlushResult(
                            batchId = 0L,
                            snapshotId = null,
                            materializedEventIds = emptyList(),
                            createdEventIds = emptyList(),
                        ),
                    )
                is SessionCommand.AcknowledgeFeedDeltas ->
                    remaining.response?.complete(Unit)
                else -> Unit
            }
        }
    }

    fun dispatch(command: SessionCommand) {
        if (isClosed.load()) {
            when (command) {
                is SessionCommand.InstallBootstrap ->
                    command.response.complete(BootstrapApplyResult.Stale)
                is SessionCommand.NextReplayGeneration ->
                    command.response.completeExceptionally(
                        kotlinx.coroutines.CancellationException("Processor closed"),
                    )
                else -> Unit
            }
            return
        }

        if (command is SessionCommand.WsFrameReceived) {
            val (connId, frame) = command.envelope
            when (frame) {
                is IncomingWsFrame.AccessInvalidated -> {
                    // Fail-Closed: Immediately engage deny latch synchronously before queueing
                    setImmediateEventsReadDenied(denied = true, generation = Long.MAX_VALUE)
                    isAccessInvalidated = true
                    val wasRead = _state.value.access.eventsRead
                    currentTotalCount = 0
                    _state.update {
                        it.copy(
                            access = it.access.copy(eventsRead = false),
                            connectionState = RealtimeConnectionState.FAILED,
                            visibleEvents = emptyList(),
                            realtimeOverlay = RealtimeOverlay(),
                            summary = null,
                            snapshotTotalCount = 0,
                            asOfSequence = 0L,
                            pendingNewerCount = 0,
                            pendingHistoryCount = 0,
                            pendingDeltas = emptyList(),
                        )
                    }
                    if (wasRead) {
                        onEventsReadRevoked()
                    }

                    val prioritySent = priorityCommandChannel.trySend(command)
                    if (prioritySent.isFailure) {
                        // Actor saturated or not processing: invoke terminal callback synchronously
                        onAccessInvalidated(frame.frame.reason ?: "UNKNOWN", frame.frame.companyId)
                    }
                    return
                }
                is IncomingWsFrame.AuthRequired -> {
                    val prioritySent = priorityCommandChannel.trySend(command)
                    if (prioritySent.isFailure) {
                        when (frame.frame.reason) {
                            "TOKEN_EXPIRED" -> onTokenRefreshNeeded()
                            "SESSION_REVOKED" -> onSessionRevoked()
                            else ->
                                _state.update {
                                    it.copy(
                                        connectionState = RealtimeConnectionState.FAILED,
                                    )
                                }
                        }
                    }
                    return
                }
                else -> {
                    val result = commandChannel.trySend(command)
                    if (result.isFailure) {
                        // Ordinary commit queue overflow -> controlled rebootstrap without sequence advance
                        isRebootstrapping = true
                        bufferReplayId = sequenceProcessor.currentReplayId
                        onTriggerRebootstrap()
                    }
                    return
                }
            }
        }

        if (command is SessionCommand.Close) {
            if (!priorityCommandChannel.trySend(command).isSuccess) {
                commandChannel.trySend(command)
            }
            return
        }

        val result = commandChannel.trySend(command)
        if (result.isFailure) {
            when (command) {
                is SessionCommand.InstallBootstrap ->
                    command.response.complete(BootstrapApplyResult.Stale)
                is SessionCommand.NextReplayGeneration ->
                    command.response.completeExceptionally(
                        kotlinx.coroutines.CancellationException("Processor command queue full"),
                    )
                is SessionCommand.AccessUpdated -> {
                    if (!command.access.eventsRead) {
                        setImmediateEventsReadDenied(denied = true, generation = command.generation)
                    }
                }
                else -> Unit
            }
        }
    }

    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        priorityCommandChannel.trySend(SessionCommand.Close)
        commandChannel.close()
        priorityCommandChannel.close()
        // Actor-owned collections are cleared in the actor's finally block to avoid thread-safety races
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
        if (isClosed.load()) {
            deferred.completeExceptionally(kotlinx.coroutines.CancellationException("Processor closed"))
            return deferred.await()
        }
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
        if (isClosed.load()) {
            return BootstrapApplyResult.Stale
        }
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
            if (command is SessionCommand.WsFrameReceived &&
                command.envelope.frame is IncomingWsFrame.AccessInvalidated
            ) {
                handleWsFrame(command.envelope.frame)
                return
            }
            // Terminal state: ignore all frames and updates
            when (command) {
                is SessionCommand.InstallBootstrap ->
                    command.response.complete(BootstrapApplyResult.Stale)
                is SessionCommand.NextReplayGeneration ->
                    command.response.completeExceptionally(
                        IllegalStateException("Processor is terminal: access invalidated"),
                    )
                is SessionCommand.ViewportAtTop ->
                    command.response?.complete(
                        PendingNewerFlushResult(
                            batchId = 0L,
                            snapshotId = null,
                            materializedEventIds = emptyList(),
                            createdEventIds = emptyList(),
                        ),
                    )
                is SessionCommand.AcknowledgeFeedDeltas ->
                    command.response?.complete(Unit)
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
                is SessionCommand.ViewportAtTop -> {
                    command.response?.complete(
                        PendingNewerFlushResult(
                            batchId = 0L,
                            snapshotId = null,
                            materializedEventIds = emptyList(),
                            createdEventIds = emptyList(),
                        ),
                    )
                }
                is SessionCommand.AcknowledgeFeedDeltas -> {
                    command.response?.complete(Unit)
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
                    val earlyFrames = earlyFramesBuffer.remove(command.connectionId)
                    earlyFrames?.forEach { earlyFrame ->
                        handleWsFrame(earlyFrame)
                    }
                    earlyFramesBuffer.keys.removeAll { it < activeConnectionId }
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
                    val earlyFrames = earlyFramesBuffer.remove(command.connectionId)
                    earlyFrames?.forEach { earlyFrame ->
                        handleWsFrame(earlyFrame)
                    }
                    earlyFramesBuffer.keys.removeAll { it < activeConnectionId }
                }
            }
            is SessionCommand.WsFrameReceived -> {
                val (connId, frame) = command.envelope
                if (activeConnectionId == 0L) {
                    activeConnectionId = connId
                    handleWsFrame(frame)
                } else if (connId == activeConnectionId) {
                    handleWsFrame(frame)
                } else if (connId > activeConnectionId) {
                    val list = earlyFramesBuffer.getOrPut(connId) { mutableListOf() }
                    if (list.size < 128) {
                        list.add(frame)
                    }
                } else {
                    return
                }
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
                    currentTotalCount = 0
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
                            pendingDeltas = emptyList(),
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
                if (!_state.value.access.eventsRead || accessFence.value.denied) {
                    command.response?.complete(
                        PendingNewerFlushResult(
                            batchId = 0L,
                            snapshotId = null,
                            materializedEventIds = emptyList(),
                            createdEventIds = emptyList(),
                        ),
                    )
                    return
                }
                // User navigated to top: integrate all pending newer events into visible feed
                val pendingNewerEntries =
                    currentOverlay.entries
                        .filterValues { it.membership == OverlayMembership.PENDING_NEWER }

                val createdIds =
                    pendingNewerEntries
                        .filterValues { it.origin == RealtimeEventOrigin.CREATED }
                        .keys
                        .toList()

                val updatedIds =
                    pendingNewerEntries
                        .filterValues { it.origin == RealtimeEventOrigin.UPDATED }
                        .keys
                        .toList()

                val allPendingIds = pendingNewerEntries.keys.toList()

                currentOverlay = RealtimeOverlayReducer.integratePendingNewer(currentOverlay)
                val visible = RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
                val (newerCount, historyCount) = RealtimeOverlayReducer.computePendingCounts(currentOverlay)

                val deltaBatchId = if (allPendingIds.isNotEmpty()) ++feedDeltaBatchCounter else 0L
                val delta =
                    if (allPendingIds.isNotEmpty()) {
                        EventsFeedDelta(
                            batchId = deltaBatchId,
                            tenantSequence = sequenceProcessor.currentSequence,
                            createdEventIds = createdIds,
                            updatedEventIds = updatedIds,
                            removedEventIds = emptyList(),
                        )
                    } else {
                        null
                    }

                _state.update {
                    it.copy(
                        visibleEvents = visible,
                        realtimeOverlay = currentOverlay,
                        snapshotTotalCount = currentTotalCount,
                        pendingNewerCount = newerCount,
                        pendingHistoryCount = historyCount,
                        pendingDeltas = appendFeedDelta(it.pendingDeltas, delta),
                    )
                }

                command.response?.complete(
                    PendingNewerFlushResult(
                        batchId = deltaBatchId,
                        snapshotId = _state.value.snapshotId,
                        materializedEventIds = allPendingIds,
                        createdEventIds = createdIds,
                    ),
                )
            }
            is SessionCommand.AcknowledgeFeedDeltas -> {
                _state.update { current ->
                    val remaining = current.pendingDeltas.filter { it.batchId > command.batchId }
                    current.copy(pendingDeltas = remaining)
                }
                command.response?.complete(Unit)
            }
            is SessionCommand.Close -> {
                commandChannel.close()
                priorityCommandChannel.close()
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
        var candidateTotalCount = bootstrap.page.totalCount
        val optionsToNotify = mutableListOf<EventsFilterOptionsDto>()

        // If rebootstrapping, apply the verified commits to candidate overlay & summary
        if (isRebootstrapping && deduplicatedCommits.isNotEmpty()) {
            val workingEvents = mutableMapOf<String, Event>()
            for (item in candidateSnapshotItems) {
                workingEvents[item.id] = item
            }
            for (entry in candidateOverlay.entries.values) {
                workingEvents[entry.eventId] = entry.event
            }

            var countStale = false
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

                    val previous = workingEvents[event.id]
                    val wasMatching = previous?.let { RealtimeOverlayReducer.matchesFilter(it, query.filter) } ?: false
                    val isMatching = RealtimeOverlayReducer.matchesFilter(event, query.filter)
                    if (!wasMatching && isMatching) {
                        candidateTotalCount++
                    } else if (wasMatching && !isMatching) {
                        candidateTotalCount = maxOf(0, candidateTotalCount - 1)
                    }
                    workingEvents[event.id] = event
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

                    val previous = workingEvents[event.id]
                    if (previous == null) {
                        countStale = true
                    } else {
                        val wasMatching = RealtimeOverlayReducer.matchesFilter(previous, query.filter)
                        val isMatching = RealtimeOverlayReducer.matchesFilter(event, query.filter)
                        if (!wasMatching && isMatching) {
                            candidateTotalCount++
                        } else if (wasMatching && !isMatching) {
                            candidateTotalCount = maxOf(0, candidateTotalCount - 1)
                        }
                    }
                    workingEvents[event.id] = event
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

            if (countStale) {
                candidateOverlay = candidateOverlay.copy(requiresRefresh = true)
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
        currentTotalCount = candidateTotalCount

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
                snapshotTotalCount = currentTotalCount,
                hasMore = bootstrap.page.hasMore,
                isInitialLoading = false,
                initialLoadError = null,
                summary = candidateSummary,
                pendingNewerCount = newerCount,
                pendingHistoryCount = historyCount,
                pendingDeltas = emptyList(),
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

        val wasMatching =
            when {
                existingEntry != null -> existingEntry.membership != OverlayMembership.REMOVED
                currentEvent != null -> RealtimeOverlayReducer.matchesFilter(currentEvent, query.filter)
                else -> false
            }
        val isMatching = RealtimeOverlayReducer.matchesFilter(finalEvent, query.filter)
        if (!wasMatching && isMatching) {
            currentTotalCount++
        } else if (wasMatching && !isMatching) {
            currentTotalCount = maxOf(0, currentTotalCount - 1)
        }

        val membership =
            when {
                !isMatching -> OverlayMembership.REMOVED
                existingEntry != null -> existingEntry.membership
                else -> OverlayMembership.INCLUDED
            }

        val idx = snapshotItems.indexOfFirst { it.id == finalEvent.id }
        if (idx != -1) {
            snapshotItems[idx] = finalEvent
        }
        val updatedEntries =
            currentOverlay.entries +
                (
                    finalEvent.id to
                        (
                            existingEntry?.copy(
                                event = finalEvent,
                                eventVersion = finalEvent.eventVersion,
                                membership = membership,
                            ) ?: OverlayEntry(
                                eventId = finalEvent.id,
                                event = finalEvent,
                                eventVersion = finalEvent.eventVersion,
                                membership = membership,
                            )
                        )
                )

        currentOverlay = currentOverlay.copy(entries = updatedEntries)
        val visible = RealtimeOverlayReducer.computeVisibleEvents(snapshotItems, currentOverlay, query)
        val (newerCount, historyCount) = RealtimeOverlayReducer.computePendingCounts(currentOverlay)
        _state.update {
            it.copy(
                visibleEvents = visible,
                realtimeOverlay = currentOverlay,
                snapshotTotalCount = currentTotalCount,
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

        val wasMatching =
            when {
                existingEntry != null -> existingEntry.membership != OverlayMembership.REMOVED
                currentEvent != null -> RealtimeOverlayReducer.matchesFilter(currentEvent, query.filter)
                else -> false
            }
        val isMatching = RealtimeOverlayReducer.matchesFilter(finalEvent, query.filter)
        if (!wasMatching && isMatching) {
            currentTotalCount++
        } else if (wasMatching && !isMatching) {
            currentTotalCount = maxOf(0, currentTotalCount - 1)
        }

        val membership =
            when {
                !isMatching -> OverlayMembership.REMOVED
                existingEntry != null -> existingEntry.membership
                else -> OverlayMembership.INCLUDED
            }

        val idx = snapshotItems.indexOfFirst { it.id == finalEvent.id }
        if (idx != -1) {
            snapshotItems[idx] = finalEvent
        }
        val updatedEntries =
            currentOverlay.entries +
                (
                    finalEvent.id to
                        (
                            existingEntry?.copy(
                                event = finalEvent,
                                eventVersion = finalEvent.eventVersion,
                                membership = membership,
                            ) ?: OverlayEntry(
                                eventId = finalEvent.id,
                                event = finalEvent,
                                eventVersion = finalEvent.eventVersion,
                                membership = membership,
                            )
                        )
                )

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
                snapshotTotalCount = currentTotalCount,
                pendingNewerCount = newerCount,
                pendingHistoryCount = historyCount,
            )
        }
    }

    private suspend fun handleWsFrame(frame: IncomingWsFrame) {
        if (frame is IncomingWsFrame.AccessInvalidated) {
            isAccessInvalidated = true
            isRebootstrapping = false
            bufferReplayId = null
            snapshotItems.clear()
            rebootstrapBuffer.clear()
            currentOverlay = RealtimeOverlay()
            currentTotalCount = 0
            onAccessInvalidated(frame.frame.reason ?: "UNKNOWN", frame.frame.companyId)
            _state.update {
                it.copy(
                    access = it.access.copy(eventsRead = false),
                    connectionState = RealtimeConnectionState.FAILED,
                    visibleEvents = emptyList(),
                    realtimeOverlay = RealtimeOverlay(),
                    summary = null,
                    snapshotTotalCount = 0,
                    hasMore = false,
                    asOfSequence = 0L,
                    pendingNewerCount = 0,
                    pendingHistoryCount = 0,
                    pendingDeltas = emptyList(),
                )
            }
            return
        }

        if (frame is IncomingWsFrame.AuthRequired) {
            when (frame.frame.reason) {
                "TOKEN_EXPIRED" -> onTokenRefreshNeeded()
                "SESSION_REVOKED" -> onSessionRevoked()
                else -> {
                    _state.update { it.copy(connectionState = RealtimeConnectionState.FAILED) }
                }
            }
            return
        }

        if (isAccessInvalidated || !_state.value.access.eventsRead || accessFence.value.denied) return

        when (frame) {
            is IncomingWsFrame.AccessInvalidated,
            is IncomingWsFrame.AuthRequired,
            -> Unit
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

        val createdIds = mutableListOf<String>()
        val updatedIds = mutableSetOf<String>()
        val removedIds = mutableSetOf<String>()

        if (createdDto != null) {
            val event = EventsDtoMapper.mapEvent(createdDto)
            val existingEntry = tentativeOverlay.entries[event.id]
            val existingInSnapshot = snapshotItems.firstOrNull { it.id == event.id }
            val currentEvent = existingEntry?.event ?: existingInSnapshot
            val wasMatching =
                when {
                    existingEntry != null -> existingEntry.membership != OverlayMembership.REMOVED
                    currentEvent != null -> RealtimeOverlayReducer.matchesFilter(currentEvent, query.filter)
                    else -> false
                }

            val (newOverlay, membership) =
                RealtimeOverlayReducer.applyEventCommit(
                    currentOverlay = tentativeOverlay,
                    event = event,
                    query = query,
                    snapshotItems = snapshotItems,
                    isEventCreated = true,
                    hasMore = currentHasMore,
                )
            tentativeOverlay = newOverlay
            if (membership == OverlayMembership.INCLUDED) {
                createdIds.add(event.id)
            } else if (membership == OverlayMembership.REMOVED) {
                removedIds.add(event.id)
            }
            if (!tentativeOverlay.requiresRefresh) {
                val isMatching =
                    membership != OverlayMembership.REMOVED && RealtimeOverlayReducer.matchesFilter(event, query.filter)
                if (!wasMatching && isMatching) {
                    currentTotalCount++
                } else if (wasMatching && !isMatching) {
                    currentTotalCount = maxOf(0, currentTotalCount - 1)
                }
            }
        }

        if (updatedDto != null) {
            val event = EventsDtoMapper.mapEvent(updatedDto)
            val existingEntry = tentativeOverlay.entries[event.id]
            val existingInSnapshot = snapshotItems.firstOrNull { it.id == event.id }
            val currentEvent = existingEntry?.event ?: existingInSnapshot
            if (currentEvent == null) {
                // Event is not in snapshot and not in overlay: previous state is unknown!
                // Trigger rebootstrap so that currentTotalCount is refreshed accurately from the backend
                tentativeOverlay = tentativeOverlay.copy(requiresRefresh = true)
            } else {
                val wasMatching =
                    if (existingEntry != null) {
                        existingEntry.membership != OverlayMembership.REMOVED
                    } else {
                        RealtimeOverlayReducer.matchesFilter(currentEvent, query.filter)
                    }

                val (newOverlay, membership) =
                    RealtimeOverlayReducer.applyEventCommit(
                        currentOverlay = tentativeOverlay,
                        event = event,
                        query = query,
                        snapshotItems = snapshotItems,
                        isEventCreated = false,
                        hasMore = currentHasMore,
                    )
                tentativeOverlay = newOverlay
                if (membership == OverlayMembership.REMOVED) {
                    removedIds.add(event.id)
                } else {
                    updatedIds.add(event.id)
                }
                if (!tentativeOverlay.requiresRefresh) {
                    val isMatching =
                        membership != OverlayMembership.REMOVED &&
                            RealtimeOverlayReducer.matchesFilter(event, query.filter)
                    if (!wasMatching && isMatching) {
                        currentTotalCount++
                    } else if (wasMatching && !isMatching) {
                        currentTotalCount = maxOf(0, currentTotalCount - 1)
                    }
                }
            }
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

        val delta =
            if (createdIds.isNotEmpty() || updatedIds.isNotEmpty() || removedIds.isNotEmpty()) {
                val distinctCreated = createdIds.distinct()
                val distinctUpdated = updatedIds.distinct().filter { it !in distinctCreated }
                val distinctRemoved = removedIds.distinct().filter { it !in distinctCreated && it !in distinctUpdated }
                EventsFeedDelta(
                    batchId = ++feedDeltaBatchCounter,
                    tenantSequence = sequenceProcessor.currentSequence,
                    createdEventIds = distinctCreated,
                    updatedEventIds = distinctUpdated,
                    removedEventIds = distinctRemoved,
                )
            } else {
                null
            }

        _state.update {
            it.copy(
                visibleEvents = visible,
                realtimeOverlay = currentOverlay,
                summary = updatedSummary,
                snapshotTotalCount = currentTotalCount,
                asOfSequence = sequenceProcessor.currentSequence,
                replayId = sequenceProcessor.currentReplayId,
                pendingNewerCount = newerCount,
                pendingHistoryCount = historyCount,
                pendingDeltas = appendFeedDelta(it.pendingDeltas, delta),
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

    internal fun getActiveConnectionIdForTest(): Long = activeConnectionId

    internal fun getActiveAuthGenerationIdForTest(): Long = activeAuthGenerationId
}
