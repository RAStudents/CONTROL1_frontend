package studio.acmenra.control.feature.events.domain.usecase.impl

import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.AcknowledgeEventUseCase

class AcknowledgeEventUseCaseImpl(
    private val repository: EventsRepository,
) : AcknowledgeEventUseCase {
    override suspend fun invoke(
        companyId: String,
        eventId: String,
    ): EventsOperationResult<Event> = repository.acknowledgeEvent(companyId, eventId)
}
