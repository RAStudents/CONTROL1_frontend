package studio.acmenra.control.feature.events.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsQuery

interface EventsFeedSession {
    val companyId: String
    val query: EventsQuery
    val state: StateFlow<EventsFeedState>
    val connectionNotice: Flow<Notice>
    val filterOptionsInvalidated: Flow<Unit>

    fun loadNextPage()

    fun refresh()

    suspend fun refreshForHistory(operationId: Long): HistoryRefreshResult

    fun onViewportAtTop()

    suspend fun flushPendingNewer(operationId: Long = 0L): PendingNewerFlushResult

    suspend fun acknowledgeDeltasThrough(batchId: Long): Boolean

    fun markOptimisticAckPending(eventId: String)

    fun clearOptimisticAckPending(eventId: String)

    fun applyAcknowledged(event: Event)

    fun applyEventMutationResult(event: Event)

    fun retryRebootstrap()

    fun updateAccessCapabilities(access: EventsAccessCapabilities)

    fun close()
}
