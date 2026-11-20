package studio.acmenra.control.feature.events.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EventDto(
    @SerialName("id") val id: String,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("event_version") val eventVersion: Long,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("classification_version") val classificationVersion: Long,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("acknowledgement_version") val acknowledgementVersion: Long,
    @SerialName("event_type_id") val eventTypeId: String,
    @SerialName("event_type_name") val eventTypeName: String,
    @SerialName("category_code") val categoryCode: String,
    @SerialName("icon_key") val iconKey: String? = null,
    @SerialName("location_id") val locationId: String,
    @SerialName("location_name") val locationName: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("status") val status: String,
    @SerialName("acknowledged_at") val acknowledgedAt: String? = null,
    @SerialName("acknowledged_by") val acknowledgedBy: String? = null,
    @SerialName("is_erroneous") val isErroneous: Boolean,
    @SerialName("erroneous_marked_at") val erroneousMarkedAt: String? = null,
    @SerialName("erroneous_marked_by") val erroneousMarkedBy: String? = null,
    @SerialName("preview_url") val previewUrl: String,
    @SerialName("video_url") val videoUrl: String? = null,
)

@Serializable
data class EventsPageDto(
    @SerialName("items") val items: List<EventDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("total_count") val totalCount: Int = 0,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("as_of_sequence") val asOfSequence: Long = 0L,
    @SerialName("snapshot_id") val snapshotId: String? = null,
)

@Serializable
data class EventsPaginationDto(
    @SerialName("snapshot_id") val snapshotId: String,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("as_of_sequence") val asOfSequence: Long,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("total_count") val totalCount: Int = 0,
)

@Serializable
data class BootstrapEventsDto(
    @SerialName("items") val items: List<EventDto> = emptyList(),
    @SerialName("pagination") val pagination: EventsPaginationDto,
)

@Serializable
data class CategoryScoreDto(
    @SerialName("category_id") val categoryId: String,
    @SerialName("name") val name: String,
    @SerialName("score") val score: Int,
    @SerialName("trend") val trend: String,
)

@Serializable
data class DevicesStatusSummaryDto(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("online_ok_count") val onlineOkCount: Int = 0,
    @SerialName("unknown_count") val unknownCount: Int = 0,
    @SerialName("error_count") val errorCount: Int = 0,
)

@Serializable
data class EventsSummaryDto(
    @SerialName("business_timezone") val businessTimezone: String,
    @SerialName("business_date") val businessDate: String,
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("summary_as_of_sequence") val summaryAsOfSequence: Long,
    @SerialName("today_events_count") val todayEventsCount: Int = 0,
    @SerialName("categories") val categories: List<CategoryScoreDto> = emptyList(),
    @SerialName("devices") val devices: DevicesStatusSummaryDto = DevicesStatusSummaryDto(),
)

@Serializable
data class EventsBootstrapDto(
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("stream_cursor") val streamCursor: Long,
    @SerialName("events") val events: BootstrapEventsDto,
    @SerialName("summary") val summary: EventsSummaryDto,
)

@Serializable
data class EventTypeOptionDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("icon_key") val iconKey: String? = null,
)

@Serializable
data class DeviceFilterOptionDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
)

@Serializable
data class LocationFilterGroupDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("devices") val devices: List<DeviceFilterOptionDto> = emptyList(),
)

@Serializable
data class EventsFilterOptionsDto(
    @SerialName("revision") val revision: String,
    @SerialName("event_types") val eventTypes: List<EventTypeOptionDto> = emptyList(),
    @SerialName("locations") val locations: List<LocationFilterGroupDto> = emptyList(),
)

@Serializable
data class MarkErroneousRequestDto(
    @Serializable(with = SafeJsLongSerializer::class)
    @SerialName("expected_classification_version") val expectedClassificationVersion: Long,
)

@Serializable
data class EventsExportFiltersDto(
    @SerialName("search_id") val searchId: String? = null,
    @SerialName("from") val from: String? = null,
    @SerialName("to") val to: String? = null,
    @SerialName("event_type_ids") val eventTypeIds: List<String> = emptyList(),
    @SerialName("device_ids") val deviceIds: List<String> = emptyList(),
    @SerialName("validity") val validity: String = "ALL",
)

@Serializable
data class CreateExportRequestDto(
    @SerialName("filters") val filters: EventsExportFiltersDto,
    @SerialName("include_media_links") val includeMediaLinks: Boolean = false,
    @SerialName("format_preset") val formatPreset: String = "EXCEL_SEMICOLON_BOM",
)

@Serializable
data class EventsExportStatusDto(
    @SerialName("export_id") val exportId: String,
    @SerialName("status") val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @Serializable(with = SafeJsNullableLongSerializer::class)
    @SerialName("processed_records") val processedRecords: Long? = null,
    @Serializable(with = SafeJsNullableLongSerializer::class)
    @SerialName("total_records") val totalRecords: Long? = null,
    @SerialName("progress_percent") val progressPercent: Int? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
)

@Serializable
data class ApiErrorExtraDetailsDto(
    @SerialName("invalid_fields") val invalidFields: List<String> = emptyList(),
    @SerialName("max_items") val maxItems: Int? = null,
    @SerialName("suggested_from") val suggestedFrom: String? = null,
    @SerialName("suggested_to") val suggestedTo: String? = null,
    @SerialName("latest_event") val latestEvent: EventDto? = null,
)

@Serializable
data class ApiErrorDetailsDto(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("retryable") val retryable: Boolean? = null,
    @SerialName("details") val details: ApiErrorExtraDetailsDto? = null,
)

@Serializable
data class ApiErrorEnvelopeDto(
    @SerialName("error") val error: ApiErrorDetailsDto,
)
