package studio.acmenra.control.feature.events.data.realtime

import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventIconKey
import kotlin.time.Instant

object EventMerger {
    fun merge(
        current: Event,
        incoming: Event,
    ): Event {
        val classificationPart =
            if (incoming.classificationVersion >= current.classificationVersion) {
                ClassificationFields(
                    classificationVersion = incoming.classificationVersion,
                    isErroneous = incoming.isErroneous,
                    eventTypeId = incoming.eventTypeId,
                    eventTypeName = incoming.eventTypeName,
                    categoryCode = incoming.categoryCode,
                    iconKey = incoming.iconKey,
                )
            } else {
                ClassificationFields(
                    classificationVersion = current.classificationVersion,
                    isErroneous = current.isErroneous,
                    eventTypeId = current.eventTypeId,
                    eventTypeName = current.eventTypeName,
                    categoryCode = current.categoryCode,
                    iconKey = current.iconKey,
                )
            }

        val acknowledgementPart =
            if (incoming.acknowledgementVersion >= current.acknowledgementVersion) {
                AcknowledgementFields(
                    acknowledgementVersion = incoming.acknowledgementVersion,
                    status = incoming.status,
                    acknowledgedAt = incoming.acknowledgedAt,
                    acknowledgedBy = incoming.acknowledgedBy,
                )
            } else {
                AcknowledgementFields(
                    acknowledgementVersion = current.acknowledgementVersion,
                    status = current.status,
                    acknowledgedAt = current.acknowledgedAt,
                    acknowledgedBy = current.acknowledgedBy,
                )
            }

        val baseEvent = if (incoming.eventVersion >= current.eventVersion) incoming else current

        return baseEvent.copy(
            eventVersion = maxOf(current.eventVersion, incoming.eventVersion),
            classificationVersion = classificationPart.classificationVersion,
            isErroneous = classificationPart.isErroneous,
            eventTypeId = classificationPart.eventTypeId,
            eventTypeName = classificationPart.eventTypeName,
            categoryCode = classificationPart.categoryCode,
            iconKey = classificationPart.iconKey,
            acknowledgementVersion = acknowledgementPart.acknowledgementVersion,
            status = acknowledgementPart.status,
            acknowledgedAt = acknowledgementPart.acknowledgedAt,
            acknowledgedBy = acknowledgementPart.acknowledgedBy,
        )
    }

    private data class ClassificationFields(
        val classificationVersion: Long,
        val isErroneous: Boolean,
        val eventTypeId: String,
        val eventTypeName: String,
        val categoryCode: String,
        val iconKey: EventIconKey,
    )

    private data class AcknowledgementFields(
        val acknowledgementVersion: Long,
        val status: EventAcknowledgementStatus,
        val acknowledgedAt: Instant?,
        val acknowledgedBy: String?,
    )
}
