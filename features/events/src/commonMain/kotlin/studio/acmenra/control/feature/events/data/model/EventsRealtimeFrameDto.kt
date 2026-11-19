package studio.acmenra.control.feature.events.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val MAX_SAFE_JS_INTEGER = 9_007_199_254_740_991L

@Serializable
data class AuthFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "AUTH",
    @SerialName("access_token") val accessToken: String,
    @SerialName("replay_id") val replayId: Long,
    @SerialName("after_sequence") val afterSequence: Long,
)

@Serializable
data class ReplayRequestFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "REPLAY_REQUEST",
    @SerialName("replay_id") val replayId: Long,
    @SerialName("after_sequence") val afterSequence: Long,
)

@Serializable
data class HeartbeatFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "HEARTBEAT",
    @SerialName("client_confirmed_seq") val clientConfirmedSeq: Long,
)

@Serializable
data class TenantCommitFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "TENANT_COMMIT",
    @SerialName("replay_id") val replayId: Long,
    @SerialName("tenant_sequence") val tenantSequence: Long,
    @SerialName("payload_sha256") val payloadSha256: String? = null,
    @SerialName("event_created") val eventCreated: EventDto? = null,
    @SerialName("event_updated") val eventUpdated: EventDto? = null,
    @SerialName("summary_updated") val summaryUpdated: EventsSummaryDto? = null,
    @SerialName("filter_options_updated") val filterOptionsUpdated: EventsFilterOptionsDto? = null,
)

@Serializable
data class SubscribedFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "SUBSCRIBED",
    @SerialName("replay_id") val replayId: Long,
    @SerialName("replay_from_sequence") val replayFromSequence: Long? = null,
    @SerialName("replay_until_sequence") val replayUntilSequence: Long? = null,
    @SerialName("replay_empty") val replayEmpty: Boolean = false,
)

@Serializable
data class ReplayCompletedFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "REPLAY_COMPLETED",
    @SerialName("replay_id") val replayId: Long,
    @SerialName("through_sequence") val throughSequence: Long,
)

@Serializable
data class ResyncRequiredFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "RESYNC_REQUIRED",
    @SerialName("replay_id") val replayId: Long,
    @SerialName("reason") val reason: String? = null,
    @SerialName("current_sequence") val currentSequence: Long? = null,
)

@Serializable
data class HeartbeatAckFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "HEARTBEAT_ACK",
    @SerialName("replay_id") val replayId: Long,
    @SerialName("replay_base_seq") val replayBaseSeq: Long? = null,
    @SerialName("last_replay_written_seq") val lastReplayWrittenSeq: Long? = null,
    @SerialName("last_socket_written_seq") val lastSocketWrittenSeq: Long? = null,
    @SerialName("client_confirmed_seq") val clientConfirmedSeq: Long? = null,
)

@Serializable
data class AccessCheckUnavailableFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "ACCESS_CHECK_UNAVAILABLE",
    @SerialName("replay_id") val replayId: Long,
    @SerialName("retry_after_ms") val retryAfterMs: Long = 1000L,
)

@Serializable
data class AuthRequiredFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "AUTH_REQUIRED",
    @SerialName("reason") val reason: String? = null,
)

@Serializable
data class AccessInvalidatedFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "ACCESS_INVALIDATED",
    @SerialName("reason") val reason: String? = null,
    @SerialName("company_id") val companyId: String? = null,
)

@Serializable
data class GenericRealtimeFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String,
    @SerialName("replay_id") val replayId: Long? = null,
)
