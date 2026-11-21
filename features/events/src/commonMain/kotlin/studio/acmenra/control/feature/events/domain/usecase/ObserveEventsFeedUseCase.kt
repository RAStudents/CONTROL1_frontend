package studio.acmenra.control.feature.events.domain.usecase

import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.repository.EventsFeedSession

interface ObserveEventsFeedUseCase {
    operator fun invoke(
        companyId: String,
        query: EventsQuery,
        accessCapabilities: EventsAccessCapabilities,
    ): EventsFeedSession
}
