package studio.acmenra.control.feature.events.domain.repository

data class PendingNewerFlushResult(
    val batchId: Long,
    val snapshotId: String?,
    val materializedEventIds: List<String> = emptyList(),
    val createdEventIds: List<String> = emptyList(),
)
