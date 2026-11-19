package studio.acmenra.control.feature.events.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

const val MAX_SAFE_JS_INTEGER = 9_007_199_254_740_991L

object SafeJsLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SafeJsLong", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: Long,
    ) {
        if (value < 0L || value > MAX_SAFE_JS_INTEGER) {
            throw SerializationException("Safe JS Integer overflow: $value")
        }
        encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            val raw = element.jsonPrimitive.content
            if (raw.contains('.') || raw.contains('e', ignoreCase = true) || raw.contains('E')) {
                throw SerializationException("Float/exponent not allowed for safe integer: $raw")
            }
            val value =
                raw.toLongOrNull()
                    ?: throw SerializationException("Invalid safe integer: $raw")
            if (value < 0L || value > MAX_SAFE_JS_INTEGER) {
                throw SerializationException("Safe JS Integer out of range [0, $MAX_SAFE_JS_INTEGER]: $value")
            }
            return value
        }
        val value = decoder.decodeLong()
        if (value < 0L || value > MAX_SAFE_JS_INTEGER) {
            throw SerializationException("Safe JS Integer out of range [0, $MAX_SAFE_JS_INTEGER]: $value")
        }
        return value
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object SafeJsNullableLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SafeJsNullableLong", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: Long?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            SafeJsLongSerializer.serialize(encoder, value)
        }
    }

    override fun deserialize(decoder: Decoder): Long? {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonNull) return null
            val raw = element.jsonPrimitive.content
            if (raw.contains('.') || raw.contains('e', ignoreCase = true) || raw.contains('E')) {
                throw SerializationException("Float/exponent not allowed for safe integer: $raw")
            }
            val value =
                raw.toLongOrNull()
                    ?: throw SerializationException("Invalid safe integer: $raw")
            if (value < 0L || value > MAX_SAFE_JS_INTEGER) {
                throw SerializationException("Safe JS Integer out of range [0, $MAX_SAFE_JS_INTEGER]: $value")
            }
            return value
        }
        return if (decoder.decodeNotNullMark()) SafeJsLongSerializer.deserialize(decoder) else decoder.decodeNull()
    }
}

@Serializable
data class AuthFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "AUTH",
    @SerialName("access_token") val accessToken: String,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("replay_id") val replayId: Long,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("after_sequence") val afterSequence: Long,
)

@Serializable
data class ReplayRequestFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "REPLAY_REQUEST",
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("replay_id") val replayId: Long,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("after_sequence") val afterSequence: Long,
)

@Serializable
data class HeartbeatFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "HEARTBEAT",
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("client_confirmed_seq") val clientConfirmedSeq: Long,
)

@Serializable
data class TenantCommitFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "TENANT_COMMIT",
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("replay_id") val replayId: Long,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("tenant_sequence") val tenantSequence: Long,
    @SerialName("payload_sha256") val payloadSha256: String? = null,
    @SerialName("event_created") val eventCreated: EventDto? = null,
    @SerialName("event_updated") val eventUpdated: EventDto? = null,
    @SerialName("summary_updated") val summaryUpdated: EventsSummaryDto? = null,
    @SerialName("filter_options_updated") val filterOptionsUpdated: EventsFilterOptionsDto? = null,
) {
    fun validateEnvelope(): Boolean {
        val hasPayload =
            eventCreated != null || eventUpdated != null || summaryUpdated != null || filterOptionsUpdated != null
        val bothEvents = eventCreated != null && eventUpdated != null
        return hasPayload && !bothEvents
    }
}

@Serializable
data class SubscribedFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "SUBSCRIBED",
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("replay_id") val replayId: Long,
    @Serializable(with = SafeJsNullableLongSerializer::class)
    @SerialName("replay_from_sequence") val replayFromSequence: Long? = null,
    @Serializable(with = SafeJsNullableLongSerializer::class)
    @SerialName("replay_until_sequence") val replayUntilSequence: Long? = null,
    @SerialName("replay_empty") val replayEmpty: Boolean = false,
)

@Serializable
data class ReplayCompletedFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "REPLAY_COMPLETED",
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("replay_id") val replayId: Long,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("through_sequence") val throughSequence: Long,
)

@Serializable
data class ResyncRequiredFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "RESYNC_REQUIRED",
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("replay_id") val replayId: Long,
    @SerialName("reason") val reason: String? = null,
    @Serializable(with = SafeJsNullableLongSerializer::class)
    @SerialName("current_sequence") val currentSequence: Long? = null,
)

@Serializable
data class HeartbeatAckFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "HEARTBEAT_ACK",
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("replay_id") val replayId: Long,
    @Serializable(with = SafeJsNullableLongSerializer::class)
    @SerialName("replay_base_seq") val replayBaseSeq: Long? = null,
    @Serializable(with = SafeJsNullableLongSerializer::class)
    @SerialName("last_replay_written_seq") val lastReplayWrittenSeq: Long? = null,
    @Serializable(with = SafeJsNullableLongSerializer::class)
    @SerialName("last_socket_written_seq") val lastSocketWrittenSeq: Long? = null,
    @Serializable(with = SafeJsNullableLongSerializer::class)
    @SerialName("client_confirmed_seq") val clientConfirmedSeq: Long? = null,
)

@Serializable
data class AccessCheckUnavailableFrameDto(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("type") val type: String = "ACCESS_CHECK_UNAVAILABLE",
    @Serializable(with = SafeJsLongSerializer::class)
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
    @Serializable(with = SafeJsNullableLongSerializer::class)
    @SerialName("replay_id") val replayId: Long? = null,
)
