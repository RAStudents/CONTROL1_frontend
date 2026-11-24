package studio.acmenra.control.feature.events.domain.repository

sealed interface PendingNewerFlushResult {
    data class Completed(
        val operationId: Long = 0L,
        val batchId: Long,
        val snapshotId: String?,
        val feedRevision: Long = 0L,
        val materializedEventIds: List<String> = emptyList(),
        val createdEventIds: List<String> = emptyList(),
        val updatedEventIds: List<String> = emptyList(),
        val remainingPendingEventIds: List<String> = emptyList(),
        val remainingPendingCount: Int = 0,
    ) : PendingNewerFlushResult

    data class NoPendingEvents(
        val operationId: Long = 0L,
        val feedRevision: Long = 0L,
    ) : PendingNewerFlushResult

    data class Rejected(
        val operationId: Long = 0L,
        val reason: FlushFailureReason,
    ) : PendingNewerFlushResult

    data class OutcomeUnknown(
        val operationId: Long = 0L,
        val reason: String = "Operation timed out after command was enqueued",
    ) : PendingNewerFlushResult
}

enum class FlushFailureReason {
    ACCESS_DENIED,
    SESSION_CLOSED,
    FEED_SCOPE_CHANGED,
    PROCESSOR_CLOSED,
    INTERNAL_ERROR,
}
