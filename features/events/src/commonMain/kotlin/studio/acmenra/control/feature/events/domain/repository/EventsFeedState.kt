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
) {
    val pendingAckIds: Set<String> get() = realtimeOverlay.pendingAckIds
    val requiresRefresh: Boolean get() = realtimeOverlay.requiresRefresh
}
