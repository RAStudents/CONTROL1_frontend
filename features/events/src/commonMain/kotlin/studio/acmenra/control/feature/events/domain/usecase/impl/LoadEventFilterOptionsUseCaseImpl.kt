package studio.acmenra.control.feature.events.domain.usecase.impl

import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.LoadEventFilterOptionsUseCase

class LoadEventFilterOptionsUseCaseImpl(
    private val repository: EventsRepository,
) : LoadEventFilterOptionsUseCase {
    override suspend fun invoke(companyId: String): EventsFilterOptions = repository.getFilterOptions(companyId)
}
