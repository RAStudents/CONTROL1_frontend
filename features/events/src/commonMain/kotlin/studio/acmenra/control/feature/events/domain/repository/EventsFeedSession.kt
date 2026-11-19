package studio.acmenra.control.feature.events.domain.repository

import kotlinx.coroutines.flow.StateFlow
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsQuery

interface EventsFeedSession {
    val companyId: String
    val query: EventsQuery
    val state: StateFlow<EventsFeedState>

    fun loadNextPage()

    fun refresh()

    fun onViewportAtTop()

    fun markOptimisticAckPending(eventId: String)

    fun clearOptimisticAckPending(eventId: String)

    fun applyAcknowledged(event: Event)

    fun retryRebootstrap()

    fun updateAccessCapabilities(access: EventsAccessCapabilities)

    fun close()
}
