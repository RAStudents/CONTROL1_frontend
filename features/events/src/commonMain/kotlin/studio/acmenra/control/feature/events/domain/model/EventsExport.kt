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
 * Memory model:
 * - Direct in-memory byte transfer is strictly bounded to `MAX_EXPORT_DOWNLOAD_BYTES` (100 MiB).
 * - Bounded accumulation: Not an end-to-end streaming sink on browser WasmJs without
 *   the File System Access API; heap footprint peaks at ~2-3x during Blob/Int8Array creation,
 *   strictly bounded to <= 100 MiB payload.
 */
data class EventsExportDownload(
    val fileName: String,
    val contentLength: Long,
    val downloadHandle: String,
    val exportId: String = downloadHandle,
    val bytes: ByteArray = ByteArray(0),
    val contentType: String = "text/csv",
) {
    constructor(
        exportId: String,
        filename: String,
        bytes: ByteArray,
        contentType: String,
    ) : this(
        fileName = filename,
        contentLength = bytes.size.toLong(),
        downloadHandle = exportId,
        exportId = exportId,
        bytes = bytes,
        contentType = contentType,
    )

    val filename: String get() = fileName

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EventsExportDownload) return false

        return fileName == other.fileName &&
            contentLength == other.contentLength &&
            downloadHandle == other.downloadHandle &&
            exportId == other.exportId &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + contentLength.hashCode()
        result = 31 * result + downloadHandle.hashCode()
        result = 31 * result + exportId.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
