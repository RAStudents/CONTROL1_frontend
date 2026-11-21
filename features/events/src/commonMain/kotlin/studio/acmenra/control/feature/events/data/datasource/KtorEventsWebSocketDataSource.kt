package studio.acmenra.control.feature.events.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.encodeURLPathPart
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.acmenra.control.feature.events.data.model.AccessCheckUnavailableFrameDto
import studio.acmenra.control.feature.events.data.model.AccessInvalidatedFrameDto
import studio.acmenra.control.feature.events.data.model.AuthFrameDto
import studio.acmenra.control.feature.events.data.model.AuthRequiredFrameDto
import studio.acmenra.control.feature.events.data.model.GenericRealtimeFrameDto
import studio.acmenra.control.feature.events.data.model.HeartbeatAckFrameDto
import studio.acmenra.control.feature.events.data.model.HeartbeatFrameDto
import studio.acmenra.control.feature.events.data.model.ReplayCompletedFrameDto
import studio.acmenra.control.feature.events.data.model.ReplayRequestFrameDto
import studio.acmenra.control.feature.events.data.model.ResyncRequiredFrameDto
import studio.acmenra.control.feature.events.data.model.SubscribedFrameDto
import studio.acmenra.control.feature.events.data.model.TenantCommitFrameDto

sealed interface IncomingWsFrame {
    data class Subscribed(
        val frame: SubscribedFrameDto,
    ) : IncomingWsFrame

    data class TenantCommit(
        val frame: TenantCommitFrameDto,
    ) : IncomingWsFrame

    data class ReplayCompleted(
        val frame: ReplayCompletedFrameDto,
    ) : IncomingWsFrame

    data class ResyncRequired(
        val frame: ResyncRequiredFrameDto,
    ) : IncomingWsFrame

    data class HeartbeatAck(
        val frame: HeartbeatAckFrameDto,
    ) : IncomingWsFrame

    data class AccessCheckUnavailable(
        val frame: AccessCheckUnavailableFrameDto,
    ) : IncomingWsFrame

    data class AuthRequired(
        val frame: AuthRequiredFrameDto,
    ) : IncomingWsFrame

    data class AccessInvalidated(
        val frame: AccessInvalidatedFrameDto,
    ) : IncomingWsFrame

    data class MalformedCommit(
        val replayId: Long?,
        val error: String,
    ) : IncomingWsFrame
}

data class IncomingWsEnvelope(
    val connectionId: Long,
    val frame: IncomingWsFrame,
)

enum class WsCloseReason {
    REMOTE_CLOSE,
    NETWORK_ERROR,
    HEARTBEAT_TIMEOUT,
    REPLACED,
    SESSION_CLOSED,
}

interface EventsWebSocketDataSource {
    val incomingFrames: Flow<IncomingWsEnvelope>

    fun connect(
        companyId: String,
        accessToken: String,
        replayId: Long,
        afterSequence: Long,
        scope: CoroutineScope,
        getClientConfirmedSeq: () -> Long = { afterSequence },
        activationGate: CompletableDeferred<Unit>? = null,
        onDisconnected: (reason: WsCloseReason, message: String?) -> Unit,
    ): Long

    fun activateConnection(connectionId: Long) {}

    suspend fun sendReplayRequest(
        replayId: Long,
        afterSequence: Long,
    )

    suspend fun sendHeartbeat(clientConfirmedSeq: Long)

