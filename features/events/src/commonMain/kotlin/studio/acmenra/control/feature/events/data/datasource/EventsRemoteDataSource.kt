package studio.acmenra.control.feature.events.data.datasource

import studio.acmenra.control.feature.events.data.model.CreateExportRequestDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventsBootstrapDto
import studio.acmenra.control.feature.events.data.model.EventsExportStatusDto
import studio.acmenra.control.feature.events.data.model.EventsFilterOptionsDto
import studio.acmenra.control.feature.events.data.model.EventsPageDto
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsQuery

sealed interface FilterOptionsFetchResult {
    data class Success(
        val options: EventsFilterOptionsDto,
        val etag: String?,
    ) : FilterOptionsFetchResult

    data object NotModified : FilterOptionsFetchResult
}

interface EventsRemoteDataSource {
    suspend fun getBootstrap(
        companyId: String,
        query: EventsQuery,
        limit: Int = 50,
    ): EventsBootstrapDto

    suspend fun getEventsPage(
        companyId: String,
        cursor: String,
        limit: Int = 50,
    ): EventsPageDto

    suspend fun getFilterOptions(
        companyId: String,
        ifNoneMatchEtag: String? = null,
    ): FilterOptionsFetchResult

    suspend fun acknowledgeEvent(
        companyId: String,
        eventId: String,
    ): EventDto

    suspend fun markEventErroneous(
        companyId: String,
        eventId: String,
        expectedClassificationVersion: Long,
        idempotencyKey: String,
    ): EventDto

    suspend fun createExport(
        companyId: String,
        request: CreateExportRequestDto,
        idempotencyKey: String,
    ): EventsExportStatusDto

    suspend fun getExportStatus(
        companyId: String,
        exportId: String,
    ): EventsExportStatusDto

    suspend fun downloadExport(
        companyId: String,
        exportId: String,
    ): EventsExportDownload
}

sealed class EventsApiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class SnapshotExpired(
        message: String = "Snapshot expired",
    ) : EventsApiException(message)

    class FilterInvalid(
        val invalidFields: List<String>,
        message: String = "Filter invalid",
    ) : EventsApiException(message)

    class SnapshotTooLarge(
        val maxItems: Int?,
        val suggestedFrom: String?,
        val suggestedTo: String?,
        message: String = "Snapshot too large",
    ) : EventsApiException(message)

    class StateConflict(
        val latestEvent: EventDto?,
        message: String = "State conflict",
    ) : EventsApiException(message)

    class PayloadMismatch(
        message: String = "Idempotency key payload mismatch",
    ) : EventsApiException(message)

    class ExportNotReady(
        message: String = "Export not ready",
    ) : EventsApiException(message)

    class ExportExpired(
        message: String = "Export expired",
    ) : EventsApiException(message)

    class PermissionDenied(
        message: String = "Permission denied",
    ) : EventsApiException(message)

    class Generic(
        val code: String,
        val serverMessage: String?,
        val retryable: Boolean,
    ) : EventsApiException("API error $code: $serverMessage")
}
