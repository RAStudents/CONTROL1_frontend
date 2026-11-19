package studio.acmenra.control.feature.events.domain.usecase

import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus

interface CreateEventsExportUseCase {
    suspend operator fun invoke(
        companyId: String,
        request: EventsExportRequest,
        idempotencyKey: String,
    ): EventsExportStatus
}
