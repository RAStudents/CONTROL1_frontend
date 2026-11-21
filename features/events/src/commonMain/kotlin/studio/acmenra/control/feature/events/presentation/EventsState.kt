package studio.acmenra.control.feature.events.presentation

import org.jetbrains.compose.resources.StringResource
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsInitialLoadError
import studio.acmenra.control.feature.events.domain.model.EventsPaginationError
import studio.acmenra.control.feature.events.domain.model.EventsSummary
import studio.acmenra.control.feature.events.domain.model.RealtimeConnectionState
import studio.acmenra.control.feature.events.domain.model.RealtimeOverlay

data class ErroneousConfirmationTarget(
    val eventId: String,
    val expectedClassificationVersion: Long,
    val eventTypeName: String,
)

data class EventsState(
    val companyId: String = "",
    val access: EventsAccessCapabilities = EventsAccessCapabilities(),
    val isInitialLoading: Boolean = true,
    val initialLoadError: EventsInitialLoadError? = null,
    val visibleEvents: List<Event> = emptyList(),
    val realtimeOverlay: RealtimeOverlay = RealtimeOverlay(),
    val asOfSequence: Long = 0L,
    val snapshotTotalCount: Int? = null,
    val hasMore: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val paginationError: EventsPaginationError? = null,
    val connectionState: RealtimeConnectionState = RealtimeConnectionState.DISCONNECTED,
    val pendingNewerCount: Int = 0,
    val pendingHistoryCount: Int = 0,
    val summary: EventsSummary? = null,
    val filterOptions: EventsFilterOptions? = null,
    val isFilterOptionsLoading: Boolean = false,
    val filter: EventsFilter = EventsFilter(),
    val selectedEventId: String? = null,
    val isViewerOpenInCompact: Boolean = false,
    val isExportModeActive: Boolean = false,
    val exportFormatPreset: EventsExportFormatPreset = EventsExportFormatPreset.EXCEL_SEMICOLON_BOM,
    val exportIncludeMediaLinks: Boolean = false,
    val exportJobStatus: EventsExportStatus? = null,
    val isExportSubmitting: Boolean = false,
    val isExportDownloading: Boolean = false,
    val exportError: StringResource? = null,
    val isFilterDialogOpen: Boolean = false,
    val isDatePickerOpen: Boolean = false,
    val confirmationTarget: ErroneousConfirmationTarget? = null,
    val isMarkingErroneous: Boolean = false,
    val mutationErrorNotice: StringResource? = null,
    val clipboardNotice: String? = null,
    val errorNotice: Notice? = null,
) {
    val erroneousConfirmationEventId: String?
        get() = confirmationTarget?.eventId
    val selectedEvent: Event?
        get() = visibleEvents.firstOrNull { it.id == selectedEventId }

    val requiresRefresh: Boolean
        get() = realtimeOverlay.requiresRefresh

    val pendingAckIds: Set<String>
        get() = realtimeOverlay.pendingAckIds
}
