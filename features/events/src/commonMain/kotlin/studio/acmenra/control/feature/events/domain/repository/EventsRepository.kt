package studio.acmenra.control.feature.events.domain.repository

import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsBootstrap
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsPage
import studio.acmenra.control.feature.events.domain.model.EventsQuery

interface EventsRepository {
    fun openFeedSession(
        companyId: String,
        query: EventsQuery,
        accessCapabilities: EventsAccessCapabilities,
        parentScope: kotlinx.coroutines.CoroutineScope? = null,
    ): EventsFeedSession

    suspend fun bootstrap(
        companyId: String,
        query: EventsQuery,
        limit: Int = 50,
    ): EventsBootstrap

    suspend fun getEventsPage(
        companyId: String,
        cursor: String,
        limit: Int = 50,
    ): EventsPage

    suspend fun getFilterOptions(companyId: String): EventsFilterOptions

    suspend fun createExport(
        companyId: String,
        request: EventsExportRequest,
        idempotencyKey: String,
    ): EventsExportStatus

    suspend fun getExportStatus(
        companyId: String,
        exportId: String,
    ): EventsExportStatus

    suspend fun downloadExport(
        companyId: String,
        exportId: String,
    ): EventsExportDownload

    suspend fun acknowledgeEvent(
        companyId: String,
        eventId: String,
    ): EventsOperationResult<Event>

    suspend fun markEventErroneous(
        companyId: String,
        eventId: String,
        expectedClassificationVersion: Long,
        idempotencyKey: String,
    ): EventsOperationResult<Event>

    fun invalidateFilterCache(companyId: String)
}
