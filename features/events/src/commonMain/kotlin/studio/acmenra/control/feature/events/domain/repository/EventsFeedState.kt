package studio.acmenra.control.feature.events.domain.repository

import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsInitialLoadError
import studio.acmenra.control.feature.events.domain.model.EventsPaginationError
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.RealtimeConnectionState
import studio.acmenra.control.feature.events.domain.model.RealtimeOverlay

sealed interface RebootstrapState {
    data object Idle : RebootstrapState

    data class Loading(
        val attempt: Int,
    ) : RebootstrapState

    data class RetryWaiting(
        val attempt: Int,
        val delayMs: Long,
    ) : RebootstrapState

    data class Failed(
        val error: Throwable,
    ) : RebootstrapState
}

sealed interface DeltaDeliveryState {
    data object Healthy : DeltaDeliveryState

    data class Overflowed(
        val firstMissingBatchId: Long,
        val latestBatchId: Long,
    ) : DeltaDeliveryState
}

internal data class AppendFeedDeltaResult(
    val pendingDeltas: List<EventsFeedDelta>,
    val deliveryState: DeltaDeliveryState,
)

data class EventsFeedDelta(
    val batchId: Long,
    val tenantSequence: Long = 0L,
    val createdEventIds: List<String> = emptyList(),
    val updatedEventIds: List<String> = emptyList(),
    val removedEventIds: List<String> = emptyList(),
) {
    init {
        require(batchId > 0L) { "batchId must be strictly positive: $batchId" }
        require(tenantSequence >= 0L) { "tenantSequence must be non-negative: $tenantSequence" }
        require(createdEventIds.distinct().size == createdEventIds.size) { "createdEventIds contains duplicates" }
        require(updatedEventIds.distinct().size == updatedEventIds.size) { "updatedEventIds contains duplicates" }
        require(removedEventIds.distinct().size == removedEventIds.size) { "removedEventIds contains duplicates" }

        val createdSet = createdEventIds.toSet()
        val updatedSet = updatedEventIds.toSet()
        val removedSet = removedEventIds.toSet()

        require(createdSet.intersect(updatedSet).isEmpty()) { "createdEventIds and updatedEventIds must be disjoint" }
        require(createdSet.intersect(removedSet).isEmpty()) { "createdEventIds and removedEventIds must be disjoint" }
        require(updatedSet.intersect(removedSet).isEmpty()) { "updatedEventIds and removedEventIds must be disjoint" }
    }

    val isEmpty: Boolean
        get() = createdEventIds.isEmpty() && updatedEventIds.isEmpty() && removedEventIds.isEmpty()
}

data class EventsFeedState(
    val visibleEvents: List<Event> = emptyList(),
    val realtimeOverlay: RealtimeOverlay = RealtimeOverlay(),
    val access: EventsAccessCapabilities =
        EventsAccessCapabilities(eventsRead = false, eventsReview = false, reportsExport = false),
    val snapshotId: String? = null,
    val asOfSequence: Long = 0L,
    val replayId: Long = 1L,
    val snapshotTotalCount: Int = 0,
    val hasMore: Boolean = false,
    val isInitialLoading: Boolean = true,
    val initialLoadError: EventsInitialLoadError? = null,
    val isLoadingNextPage: Boolean = false,
    val paginationError: EventsPaginationError? = null,
    val connectionState: RealtimeConnectionState = RealtimeConnectionState.DISCONNECTED,
    val rebootstrapState: RebootstrapState = RebootstrapState.Idle,
    val pendingNewerCount: Int = 0,
    val pendingHistoryCount: Int = 0,
    val summary: EventsSummary? = null,
    val isSummaryLoading: Boolean = false,
    val pendingDeltas: List<EventsFeedDelta> = emptyList(),
    val deltaDeliveryState: DeltaDeliveryState = DeltaDeliveryState.Healthy,
    val bootstrapGeneration: Long = 0L,
    val feedRevision: Long = 0L,
    val nextCursor: String? = null,
    val pageGeneration: Long = 0L,
) {
    val pendingAckIds: Set<String> get() = realtimeOverlay.pendingAckIds
    val requiresRefresh: Boolean get() = realtimeOverlay.requiresRefresh
}

private const val MAX_PENDING_DELTAS = 100

internal fun appendFeedDeltaChecked(
    current: List<EventsFeedDelta>,
    currentDeliveryState: DeltaDeliveryState,
    delta: EventsFeedDelta?,
): AppendFeedDeltaResult {
    if (delta == null || delta.isEmpty) {
        return AppendFeedDeltaResult(current, currentDeliveryState)
    }
    val next = current + delta
    if (next.size <= MAX_PENDING_DELTAS) {
        val nextDelivery =
            when (currentDeliveryState) {
                DeltaDeliveryState.Healthy -> DeltaDeliveryState.Healthy
                is DeltaDeliveryState.Overflowed ->
                    currentDeliveryState.copy(
                        latestBatchId = maxOf(currentDeliveryState.latestBatchId, delta.batchId),
                    )
            }
        return AppendFeedDeltaResult(next, nextDelivery)
    }

    val droppedCount = next.size - MAX_PENDING_DELTAS
    val firstDroppedBatchId = next.first().batchId
    val trimmed = next.drop(droppedCount)
    val firstMissing =
        when (currentDeliveryState) {
            DeltaDeliveryState.Healthy -> firstDroppedBatchId
            is DeltaDeliveryState.Overflowed -> minOf(currentDeliveryState.firstMissingBatchId, firstDroppedBatchId)
        }
    return AppendFeedDeltaResult(
        pendingDeltas = trimmed,
        deliveryState =
            DeltaDeliveryState.Overflowed(
                firstMissingBatchId = firstMissing,
                latestBatchId = trimmed.last().batchId,
            ),
    )
}
