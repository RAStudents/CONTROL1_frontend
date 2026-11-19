package studio.acmenra.control.feature.events.domain.usecase.impl

import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.repository.EventsFeedSession
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsFeedUseCase

class ObserveEventsFeedUseCaseImpl(
    private val repository: EventsRepository,
) : ObserveEventsFeedUseCase {
    override fun invoke(
        companyId: String,
        query: EventsQuery,
        accessCapabilities: EventsAccessCapabilities,
    ): EventsFeedSession = repository.openFeedSession(companyId, query, accessCapabilities)
}
