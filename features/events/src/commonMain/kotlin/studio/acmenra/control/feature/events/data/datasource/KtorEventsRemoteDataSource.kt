package studio.acmenra.control.feature.events.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import studio.acmenra.control.feature.events.data.model.ApiErrorEnvelopeDto
import studio.acmenra.control.feature.events.data.model.CreateExportRequestDto
import studio.acmenra.control.feature.events.data.model.EventDto
import studio.acmenra.control.feature.events.data.model.EventsBootstrapDto
import studio.acmenra.control.feature.events.data.model.EventsExportStatusDto
import studio.acmenra.control.feature.events.data.model.EventsFilterOptionsDto
import studio.acmenra.control.feature.events.data.model.EventsPageDto
import studio.acmenra.control.feature.events.data.model.MarkErroneousRequestDto
import studio.acmenra.control.feature.events.domain.model.EventValidityFilter
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsQuery

class KtorEventsRemoteDataSource(
    private val apiClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EventsRemoteDataSource {
    override suspend fun getBootstrap(
        companyId: String,
        query: EventsQuery,
        limit: Int,
    ): EventsBootstrapDto {
        val filter = query.filter
        val response =
            apiClient.get("api/v1/companies/$companyId/events/bootstrap") {
                parameter("limit", limit)
                filter.searchId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    parameter("search_id", it)
                }
                filter.dateFrom?.let {
                    parameter("from", it.toString())
                }
                filter.dateTo?.let {
                    parameter("to", it.toString())
                }
                filter.eventTypeIds.sorted().forEach { typeId ->
                    if (typeId.isNotBlank()) parameter("event_type_ids", typeId)
                }
                filter.deviceIds.sorted().forEach { devId ->
                    if (devId.isNotBlank()) parameter("device_ids", devId)
                }
                if (filter.validity != EventValidityFilter.ALL) {
                    parameter("validity", filter.validity.name)
                }
                parameter("sort", query.sort.name)
            }

        handleErrorsIfAny(response)
        return response.body<EventsBootstrapDto>()
    }

    override suspend fun getEventsPage(
        companyId: String,
        cursor: String,
        limit: Int,
    ): EventsPageDto {
        val response =
            apiClient.get("api/v1/companies/$companyId/events") {
                parameter("cursor", cursor)
                parameter("limit", limit)
            }

        handleErrorsIfAny(response)
        return response.body<EventsPageDto>()
    }

    override suspend fun getFilterOptions(
        companyId: String,
        ifNoneMatchEtag: String?,
    ): FilterOptionsFetchResult {
        val response =
            apiClient.get("api/v1/companies/$companyId/events/filter-options") {
                ifNoneMatchEtag?.let { etag ->
                    header(HttpHeaders.IfNoneMatch, etag)
                }
            }

        if (response.status == HttpStatusCode.NotModified) {
            return FilterOptionsFetchResult.NotModified
        }

        handleErrorsIfAny(response)
        val etag = response.headers[HttpHeaders.ETag]
        val options = response.body<EventsFilterOptionsDto>()
        return FilterOptionsFetchResult.Success(options, etag)
    }

    override suspend fun acknowledgeEvent(
        companyId: String,
        eventId: String,
    ): EventDto {
        val response =
            apiClient.put("api/v1/companies/$companyId/events/$eventId/acknowledge") {
                contentType(ContentType.Application.Json)
            }

        handleErrorsIfAny(response)
        return response.body<EventDto>()
    }

    override suspend fun markEventErroneous(
        companyId: String,
        eventId: String,
        expectedClassificationVersion: Long,
        idempotencyKey: String,
    ): EventDto {
        val response =
            apiClient.post("api/v1/companies/$companyId/events/$eventId/erroneous") {
                header("Idempotency-Key", idempotencyKey)
                contentType(ContentType.Application.Json)
                setBody(MarkErroneousRequestDto(expectedClassificationVersion = expectedClassificationVersion))
            }

        handleErrorsIfAny(response)
        return response.body<EventDto>()
    }

    override suspend fun createExport(
        companyId: String,
        request: CreateExportRequestDto,
        idempotencyKey: String,
    ): EventsExportStatusDto {
        val response =
            apiClient.post("api/v1/companies/$companyId/events/exports") {
                header("Idempotency-Key", idempotencyKey)
                contentType(ContentType.Application.Json)
                setBody(request)
            }

        handleErrorsIfAny(response)
        return response.body<EventsExportStatusDto>()
    }

    override suspend fun getExportStatus(
        companyId: String,
        exportId: String,
    ): EventsExportStatusDto {
        val response =
            apiClient.get("api/v1/companies/$companyId/events/exports/$exportId")

        handleErrorsIfAny(response)
        return response.body<EventsExportStatusDto>()
    }

    override suspend fun downloadExport(
        companyId: String,
        exportId: String,
    ): EventsExportDownload {
        val response =
            apiClient.get("api/v1/companies/$companyId/events/exports/$exportId/download")

        handleErrorsIfAny(response)

        val disposition = response.headers[HttpHeaders.ContentDisposition]
        val filename = extractFilenameFromDisposition(disposition) ?: "events_export.csv"
        val contentType = response.headers[HttpHeaders.ContentType] ?: "text/csv"
        val bytes = response.readRawBytes()

        return EventsExportDownload(
            exportId = exportId,
            filename = filename,
            bytes = bytes,
            contentType = contentType,
        )
    }

    private suspend fun handleErrorsIfAny(response: HttpResponse) {
        if (response.status.isSuccess()) return

        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        val parsedError =
            runCatching {
                json.decodeFromString<ApiErrorEnvelopeDto>(text).error
            }.getOrNull()

        val code = parsedError?.code ?: response.status.value.toString()
        val details = parsedError?.details

        when {
            code == "EXPORT_EXPIRED" -> {
                throw EventsApiException.ExportExpired()
            }
            code == "SNAPSHOT_EXPIRED" -> {
                throw EventsApiException.SnapshotExpired()
            }
            code == "FILTER_INVALID" -> {
                throw EventsApiException.FilterInvalid(details?.invalidFields ?: emptyList())
            }
            code == "SNAPSHOT_TOO_LARGE" -> {
                throw EventsApiException.SnapshotTooLarge(
                    maxItems = details?.maxItems,
                    suggestedFrom = details?.suggestedFrom,
                    suggestedTo = details?.suggestedTo,
                )
            }
            code == "STATE_CONFLICT" -> {
                throw EventsApiException.StateConflict(latestEvent = details?.latestEvent)
            }
            code == "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH" -> {
                throw EventsApiException.PayloadMismatch()
            }
            code == "EXPORT_NOT_READY" -> {
                throw EventsApiException.ExportNotReady()
            }
            code in
                setOf(
                    "PERMISSION_DENIED",
                    "COMPANY_RESTRICTED",
                    "SUPPORT_GRANT_EXPIRED",
                    "SUPPORT_GRANT_REVOKED",
                )
            -> {
                throw EventsApiException.PermissionDenied(reasonCode = code)
            }
            response.status == HttpStatusCode.Gone -> {
                throw EventsApiException.SnapshotExpired()
            }
            response.status == HttpStatusCode.Conflict -> {
                throw EventsApiException.StateConflict(latestEvent = details?.latestEvent)
            }
            response.status == HttpStatusCode.Forbidden -> {
                throw EventsApiException.PermissionDenied(
                    reasonCode =
                        code.takeIf { it != "403" } ?: "PERMISSION_DENIED",
                )
            }
            else -> {
                throw EventsApiException.Generic(
                    code = code,
                    serverMessage = parsedError?.message ?: response.status.description,
                    retryable = parsedError?.retryable ?: (response.status.value >= 500),
                )
            }
        }
    }

    private fun extractFilenameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val match =
            Regex(
                """filename\*?=['"]?(?:UTF-8'')?([^'";\n]+)['"]?""",
                RegexOption.IGNORE_CASE,
            ).find(disposition)
        return match?.groupValues?.getOrNull(1)?.trim()
    }
}
