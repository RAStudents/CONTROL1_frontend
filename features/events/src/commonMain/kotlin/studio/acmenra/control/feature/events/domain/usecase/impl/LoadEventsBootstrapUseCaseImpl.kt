package studio.acmenra.control.feature.events.domain.usecase.impl

import studio.acmenra.control.feature.events.domain.model.EventsBootstrap
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.LoadEventsBootstrapUseCase

class LoadEventsBootstrapUseCaseImpl(
    private val repository: EventsRepository,
) : LoadEventsBootstrapUseCase {
    override suspend fun invoke(
        companyId: String,
        query: EventsQuery,
        limit: Int,
    ): EventsBootstrap = repository.bootstrap(companyId, query, limit)
}
