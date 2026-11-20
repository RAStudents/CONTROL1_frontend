package studio.acmenra.control.feature.events.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
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

private const val MAX_EXPORT_DOWNLOAD_BYTES = 100L * 1024 * 1024

private class BoundedByteAccumulator(
    private val maxBytes: Long,
    initialCapacity: Int = 0,
) {
    private var buffer = ByteArray(initialCapacity.coerceAtLeast(1024).coerceAtMost(maxBytes.toInt()))
    private var size = 0

    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (size.toLong() + length > maxBytes) {
            throw EventsApiException.ExportDownloadTooLarge(maxBytes)
        }
        ensureCapacity(size + length)
        bytes.copyInto(buffer, destinationOffset = size, startIndex = offset, endIndex = offset + length)
        size += length
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= buffer.size) return
        var newCap = buffer.size.toLong() * 2
        if (newCap < minCapacity) newCap = minCapacity.toLong()
        if (newCap > maxBytes) newCap = maxBytes
        val newBuffer = ByteArray(newCap.toInt())
        buffer.copyInto(newBuffer, 0, 0, size)
        buffer = newBuffer
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

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
        val encodedCompany = companyId.encodeURLPathPart()
        val response =
            apiClient.get("api/v1/companies/$encodedCompany/events/bootstrap") {
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
        val encodedCompany = companyId.encodeURLPathPart()
        val response =
            apiClient.get("api/v1/companies/$encodedCompany/events") {
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
        val encodedCompany = companyId.encodeURLPathPart()
        val response =
            apiClient.get("api/v1/companies/$encodedCompany/events/filter-options") {
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
        val encodedCompany = companyId.encodeURLPathPart()
        val encodedEvent = eventId.encodeURLPathPart()
        val response =
            apiClient.put("api/v1/companies/$encodedCompany/events/$encodedEvent/acknowledge") {
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
        val encodedCompany = companyId.encodeURLPathPart()
        val encodedEvent = eventId.encodeURLPathPart()
        val response =
            apiClient.post("api/v1/companies/$encodedCompany/events/$encodedEvent/erroneous") {
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
        val encodedCompany = companyId.encodeURLPathPart()
        val response =
            apiClient.post("api/v1/companies/$encodedCompany/events/exports") {
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
        val encodedCompany = companyId.encodeURLPathPart()
        val encodedExport = exportId.encodeURLPathPart()
        val response =
            apiClient.get("api/v1/companies/$encodedCompany/events/exports/$encodedExport")

        handleErrorsIfAny(response)
        return response.body<EventsExportStatusDto>()
    }

    override suspend fun downloadExport(
        companyId: String,
        exportId: String,
    ): EventsExportDownload {
        val encodedCompany = companyId.encodeURLPathPart()
        val encodedExport = exportId.encodeURLPathPart()
        val statement =
            apiClient.prepareGet("api/v1/companies/$encodedCompany/events/exports/$encodedExport/download")

        return statement.execute { response ->
            handleErrorsIfAny(response)

            val disposition = response.headers[HttpHeaders.ContentDisposition]
            val filename = extractFilenameFromDisposition(disposition) ?: "events_export.csv"
            val contentType = response.headers[HttpHeaders.ContentType] ?: "text/csv"
            val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > MAX_EXPORT_DOWNLOAD_BYTES) {
                throw EventsApiException.ExportDownloadTooLarge(MAX_EXPORT_DOWNLOAD_BYTES)
            }

            val channel = response.bodyAsChannel()
            val chunk = ByteArray(16 * 1024)
            var totalBytes = 0L
            val accumulator =
                BoundedByteAccumulator(
                    maxBytes = MAX_EXPORT_DOWNLOAD_BYTES,
                    initialCapacity = contentLength?.takeIf { it in 0..MAX_EXPORT_DOWNLOAD_BYTES }?.toInt() ?: 1024,
                )

            try {
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(chunk, 0, chunk.size)
                    if (read < 0) break
                    if (read == 0) continue

                    if (totalBytes + read > MAX_EXPORT_DOWNLOAD_BYTES) {
                        channel.cancel(CancellationException("Download exceeded max size"))
                        throw EventsApiException.ExportDownloadTooLarge(MAX_EXPORT_DOWNLOAD_BYTES)
                    }

                    accumulator.write(chunk, 0, read)
                    totalBytes += read
                }

                EventsExportDownload(
                    exportId = exportId,
                    filename = filename,
                    bytes = accumulator.toByteArray(),
                    contentType = contentType,
                )
            } catch (e: CancellationException) {
                channel.cancel(e)
                throw e
            } catch (e: Throwable) {
                channel.cancel(CancellationException(e.message, e))
                throw e
            }
        }
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
                val isRetryable =
                    parsedError?.retryable
                        ?: (
                            response.status.value == 408 ||
                                response.status.value == 425 ||
                                response.status.value == 429 ||
                                response.status.value >= 500
                        )
                throw EventsApiException.Generic(
                    code = code,
                    serverMessage = parsedError?.message ?: response.status.description,
                    retryable = isRetryable,
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
