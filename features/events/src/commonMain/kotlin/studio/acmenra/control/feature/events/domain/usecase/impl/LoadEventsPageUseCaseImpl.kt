package studio.acmenra.control.feature.events.domain.usecase.impl

import studio.acmenra.control.feature.events.domain.model.EventsPage
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.LoadEventsPageUseCase

class LoadEventsPageUseCaseImpl(
    private val repository: EventsRepository,
) : LoadEventsPageUseCase {
    override suspend fun invoke(
        companyId: String,
        cursor: String,
        limit: Int,
    ): EventsPage = repository.getEventsPage(companyId, cursor, limit)
}
