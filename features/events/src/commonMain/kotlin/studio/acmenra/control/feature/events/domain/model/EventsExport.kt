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
    val progressPercent: Int? = null,
    val processedRecords: Long? = null,
    val totalRecords: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val failureCode: String? = null,
    val downloadUrl: String? = null,
) {
    val derivedProgressPercent: Int?
        get() {
            if (progressPercent != null) return progressPercent
            val total = totalRecords ?: return null
            val processed = processedRecords ?: return null
            if (total <= 0L) return null
            return ((processed.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
        }

    fun isDownloadable(
        now: Instant =
            kotlin.time.Clock.System
                .now(),
    ): Boolean =
        status == EventsExportStatusCode.COMPLETED &&
            !downloadUrl.isNullOrBlank() &&
            expiresAt != null &&
            expiresAt > now

    val isDownloadable: Boolean
        get() = isDownloadable()
}

/**
 * Canonical C.1 / F.16 model for downloaded export files.
 *
 * Direct in-memory byte transfer is not part of the domain model.
 * The domain layer references the downloaded file by its canonical fields:
 * - [fileName]: Desanitized display/target file name
 * - [contentLength]: Exact size in bytes (bounded to <= 100 MiB)
 * - [downloadHandle]: Opaque handle used by data/platform file savers to retrieve the payload
 */
data class EventsExportDownload(
    val fileName: String,
    val contentLength: Long,
    val downloadHandle: String,
) {
    val filename: String get() = fileName
}
