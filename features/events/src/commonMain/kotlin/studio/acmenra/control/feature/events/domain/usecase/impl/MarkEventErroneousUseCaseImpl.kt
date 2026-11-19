package studio.acmenra.control.feature.events.domain.usecase.impl

import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.MarkEventErroneousUseCase

class MarkEventErroneousUseCaseImpl(
    private val repository: EventsRepository,
) : MarkEventErroneousUseCase {
    override suspend fun invoke(
        companyId: String,
        eventId: String,
        expectedClassificationVersion: Long,
        idempotencyKey: String,
    ): EventsOperationResult<Event> =
        repository.markEventErroneous(
            companyId = companyId,
            eventId = eventId,
            expectedClassificationVersion = expectedClassificationVersion,
            idempotencyKey = idempotencyKey,
        )
}