    suspend fun disconnect(connectionId: Long? = null)
}

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
internal class HeartbeatTracker(
    val maxMissedAcks: Int = 2,
    val ackWindow: kotlin.time.Duration = kotlin.time.Duration.parse("5s"),
    private val timeSource: kotlin.time.TimeSource.WithComparableMarks = kotlin.time.TimeSource.Monotonic,
) {
    companion object {
        const val STATE_IDLE = 0
        const val STATE_SENDING = 1
        const val STATE_PENDING_ACK = 2
    }

    private val state = kotlin.concurrent.atomics.AtomicInt(STATE_IDLE)
    val missedAcks = kotlin.concurrent.atomics.AtomicInt(0)
    val hasPendingAck = kotlin.concurrent.atomics.AtomicBoolean(false)
    val pendingClientConfirmedSeq = kotlin.concurrent.atomics.AtomicLong(-1L)
    private val earlyAckAccepted = kotlin.concurrent.atomics.AtomicBoolean(false)

    @kotlin.concurrent.Volatile
    private var pendingAckSentMark: kotlin.time.ComparableTimeMark? = null

    fun onHeartbeatSending(clientConfirmedSeq: Long = -1L) {
        pendingClientConfirmedSeq.store(clientConfirmedSeq)
        earlyAckAccepted.store(false)
        state.store(STATE_SENDING)
        hasPendingAck.store(true)
    }

    fun onHeartbeatSent(
        mark: kotlin.time.ComparableTimeMark = timeSource.markNow(),
        clientConfirmedSeq: Long = -1L,
    ) {
        if (clientConfirmedSeq >= 0L) {
            pendingClientConfirmedSeq.store(clientConfirmedSeq)
        }
        if (earlyAckAccepted.compareAndSet(true, false)) {
            state.store(STATE_IDLE)
            hasPendingAck.store(false)
            pendingClientConfirmedSeq.store(-1L)
            pendingAckSentMark = null
            missedAcks.store(0)
            return
        }
        pendingAckSentMark = mark
        state.store(STATE_PENDING_ACK)
        hasPendingAck.store(true)
        if (earlyAckAccepted.compareAndSet(true, false)) {
            state.store(STATE_IDLE)
            hasPendingAck.store(false)
            pendingClientConfirmedSeq.store(-1L)
            pendingAckSentMark = null
            missedAcks.store(0)
        }
    }

    fun onSendFailed() {
        state.store(STATE_IDLE)
        hasPendingAck.store(false)
        earlyAckAccepted.store(false)
        pendingAckSentMark = null
        pendingClientConfirmedSeq.store(-1L)
    }

    fun onAckWindowTimeout(): Boolean {
        if (state.compareAndSet(STATE_PENDING_ACK, STATE_IDLE)) {
            hasPendingAck.store(false)
            pendingAckSentMark = null
            pendingClientConfirmedSeq.store(-1L)
            val missed = missedAcks.addAndFetch(1)
            if (missed >= maxMissedAcks) {
                return false
            }
        }
        return true
    }

    fun onAckReceived(
        incomingReplayId: Long,
        activeReplayId: Long,
        incomingConfirmedSeq: Long,
        mark: kotlin.time.ComparableTimeMark = timeSource.markNow(),
    ): Boolean {
        if (incomingReplayId != activeReplayId) {
            return false
        }
        if (incomingConfirmedSeq < 0L ||
            incomingConfirmedSeq > studio.acmenra.control.feature.events.data.model.MAX_SAFE_JS_INTEGER
        ) {
            return false
        }
        val expectedSeq = pendingClientConfirmedSeq.load()
        if (expectedSeq >= 0L && incomingConfirmedSeq != expectedSeq) {
            return false
        }

        val currentState = state.load()
        if (currentState == STATE_SENDING) {
            earlyAckAccepted.store(true)
            missedAcks.store(0)
            return true
        }

        if (currentState == STATE_PENDING_ACK) {
            val sentMark = pendingAckSentMark
            if (sentMark != null && (mark - sentMark) > ackWindow) {
                return false
            }
            if (state.compareAndSet(STATE_PENDING_ACK, STATE_IDLE)) {
                hasPendingAck.store(false)
                pendingAckSentMark = null
                pendingClientConfirmedSeq.store(-1L)
                missedAcks.store(0)
                return true
            }
        }

        return false
    }

    fun onTick(): Boolean = onAckWindowTimeout()
}

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class KtorEventsWebSocketDataSource(
    private val realtimeClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val wsEndpointBase: String = "",
) : EventsWebSocketDataSource {
    private sealed interface OutgoingMessage {
        val frame: Frame

        data class Regular(
            override val frame: Frame,
        ) : OutgoingMessage

        data class Heartbeat(
            override val frame: Frame,
            val clientConfirmedSeq: Long,
            val sentDeferred: CompletableDeferred<kotlin.time.ComparableTimeMark>,
        ) : OutgoingMessage
    }

    private data class ConnectionSession(
        val id: Long,
        val job: Job,
        val outgoing: Channel<OutgoingMessage>,
        val activeReplayGeneration: kotlin.concurrent.atomics.AtomicLong,
        val activationGate: CompletableDeferred<Unit>,
    )

    private val rawFramesChannel = Channel<IncomingWsEnvelope>(Channel.BUFFERED)
    override val incomingFrames: Flow<IncomingWsEnvelope> =
        rawFramesChannel
            .receiveAsFlow()
            .filter { env -> env.connectionId == activeConnectionId.load() }

    private val connectionMutex = Mutex()

    @kotlin.concurrent.Volatile
    private var currentSession: ConnectionSession? = null

    private val activeConnectionId = kotlin.concurrent.atomics.AtomicLong(0L)
    private val activationGates = mutableMapOf<Long, CompletableDeferred<Unit>>()

    internal fun emitRawFrameForTest(
        connectionId: Long,
        frame: IncomingWsFrame,
    ) {
        rawFramesChannel.trySend(IncomingWsEnvelope(connectionId, frame))
    }

    internal fun getActiveConnectionIdForTest(): Long = activeConnectionId.load()

    private fun drainFramesChannel() {
        while (rawFramesChannel.tryReceive().isSuccess) {
            // Drop stale frames from previous connection
        }
    }

    override fun activateConnection(connectionId: Long) {
        activationGates.remove(connectionId)?.complete(Unit)
        val session = currentSession
        if (session != null && session.id == connectionId) {
            session.activationGate.complete(Unit)
        }
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
        val outChan = Channel<OutgoingMessage>(Channel.BUFFERED)
        val gate = activationGate ?: CompletableDeferred<Unit>().apply { complete(Unit) }

        val connectionId = activeConnectionId.addAndFetch(1L)
        activationGates[connectionId] = gate

        scope.launch {
            connectionMutex.withLock {
                if (connectionId != activeConnectionId.load()) {
                    outChan.close()
                    return@launch
                }
                val oldSession = currentSession
                if (oldSession != null && oldSession.job.isActive) {
                    oldSession.job.cancel(CancellationException(WsCloseReason.REPLACED.name))
                    oldSession.job.join()
                    oldSession.outgoing.close()
                }
                if (connectionId != activeConnectionId.load()) {
                    outChan.close()
                    return@launch
                }
                val activeReplayGen = kotlin.concurrent.atomics.AtomicLong(replayId)
                currentSession = ConnectionSession(connectionId, coroutineContext.job, outChan, activeReplayGen, gate)
                drainFramesChannel()
            }

            val url = buildWsUrl(companyId)
            var disconnectReason = WsCloseReason.REMOTE_CLOSE
            var disconnectMessage: String? = null
            var localHeartbeatJob: Job? = null
            var localOutgoingJob: Job? = null
            try {
                realtimeClient.webSocket(urlString = url) {
                    val activeReplayGen =
                        currentSession?.activeReplayGeneration ?: kotlin.concurrent.atomics.AtomicLong(replayId)

                    // Await activation gate before sending initial AUTH frame
                    gate.await()

                    // Send AUTH frame as the very first client message
                    val authFrame =
                        AuthFrameDto(
                            accessToken = accessToken,
                            replayId = replayId,
                            afterSequence = afterSequence,
                        )
                    send(Frame.Text(json.encodeToString(authFrame)))

                    val heartbeatTracker = HeartbeatTracker()

                    // Start outgoing pump
                    localOutgoingJob =
                        launch {
                            for (msg in outChan) {
                                try {
                                    if (msg is OutgoingMessage.Heartbeat) {
                                        heartbeatTracker.onHeartbeatSending(msg.clientConfirmedSeq)
                                    }
                                    send(msg.frame)
                                    if (msg is OutgoingMessage.Heartbeat) {
                                        val sendMark =
                                            kotlin.time.TimeSource.Monotonic
                                                .markNow()
                                        // Synchronously register pending ACK before resuming heartbeat coroutine
                                        heartbeatTracker.onHeartbeatSent(
                                            mark = sendMark,
                                            clientConfirmedSeq = msg.clientConfirmedSeq,
                                        )
                                        msg.sentDeferred.complete(sendMark)
                                    }
                                } catch (e: Throwable) {
                                    if (msg is OutgoingMessage.Heartbeat) {
                                        heartbeatTracker.onSendFailed()
                                        msg.sentDeferred.completeExceptionally(e)
                                    }
                                    throw e
                                }
                            }
                        }

                    // Start Heartbeat loop: 25s cadence with 5s ACK window
                    localHeartbeatJob =
                        launch {
                            delay(25_000L)
                            while (isActive) {
                                val confirmedSeq = getClientConfirmedSeq()
                                val hbFrame = HeartbeatFrameDto(clientConfirmedSeq = confirmedSeq)
                                val sentDeferred = CompletableDeferred<kotlin.time.ComparableTimeMark>()
                                try {
                                    outChan.send(
                                        OutgoingMessage.Heartbeat(
                                            frame = Frame.Text(json.encodeToString(hbFrame)),
                                            clientConfirmedSeq = confirmedSeq,
                                            sentDeferred = sentDeferred,
                                        ),
                                    )
                                } catch (e: CancellationException) {
                                    heartbeatTracker.onSendFailed()
                                    throw e
                                } catch (_: Throwable) {
                                    heartbeatTracker.onSendFailed()
                                    break
                                }

                                val sendMark =
                                    try {
                                        sentDeferred.await()
                                    } catch (e: CancellationException) {
                                        heartbeatTracker.onSendFailed()
                                        throw e
                                    } catch (_: Throwable) {
                                        heartbeatTracker.onSendFailed()
                                        break
                                    }

                                delay(5_000L)
                                if (heartbeatTracker.hasPendingAck.load()) {
                                    val ok = heartbeatTracker.onAckWindowTimeout()
                                    if (!ok) {
                                        disconnectReason = WsCloseReason.HEARTBEAT_TIMEOUT
                                        disconnectMessage =
                                            "Heartbeat timeout (2 consecutive missed ACKs within 5s window)"
                                        close()
                                        break
                                    }
                                }
                                val elapsedMs = sendMark.elapsedNow().inWholeMilliseconds
                                val remainingMs = (25_000L - elapsedMs).coerceAtLeast(0L)
                                if (remainingMs > 0L) {
                                    delay(remainingMs)
                                }
                            }
                        }

                    // Receive loop with connectionId fencing
                    for (frame in incoming) {
                        if (connectionId != activeConnectionId.load()) break
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            val generic =
                                runCatching {
                                    json.decodeFromString<GenericRealtimeFrameDto>(text)
                                }.getOrNull() ?: continue

                            if (generic.schemaVersion != 1) continue
                            if (connectionId != activeConnectionId.load()) break

                            when (generic.type) {
                                "SUBSCRIBED" -> {
                                    val dto =
                                        runCatching {
                                            json.decodeFromString<SubscribedFrameDto>(
                                                text,
                                            )
                                        }.getOrNull()
                                    if (dto != null &&
                                        dto.schemaVersion == 1 &&
                                        dto.type == "SUBSCRIBED" &&
                                        connectionId == activeConnectionId.load()
                                    ) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(connectionId, IncomingWsFrame.Subscribed(dto)),
                                        )
                                    }
                                }
                                "TENANT_COMMIT" -> {
                                    val decodeResult = runCatching { json.decodeFromString<TenantCommitFrameDto>(text) }
                                    val frameToSend =
                                        decodeResult.fold(
                                            onSuccess = { dto ->
                                                if (dto.schemaVersion != 1 ||
                                                    dto.type != "TENANT_COMMIT" ||
                                                    !dto.validateEnvelope()
                                                ) {
                                                    IncomingWsFrame.MalformedCommit(
                                                        dto.replayId,
                                                        "Invalid commit envelope or schema_version",
                                                    )
                                                } else {
                                                    IncomingWsFrame.TenantCommit(dto)
                                                }
                                            },
                                            onFailure = { err ->
                                                IncomingWsFrame.MalformedCommit(
                                                    generic.replayId ?: 0L,
                                                    err.message ?: "Failed to decode TENANT_COMMIT",
                                                )
                                            },
                                        )
                                    if (connectionId == activeConnectionId.load()) {
                                        rawFramesChannel.send(IncomingWsEnvelope(connectionId, frameToSend))
                                    }
                                }
                                "REPLAY_COMPLETED" -> {
                                    val dto =
                                        runCatching { json.decodeFromString<ReplayCompletedFrameDto>(text) }.getOrNull()
                                    if (dto != null &&
                                        dto.schemaVersion == 1 &&
                                        dto.type == "REPLAY_COMPLETED" &&
                                        connectionId == activeConnectionId.load()
                                    ) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(connectionId, IncomingWsFrame.ReplayCompleted(dto)),
                                        )
                                    }
                                }
                                "RESYNC_REQUIRED" -> {
                                    val dto =
                                        runCatching {
                                            json.decodeFromString<ResyncRequiredFrameDto>(
                                                text,
                                            )
                                        }.getOrNull()
                                    if (dto != null &&
                                        dto.schemaVersion == 1 &&
                                        dto.type == "RESYNC_REQUIRED" &&
                                        connectionId == activeConnectionId.load()
                                    ) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(connectionId, IncomingWsFrame.ResyncRequired(dto)),
                                        )
                                    }
                                }
                                "HEARTBEAT_ACK" -> {
                                    val dto =
                                        runCatching {
                                            json.decodeFromString<HeartbeatAckFrameDto>(
                                                text,
                                            )
                                        }.getOrNull()
                                    if (dto != null && dto.schemaVersion == 1 && dto.type == "HEARTBEAT_ACK") {
                                        val activeGen = activeReplayGen.load()
                                        heartbeatTracker.onAckReceived(
                                            incomingReplayId = dto.replayId,
                                            activeReplayId = activeGen,
                                            incomingConfirmedSeq = dto.clientConfirmedSeq,
                                        )
                                        if (connectionId == activeConnectionId.load()) {
                                            rawFramesChannel.send(
                                                IncomingWsEnvelope(connectionId, IncomingWsFrame.HeartbeatAck(dto)),
                                            )
                                        }
                                    }
                                }
                                "ACCESS_CHECK_UNAVAILABLE" -> {
                                    val dto =
                                        runCatching {
                                            json.decodeFromString<AccessCheckUnavailableFrameDto>(text)
                                        }.getOrNull()
                                    if (dto != null &&
                                        dto.schemaVersion == 1 &&
                                        dto.type == "ACCESS_CHECK_UNAVAILABLE" &&
                                        connectionId == activeConnectionId.load()
                                    ) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(
                                                connectionId,
                                                IncomingWsFrame.AccessCheckUnavailable(dto),
                                            ),
                                        )
                                    }
                                }
                                "AUTH_REQUIRED" -> {
                                    val dto =
                                        runCatching {
                                            json.decodeFromString<AuthRequiredFrameDto>(
                                                text,
                                            )
                                        }.getOrNull()
                                    if (dto != null &&
                                        dto.schemaVersion == 1 &&
                                        dto.type == "AUTH_REQUIRED" &&
                                        connectionId == activeConnectionId.load()
                                    ) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(connectionId, IncomingWsFrame.AuthRequired(dto)),
                                        )
                                    }
                                }
                                "ACCESS_INVALIDATED" -> {
                                    val dto =
                                        runCatching {
                                            json.decodeFromString<AccessInvalidatedFrameDto>(text)
                                        }.getOrNull()
                                    if (dto != null &&
                                        dto.schemaVersion == 1 &&
                                        dto.type == "ACCESS_INVALIDATED" &&
                                        connectionId == activeConnectionId.load()
                                    ) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(
                                                connectionId,
                                                IncomingWsFrame.AccessInvalidated(dto),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    localOutgoingJob.cancel()
                    localHeartbeatJob.cancel()
                }
            } catch (e: CancellationException) {
                disconnectReason =
                    when (e.message) {
                        WsCloseReason.REPLACED.name -> WsCloseReason.REPLACED
                        WsCloseReason.SESSION_CLOSED.name -> WsCloseReason.SESSION_CLOSED
                        else -> WsCloseReason.SESSION_CLOSED
                    }
                disconnectMessage = "Connection cancelled: $disconnectReason"
                throw e
            } catch (e: Throwable) {
                disconnectReason = WsCloseReason.NETWORK_ERROR
                disconnectMessage = e.message ?: "Socket error"
            } finally {
                localHeartbeatJob?.cancel()
                localOutgoingJob?.cancel()
                outChan.close()
                if (currentSession?.id == connectionId) {
                    currentSession = null
                }
                onDisconnected(disconnectReason, disconnectMessage)
            }
        }
        return connectionId
    }

    override suspend fun sendReplayRequest(
        replayId: Long,
        afterSequence: Long,
    ) {
        val frame = ReplayRequestFrameDto(replayId = replayId, afterSequence = afterSequence)
        val text = json.encodeToString(frame)
        val session = currentSession ?: throw IllegalStateException("WebSocket outgoing channel is not connected")
        session.activeReplayGeneration.store(replayId)
        session.outgoing.send(OutgoingMessage.Regular(Frame.Text(text)))
    }

    override suspend fun sendHeartbeat(clientConfirmedSeq: Long) {
        val frame = HeartbeatFrameDto(clientConfirmedSeq = clientConfirmedSeq)
        val text = json.encodeToString(frame)
        try {
            currentSession?.outgoing?.send(OutgoingMessage.Regular(Frame.Text(text)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Outgoing channel closed or failed
        }
    }

    override suspend fun disconnect(connectionId: Long?) {
        connectionMutex.withLock {
            if (connectionId != null && activeConnectionId.load() != connectionId) {
                return
            }
            val sessionToDisconnect = currentSession
            if (connectionId != null && sessionToDisconnect != null && sessionToDisconnect.id != connectionId) {
                return
            }
            currentSession = null
            activeConnectionId.addAndFetch(1L)

            if (sessionToDisconnect != null) {
                if (sessionToDisconnect.job.isActive) {
                    sessionToDisconnect.job.cancel(CancellationException(WsCloseReason.SESSION_CLOSED.name))
                    sessionToDisconnect.job.join()
                }
                sessionToDisconnect.outgoing.close()
            }
            drainFramesChannel()
        }
    }

    internal fun buildWsUrl(companyId: String): String {
        val encodedCompany = companyId.encodeURLPathPart()
        val base = wsEndpointBase.trim().trimEnd('/')
        val urlWithoutApi =
            when {
                base.endsWith("/api/v1", ignoreCase = true) -> base.substring(0, base.length - "/api/v1".length)
                else -> base
            }
        val path = "api/v1/companies/$encodedCompany/events/ws"
        val fullUrl =
            if (urlWithoutApi.isBlank()) {
                path
            } else {
                "$urlWithoutApi/$path"
            }
        return when {
            fullUrl.startsWith("http://", ignoreCase = true) ->
                "ws://" + fullUrl.substring("http://".length)
            fullUrl.startsWith("https://", ignoreCase = true) ->
                "wss://" + fullUrl.substring("https://".length)
            else -> fullUrl
        }
    }
}
