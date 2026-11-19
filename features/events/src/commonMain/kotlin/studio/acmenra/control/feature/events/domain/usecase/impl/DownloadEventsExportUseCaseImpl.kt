package studio.acmenra.control.feature.events.domain.usecase.impl

import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.repository.EventsRepository
import studio.acmenra.control.feature.events.domain.usecase.DownloadEventsExportUseCase

class DownloadEventsExportUseCaseImpl(
    private val repository: EventsRepository,
) : DownloadEventsExportUseCase {
    override suspend fun invoke(
        companyId: String,
        exportId: String,
    ): EventsExportDownload = repository.downloadExport(companyId, exportId)
}
