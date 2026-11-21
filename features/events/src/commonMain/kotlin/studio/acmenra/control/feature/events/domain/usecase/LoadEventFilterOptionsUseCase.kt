package studio.acmenra.control.feature.events.domain.usecase

import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions

interface LoadEventFilterOptionsUseCase {
    suspend operator fun invoke(companyId: String): EventsFilterOptions
}
