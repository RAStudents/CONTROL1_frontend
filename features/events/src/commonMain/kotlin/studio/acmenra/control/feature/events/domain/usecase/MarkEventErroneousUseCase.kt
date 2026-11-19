package studio.acmenra.control.feature.events.domain.usecase

import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult

interface MarkEventErroneousUseCase {
    suspend operator fun invoke(
        companyId: String,
        eventId: String,
        expectedClassificationVersion: Long,
        idempotencyKey: String,
    ): EventsOperationResult<Event>
}
