package studio.acmenra.control.feature.events.domain.model

import kotlin.time.Instant

enum class EventsExportStatusCode {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    EXPIRED,
    UNKNOWN,
}

enum class EventsExportFormatPreset {
    EXCEL_SEMICOLON_BOM,
    STANDARD_COMMA_UTF8,
}

data class EventsExportRequest(
    val query: EventsQuery,
    val includeMediaLinks: Boolean = false,
    val formatPreset: EventsExportFormatPreset = EventsExportFormatPreset.EXCEL_SEMICOLON_BOM,
)

data class EventsExportStatus(
    val exportId: String,
    val status: EventsExportStatusCode,
    val progressPercent: Int = 0,
    val processedRecords: Int = 0,
    val totalRecords: Int = 0,
    val createdAt: Instant,
    val expiresAt: Instant? = null,
    val failureCode: String? = null,
)

data class EventsExportDownload(
    val exportId: String,
    val filename: String,
    val bytes: ByteArray,
    val contentType: String,
)
