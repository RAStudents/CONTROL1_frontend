package studio.acmenra.control.feature.events.data.realtime

import studio.acmenra.control.feature.events.data.model.MAX_SAFE_JS_INTEGER
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import kotlin.time.Instant

sealed interface EventMergeResult {
    data class Success(
        val merged: Event,
    ) : EventMergeResult

    data object Stale : EventMergeResult

    data class IntegrityViolation(
        val reason: String,
    ) : EventMergeResult
}

object EventMerger {
    fun validateAndMerge(
        current: Event,
        incoming: Event,
    ): EventMergeResult {
        // 1. Invariant bounds check: versions must be in 1..MAX_SAFE_JS_INTEGER
        if (incoming.eventVersion !in 1..MAX_SAFE_JS_INTEGER ||
            incoming.classificationVersion !in 1..MAX_SAFE_JS_INTEGER ||
            incoming.acknowledgementVersion !in 1..MAX_SAFE_JS_INTEGER ||
            current.eventVersion !in 1..MAX_SAFE_JS_INTEGER ||
            current.classificationVersion !in 1..MAX_SAFE_JS_INTEGER ||
            current.acknowledgementVersion !in 1..MAX_SAFE_JS_INTEGER
        ) {
            return EventMergeResult.IntegrityViolation(
                "Version out of bounds [1, $MAX_SAFE_JS_INTEGER]: " +
                    "incoming(ev=${incoming.eventVersion}, cl=${incoming.classificationVersion}, " +
                    "ack=${incoming.acknowledgementVersion}), " +
                    "current(ev=${current.eventVersion}, cl=${current.classificationVersion}, " +
                    "ack=${current.acknowledgementVersion})",
            )
        }

        // 2. Invariant I.2.1: specialized versions must not exceed overall eventVersion
        if (incoming.classificationVersion > incoming.eventVersion ||
            incoming.acknowledgementVersion > incoming.eventVersion
        ) {
            return EventMergeResult.IntegrityViolation(
                "Incoming specialized version exceeds eventVersion: " +
                    "ev=${incoming.eventVersion}, cl=${incoming.classificationVersion}, ack=${incoming.acknowledgementVersion}",
            )
        }
        if (current.classificationVersion > current.eventVersion ||
            current.acknowledgementVersion > current.eventVersion
        ) {
            return EventMergeResult.IntegrityViolation(
                "Current specialized version exceeds eventVersion: " +
                    "ev=${current.eventVersion}, cl=${current.classificationVersion}, ack=${current.acknowledgementVersion}",
            )
        }

        // 3. Immutable core identity validation
        if (current.id != incoming.id ||
            current.eventTypeId != incoming.eventTypeId ||
            current.categoryCode != incoming.categoryCode ||
            current.locationId != incoming.locationId ||
            current.deviceId != incoming.deviceId ||
            current.occurredAt != incoming.occurredAt ||
            current.receivedAt != incoming.receivedAt ||
            current.previewUrl != incoming.previewUrl ||
            current.videoUrl != incoming.videoUrl
        ) {
            return EventMergeResult.IntegrityViolation(
                "Immutable core mismatch for event ${incoming.id}",
            )
        }

        // 4. If incoming eventVersion is smaller than current: strictly reject
        if (incoming.eventVersion < current.eventVersion) {
            if (incoming.classificationVersion > current.classificationVersion ||
                incoming.acknowledgementVersion > current.acknowledgementVersion
            ) {
                return EventMergeResult.IntegrityViolation(
                    "Contradictory version vector: incoming eventVersion ${incoming.eventVersion} < " +
                        "current ${current.eventVersion} but specialized version is greater " +
                        "(cl: ${incoming.classificationVersion} vs ${current.classificationVersion}, " +
                        "ack: ${incoming.acknowledgementVersion} vs ${current.acknowledgementVersion})",
                )
            }
            return EventMergeResult.Stale
        }

        // 5. If incoming eventVersion == current eventVersion:
        if (incoming.eventVersion == current.eventVersion) {
            if (incoming.classificationVersion != current.classificationVersion ||
                incoming.acknowledgementVersion != current.acknowledgementVersion
            ) {
                return EventMergeResult.IntegrityViolation(
                    "Equal eventVersion ${incoming.eventVersion} has differing specialized versions: " +
                        "incoming(cl=${incoming.classificationVersion}, ack=${incoming.acknowledgementVersion}) vs " +
                        "current(cl=${current.classificationVersion}, ack=${current.acknowledgementVersion})",
                )
            }
            if (incoming.isErroneous != current.isErroneous ||
                incoming.erroneousMarkedAt != current.erroneousMarkedAt ||
                incoming.erroneousMarkedBy != current.erroneousMarkedBy ||
                incoming.status != current.status ||
                incoming.acknowledgedAt != current.acknowledgedAt ||
                incoming.acknowledgedBy != current.acknowledgedBy
            ) {
                return EventMergeResult.IntegrityViolation(
                    "Equal eventVersion ${incoming.eventVersion} has content/payload mismatch",
                )
            }
            return EventMergeResult.Stale
        }

        // 6. Only incoming.eventVersion > current.eventVersion reaches here:
        // Equal specialized version identity check (G.4)
        if (incoming.classificationVersion == current.classificationVersion) {
            if (incoming.isErroneous != current.isErroneous ||
                incoming.erroneousMarkedAt != current.erroneousMarkedAt ||
                incoming.erroneousMarkedBy != current.erroneousMarkedBy
            ) {
                return EventMergeResult.IntegrityViolation(
                    "Classification version ${incoming.classificationVersion} equality with content mismatch",
                )
            }
        }

        if (incoming.acknowledgementVersion == current.acknowledgementVersion) {
            if (incoming.status != current.status ||
                incoming.acknowledgedAt != current.acknowledgedAt ||
                incoming.acknowledgedBy != current.acknowledgedBy
            ) {
                return EventMergeResult.IntegrityViolation(
                    "Acknowledgement version ${incoming.acknowledgementVersion} equality with content mismatch",
                )
            }
        }

        // 7. Merge specialized groups (orthogonal merge)
        val classificationPart =
            if (incoming.classificationVersion > current.classificationVersion) {
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
            if (incoming.acknowledgementVersion > current.acknowledgementVersion) {
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

        val merged =
            current.copy(
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

        return EventMergeResult.Success(merged)
    }

    fun merge(
        current: Event,
        incoming: Event,
    ): Event =
        when (val res = validateAndMerge(current, incoming)) {
            is EventMergeResult.Success -> res.merged
            is EventMergeResult.Stale,
            is EventMergeResult.IntegrityViolation,
            -> current
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
