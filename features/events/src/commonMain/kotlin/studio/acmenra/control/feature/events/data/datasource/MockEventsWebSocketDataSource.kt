package studio.acmenra.control.feature.events.data.datasource

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.acmenra.control.feature.events.data.model.HeartbeatAckFrameDto
import studio.acmenra.control.feature.events.data.model.ReplayCompletedFrameDto
import studio.acmenra.control.feature.events.data.model.SubscribedFrameDto

class MockEventsWebSocketDataSource(
    private val dataStore: MockEventsDataStore = MockEventsDataStore(),
) : EventsWebSocketDataSource {
    private val _incomingFrames = MutableSharedFlow<IncomingWsEnvelope>(extraBufferCapacity = 64)
    override val incomingFrames: Flow<IncomingWsEnvelope> = _incomingFrames.asSharedFlow()

    private var nextConnectionId = 1L
    private var activeJob: Job? = null
    private var activeConnectionId: Long = 0L
    private var activeReplayId: Long = 1L
    private var activeCompanyId: String = "comp-001"

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
        val connectionId = nextConnectionId++
        activeConnectionId = connectionId
        activeReplayId = replayId
        activeCompanyId = companyId
        activeJob?.cancel()

        activeJob =
            scope.launch {
                activationGate?.await()

                val missedCommits = dataStore.getCommitsAfterSequence(companyId, afterSequence)

                if (missedCommits.isEmpty()) {
                    // 1. Send Subscribed frame (empty replay)
                    _incomingFrames.emit(
                        IncomingWsEnvelope(
                            connectionId = connectionId,
                            frame =
                                IncomingWsFrame.Subscribed(
                                    SubscribedFrameDto(
                                        replayId = replayId,
                                        replayEmpty = true,
                                    ),
                                ),
                        ),
                    )
                } else {
                    // Replay missed commits
                    _incomingFrames.emit(
                        IncomingWsEnvelope(
                            connectionId = connectionId,
                            frame =
                                IncomingWsFrame.Subscribed(
                                    SubscribedFrameDto(
                                        replayId = replayId,
                                        replayEmpty = false,
                                        replayFromSequence = afterSequence + 1,
                                        replayUntilSequence = missedCommits.last().tenantSequence,
                                    ),
                                ),
                        ),
                    )
                    for (commit in missedCommits) {
                        _incomingFrames.emit(
                            IncomingWsEnvelope(
                                connectionId = connectionId,
                                frame = IncomingWsFrame.TenantCommit(commit.copy(replayId = replayId)),
                            ),
                        )
                    }
                    _incomingFrames.emit(
                        IncomingWsEnvelope(
                            connectionId = connectionId,
                            frame =
                                IncomingWsFrame.ReplayCompleted(
                                    ReplayCompletedFrameDto(
                                        replayId = replayId,
                                        throughSequence = missedCommits.last().tenantSequence,
                                    ),
                                ),
                        ),
                    )
                }

                // 2. Wait 3 seconds after opening screen
                delay(3000)

                var insertAtBeginning = true
                // 3. Every 3 seconds: add new element to beginning or middle
                while (isActive) {
                    val commit =
                        dataStore.generateAndAddRealtimeCommit(
                            companyId = companyId,
                            replayId = activeReplayId,
                            insertAtBeginning = insertAtBeginning,
                        )
                    insertAtBeginning = !insertAtBeginning

                    _incomingFrames.emit(
                        IncomingWsEnvelope(
                            connectionId = connectionId,
                            frame = IncomingWsFrame.TenantCommit(commit),
                        ),
                    )

                    delay(3000)
                }
            }

        return connectionId
    }

    override suspend fun sendReplayRequest(
        replayId: Long,
        afterSequence: Long,
    ) {
        activeReplayId = replayId
        val missedCommits = dataStore.getCommitsAfterSequence(activeCompanyId, afterSequence)
        for (commit in missedCommits) {
            _incomingFrames.emit(
                IncomingWsEnvelope(
                    connectionId = activeConnectionId,
                    frame = IncomingWsFrame.TenantCommit(commit.copy(replayId = replayId)),
                ),
            )
        }
        val throughSeq =
            if (missedCommits.isNotEmpty()) {
                missedCommits.last().tenantSequence
            } else {
                afterSequence
            }
        _incomingFrames.emit(
            IncomingWsEnvelope(
                connectionId = activeConnectionId,
                frame =
                    IncomingWsFrame.ReplayCompleted(
                        ReplayCompletedFrameDto(
                            replayId = replayId,
                            throughSequence = throughSeq,
                        ),
                    ),
            ),
        )
    }

    override suspend fun sendHeartbeat(clientConfirmedSeq: Long) {
        _incomingFrames.emit(
            IncomingWsEnvelope(
                connectionId = activeConnectionId,
                frame =
                    IncomingWsFrame.HeartbeatAck(
                        HeartbeatAckFrameDto(
                            replayId = activeReplayId,
                            replayBaseSeq = clientConfirmedSeq,
                            lastReplayWrittenSeq = clientConfirmedSeq,
                            clientConfirmedSeq = clientConfirmedSeq,
                        ),
                    ),
            ),
        )
    }

    override suspend fun disconnect(connectionId: Long?) {
        if (connectionId == null || connectionId == activeConnectionId) {
            activeJob?.cancel()
            activeJob = null
        }
    }
}
