package studio.acmenra.control.feature.events.data.realtime

import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
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
                    erroneousMarkedAt = incoming.erroneousMarkedAt,
                    erroneousMarkedBy = incoming.erroneousMarkedBy,
                )
            } else {
                ClassificationFields(
                    classificationVersion = current.classificationVersion,
                    isErroneous = current.isErroneous,
                    erroneousMarkedAt = current.erroneousMarkedAt,
                    erroneousMarkedBy = current.erroneousMarkedBy,
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

        val displaySource = if (incoming.eventVersion >= current.eventVersion) incoming else current

        return current.copy(
            eventVersion = maxOf(current.eventVersion, incoming.eventVersion),
            eventTypeName = displaySource.eventTypeName,
            locationName = displaySource.locationName,
            deviceName = displaySource.deviceName,
            iconKey = displaySource.iconKey,
            classificationVersion = classificationPart.classificationVersion,
            isErroneous = classificationPart.isErroneous,
            erroneousMarkedAt = classificationPart.erroneousMarkedAt,
            erroneousMarkedBy = classificationPart.erroneousMarkedBy,
            acknowledgementVersion = acknowledgementPart.acknowledgementVersion,
            status = acknowledgementPart.status,
            acknowledgedAt = acknowledgementPart.acknowledgedAt,
            acknowledgedBy = acknowledgementPart.acknowledgedBy,
        )
    }

    private data class ClassificationFields(
        val classificationVersion: Long,
        val isErroneous: Boolean,
        val erroneousMarkedAt: Instant?,
        val erroneousMarkedBy: String?,
    )

    private data class AcknowledgementFields(
        val acknowledgementVersion: Long,
        val status: EventAcknowledgementStatus,
        val acknowledgedAt: Instant?,
        val acknowledgedBy: String?,
    )
}
