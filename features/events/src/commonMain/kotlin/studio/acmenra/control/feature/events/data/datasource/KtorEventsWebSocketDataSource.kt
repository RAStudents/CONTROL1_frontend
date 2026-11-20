package studio.acmenra.control.feature.events.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
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
        onDisconnected: (reason: WsCloseReason, message: String?) -> Unit,
    ): Long

    suspend fun sendReplayRequest(
        replayId: Long,
        afterSequence: Long,
    )

    suspend fun sendHeartbeat(clientConfirmedSeq: Long)

    suspend fun disconnect(connectionId: Long? = null)
}

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class KtorEventsWebSocketDataSource(
    private val realtimeClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val wsEndpointBase: String = "",
) : EventsWebSocketDataSource {
    private data class ConnectionSession(
        val id: Long,
        val job: Job,
        val outgoing: Channel<Frame>,
    )

    private val rawFramesChannel = Channel<IncomingWsEnvelope>(Channel.BUFFERED)
    override val incomingFrames: Flow<IncomingWsEnvelope> =
        rawFramesChannel
            .receiveAsFlow()
            .filter { env -> env.connectionId == activeConnectionId.load() }

    private val connectionMutex = Mutex()

    @kotlin.concurrent.Volatile
    private var currentSession: ConnectionSession? = null

    private val connectionSequence = kotlin.concurrent.atomics.AtomicLong(0L)
    private val activeConnectionId = kotlin.concurrent.atomics.AtomicLong(0L)
    private var lastConfirmedSeq: Long = 0L

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

    override fun connect(
        companyId: String,
        accessToken: String,
        replayId: Long,
        afterSequence: Long,
        scope: CoroutineScope,
        getClientConfirmedSeq: () -> Long,
        onDisconnected: (reason: WsCloseReason, message: String?) -> Unit,
    ): Long {
        val outChan = Channel<Frame>(Channel.BUFFERED)
        lastConfirmedSeq = afterSequence

        val connectionId = connectionSequence.addAndFetch(1L)
        activeConnectionId.store(connectionId)

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
                currentSession = ConnectionSession(connectionId, coroutineContext.job, outChan)
                drainFramesChannel()
            }

            val url = buildWsUrl(companyId)
            var disconnectReason = WsCloseReason.REMOTE_CLOSE
            var disconnectMessage: String? = null
            var localHeartbeatJob: Job? = null
            var localOutgoingJob: Job? = null
            try {
                realtimeClient.webSocket(urlString = url) {
                    // Send AUTH frame as the very first client message
                    val authFrame =
                        AuthFrameDto(
                            accessToken = accessToken,
                            replayId = replayId,
                            afterSequence = afterSequence,
                        )
                    send(Frame.Text(json.encodeToString(authFrame)))

                    // Start outgoing pump
                    localOutgoingJob =
                        launch {
                            for (frame in outChan) {
                                send(frame)
                            }
                        }

                    // Start Heartbeat loop: every 25s using connection-local outChan
                    var missedAcks = 0
                    localHeartbeatJob =
                        launch {
                            while (isActive) {
                                delay(25_000L)
                                val confirmedSeq = getClientConfirmedSeq()
                                lastConfirmedSeq = confirmedSeq
                                val hbFrame = HeartbeatFrameDto(clientConfirmedSeq = confirmedSeq)
                                try {
                                    outChan.send(Frame.Text(json.encodeToString(hbFrame)))
                                    missedAcks++
                                    if (missedAcks >= 2) {
                                        disconnectReason = WsCloseReason.HEARTBEAT_TIMEOUT
                                        disconnectMessage = "Heartbeat timeout (2 missed ACKs)"
                                        close()
                                        break
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    break
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

                            if (connectionId != activeConnectionId.load()) break

                            when (generic.type) {
                                "SUBSCRIBED" -> {
                                    val dto = json.decodeFromString<SubscribedFrameDto>(text)
                                    if (connectionId == activeConnectionId.load()) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(connectionId, IncomingWsFrame.Subscribed(dto)),
                                        )
                                    }
                                }
                                "TENANT_COMMIT" -> {
                                    val dto = json.decodeFromString<TenantCommitFrameDto>(text)
                                    if (connectionId == activeConnectionId.load()) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(connectionId, IncomingWsFrame.TenantCommit(dto)),
                                        )
                                    }
                                }
                                "REPLAY_COMPLETED" -> {
                                    val dto = json.decodeFromString<ReplayCompletedFrameDto>(text)
                                    if (connectionId == activeConnectionId.load()) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(connectionId, IncomingWsFrame.ReplayCompleted(dto)),
                                        )
                                    }
                                }
                                "RESYNC_REQUIRED" -> {
                                    val dto = json.decodeFromString<ResyncRequiredFrameDto>(text)
                                    if (connectionId == activeConnectionId.load()) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(connectionId, IncomingWsFrame.ResyncRequired(dto)),
                                        )
                                    }
                                }
                                "HEARTBEAT_ACK" -> {
                                    missedAcks = 0
                                    val dto = json.decodeFromString<HeartbeatAckFrameDto>(text)
                                    if (connectionId == activeConnectionId.load()) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(connectionId, IncomingWsFrame.HeartbeatAck(dto)),
                                        )
                                    }
                                }
                                "ACCESS_CHECK_UNAVAILABLE" -> {
                                    val dto = json.decodeFromString<AccessCheckUnavailableFrameDto>(text)
                                    if (connectionId == activeConnectionId.load()) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(
                                                connectionId,
                                                IncomingWsFrame.AccessCheckUnavailable(dto),
                                            ),
                                        )
                                    }
                                }
                                "AUTH_REQUIRED" -> {
                                    val dto = json.decodeFromString<AuthRequiredFrameDto>(text)
                                    if (connectionId == activeConnectionId.load()) {
                                        rawFramesChannel.send(
                                            IncomingWsEnvelope(connectionId, IncomingWsFrame.AuthRequired(dto)),
                                        )
                                    }
                                }
                                "ACCESS_INVALIDATED" -> {
                                    val dto = json.decodeFromString<AccessInvalidatedFrameDto>(text)
                                    if (connectionId == activeConnectionId.load()) {
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
        session.outgoing.send(Frame.Text(text))
    }

    override suspend fun sendHeartbeat(clientConfirmedSeq: Long) {
        lastConfirmedSeq = clientConfirmedSeq
        val frame = HeartbeatFrameDto(clientConfirmedSeq = clientConfirmedSeq)
        val text = json.encodeToString(frame)
        try {
            currentSession?.outgoing?.send(Frame.Text(text))
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
            val nextSeq = connectionSequence.addAndFetch(1L)
            activeConnectionId.store(nextSeq)

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

    private fun buildWsUrl(companyId: String): String {
        val path = "api/v1/companies/$companyId/events/ws"
        val fullUrl =
            if (wsEndpointBase.isBlank()) {
                path
            } else {
                "${wsEndpointBase.trimEnd('/')}/$path"
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
