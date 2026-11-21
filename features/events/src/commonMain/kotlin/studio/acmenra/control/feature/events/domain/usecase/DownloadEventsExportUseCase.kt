package studio.acmenra.control.feature.events.domain.usecase

import studio.acmenra.control.feature.events.domain.model.EventsExportDownload

interface DownloadEventsExportUseCase {
    suspend operator fun invoke(
        companyId: String,
        exportId: String,
    ): EventsExportDownload
}
