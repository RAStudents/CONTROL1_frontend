package studio.acmenra.control.feature.events.domain.usecase

import studio.acmenra.control.feature.events.domain.model.EventsPage

interface LoadEventsPageUseCase {
    suspend operator fun invoke(
        companyId: String,
        cursor: String,
        limit: Int = 50,
    ): EventsPage
}
