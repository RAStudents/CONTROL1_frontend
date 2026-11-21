package studio.acmenra.control.feature.events.presentation.platform

import org.koin.core.module.Module
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload

interface ExportFileSaver {
    suspend fun saveExportFile(download: EventsExportDownload): Boolean
}

expect val exportFileSaverModule: Module
