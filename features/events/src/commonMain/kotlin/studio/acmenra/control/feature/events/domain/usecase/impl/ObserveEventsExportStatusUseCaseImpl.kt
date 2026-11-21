package studio.acmenra.control.feature.events.domain.usecase.impl

import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsExportStatusUseCase

class ObserveEventsExportStatusUseCaseImpl(
    private val repository: EventsRepository,
) : ObserveEventsExportStatusUseCase {
    override suspend fun invoke(
        companyId: String,
        exportId: String,
    ): EventsExportStatus = repository.getExportStatus(companyId, exportId)
}
