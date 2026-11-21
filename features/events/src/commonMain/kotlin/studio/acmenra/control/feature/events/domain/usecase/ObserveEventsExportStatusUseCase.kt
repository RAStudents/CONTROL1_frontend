package studio.acmenra.control.feature.events.domain.usecase

import studio.acmenra.control.feature.events.domain.model.EventsExportStatus

interface ObserveEventsExportStatusUseCase {
    suspend operator fun invoke(
        companyId: String,
        exportId: String,
    ): EventsExportStatus
}
