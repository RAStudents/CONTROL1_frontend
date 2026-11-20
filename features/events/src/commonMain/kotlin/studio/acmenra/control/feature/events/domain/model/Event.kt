package studio.acmenra.control.feature.events.domain.model

import kotlin.time.Instant

enum class EventValidityFilter {
    ALL,
    VALID,
    ERRONEOUS,
}

enum class EventsSort {
    OCCURRED_AT_DESC,
}

enum class EventAcknowledgementStatus {
    NEW,
    ACKNOWLEDGED,
    LEGACY_UNKNOWN,
    UNRECOGNIZED,
}

enum class ScoreTrend {
    IMPROVING,
    WORSENING,
    STABLE,
    UNKNOWN,
}

enum class EventIconKey {
    HELMET,
    PHONE,
    SLEEP,
    DEFECT,
    PERSON,
    GENERIC_ALERT,
}

data class EventsAccessCapabilities(
    val eventsRead: Boolean = false,
    val eventsReview: Boolean = false,
    val reportsExport: Boolean = false,
) {
    val canAcknowledge: Boolean get() = eventsRead && eventsReview
    val canExport: Boolean get() = eventsRead && reportsExport
}

data class Event(
    val id: String,
    val eventVersion: Long,
    val classificationVersion: Long,
    val acknowledgementVersion: Long,
    val eventTypeId: String,
    val eventTypeName: String,
    val categoryCode: String,
    val iconKey: EventIconKey,
    val locationId: String,
    val locationName: String,
    val deviceId: String,
    val deviceName: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val status: EventAcknowledgementStatus,
    val acknowledgedAt: Instant?,
    val acknowledgedBy: String?,
    val isErroneous: Boolean,
    val previewUrl: String,
    val videoUrl: String?,
) {
    fun canAcknowledge(access: EventsAccessCapabilities): Boolean =
        access.eventsRead &&
            access.eventsReview &&
            (
                status == EventAcknowledgementStatus.NEW ||
                    status == EventAcknowledgementStatus.LEGACY_UNKNOWN
            )

    fun isVisuallyRead(pendingAckIds: Set<String>): Boolean =
        status == EventAcknowledgementStatus.ACKNOWLEDGED ||
            status == EventAcknowledgementStatus.LEGACY_UNKNOWN ||
            status == EventAcknowledgementStatus.UNRECOGNIZED ||
            id in pendingAckIds
}
