package studio.acmenra.control.feature.events.domain.usecase.impl

import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.CreateEventsExportUseCase

class CreateEventsExportUseCaseImpl(
    private val repository: EventsRepository,
) : CreateEventsExportUseCase {
    override suspend fun invoke(
        companyId: String,
        request: EventsExportRequest,
        idempotencyKey: String,
    ): EventsExportStatus =
        repository.createExport(
            companyId = companyId,
            request = request,
            idempotencyKey = idempotencyKey,
        )
}
