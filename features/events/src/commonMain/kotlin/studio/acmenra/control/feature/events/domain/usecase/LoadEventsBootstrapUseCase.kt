package studio.acmenra.control.feature.events.domain.usecase

import studio.acmenra.control.feature.events.domain.model.EventsBootstrap
import studio.acmenra.control.feature.events.domain.model.EventsQuery

interface LoadEventsBootstrapUseCase {
    suspend operator fun invoke(
        companyId: String,
        query: EventsQuery,
        limit: Int = 50,
    ): EventsBootstrap
}
