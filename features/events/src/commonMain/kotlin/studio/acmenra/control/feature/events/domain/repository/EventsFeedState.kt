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
    val feedRevision: Long = 0L,
    val nextCursor: String? = null,
    val pageGeneration: Long = 0L,
) {
    constructor(
        visibleEvents: List<Event> = emptyList(),
        realtimeOverlay: RealtimeOverlay = RealtimeOverlay(),
        access: EventsAccessCapabilities =
            EventsAccessCapabilities(eventsRead = false, eventsReview = false, reportsExport = false),
        snapshotId: String? = null,
        asOfSequence: Long = 0L,
        replayId: Long = 1L,
        snapshotTotalCount: Int = 0,
        hasMore: Boolean = false,
        isInitialLoading: Boolean = true,
        initialLoadError: EventsInitialLoadError? = null,
        isLoadingNextPage: Boolean = false,
        paginationError: EventsPaginationError? = null,
        connectionState: RealtimeConnectionState = RealtimeConnectionState.DISCONNECTED,
        rebootstrapState: RebootstrapState = RebootstrapState.Idle,
        pendingNewerCount: Int = 0,
        pendingHistoryCount: Int = 0,
        summary: EventsSummary? = null,
        isSummaryLoading: Boolean = false,
        feedDelta: EventsFeedDelta?,
    ) : this(
        visibleEvents = visibleEvents,
        realtimeOverlay = realtimeOverlay,
        access = access,
        snapshotId = snapshotId,
        asOfSequence = asOfSequence,
        replayId = replayId,
        snapshotTotalCount = snapshotTotalCount,
        hasMore = hasMore,
        isInitialLoading = isInitialLoading,
        initialLoadError = initialLoadError,
        isLoadingNextPage = isLoadingNextPage,
        paginationError = paginationError,
        connectionState = connectionState,
        rebootstrapState = rebootstrapState,
        pendingNewerCount = pendingNewerCount,
        pendingHistoryCount = pendingHistoryCount,
        summary = summary,
        isSummaryLoading = isSummaryLoading,
        pendingDeltas = if (feedDelta != null) listOf(feedDelta) else emptyList(),
    )

    val feedDelta: EventsFeedDelta? get() = pendingDeltas.firstOrNull()
    val pendingAckIds: Set<String> get() = realtimeOverlay.pendingAckIds
    val requiresRefresh: Boolean get() = realtimeOverlay.requiresRefresh
}

private const val MAX_PENDING_DELTAS = 100

fun appendFeedDelta(
    current: List<EventsFeedDelta>,
    delta: EventsFeedDelta?,
): List<EventsFeedDelta> {
    if (delta == null || delta.isEmpty) return current
    val next = current + delta
    return if (next.size > MAX_PENDING_DELTAS) {
        next.drop(next.size - MAX_PENDING_DELTAS)
    } else {
        next
    }
}
