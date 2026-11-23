package studio.acmenra.control.feature.events.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import studio.acmenra.control.core.ui.components.ProvideTopBar
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_export_csv
import studio.acmenra.control.core.ui.generated.resources.events_filter_tune
import studio.acmenra.control.core.ui.generated.resources.events_filters_title
import studio.acmenra.control.core.ui.generated.resources.events_show_statistics
import studio.acmenra.control.core.ui.generated.resources.events_validity_all
import studio.acmenra.control.core.ui.generated.resources.events_validity_erroneous_only
import studio.acmenra.control.core.ui.generated.resources.events_validity_valid_only
import studio.acmenra.control.core.ui.generated.resources.events_viewer_id_copied
import studio.acmenra.control.core.ui.generated.resources.events_viewer_placeholder
import studio.acmenra.control.core.ui.generated.resources.ic_bar_chart
import studio.acmenra.control.core.ui.generated.resources.ic_download
import studio.acmenra.control.core.ui.generated.resources.ic_expand_less
import studio.acmenra.control.core.ui.generated.resources.ic_expand_more
import studio.acmenra.control.core.ui.generated.resources.ic_tune
import studio.acmenra.control.core.ui.generated.resources.ic_videocam
import studio.acmenra.control.core.ui.generated.resources.nav_item_events
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.core.ui.notice.NoticeController
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventValidity
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
import studio.acmenra.control.feature.events.presentation.components.EventViewer
import studio.acmenra.control.feature.events.presentation.components.EventsDatePickerDialog
import studio.acmenra.control.feature.events.presentation.components.EventsEmptyState
import studio.acmenra.control.feature.events.presentation.components.EventsFilters
import studio.acmenra.control.feature.events.presentation.components.EventsList
import studio.acmenra.control.feature.events.presentation.components.EventsSummaryExportSlot
import studio.acmenra.control.feature.events.presentation.platform.ExternalMediaOpener

@Composable
fun EventsRouteContent(
    companyId: String = "",
    accessCapabilities: EventsAccessCapabilities = EventsAccessCapabilities(),
    queryHash: String? = null,
    onQueryHashChanged: ((String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: EventsViewModel = koinViewModel(key = companyId),
) {
    LaunchedEffect(onQueryHashChanged) {
        viewModel.onQueryHashChanged = onQueryHashChanged
    }

    LaunchedEffect(companyId, accessCapabilities, queryHash) {
        viewModel.initialize(companyId, accessCapabilities, queryHash)
    }

    val state by viewModel.state.collectAsState()
    val noticeController = koinInject<NoticeController>()
    val mediaOpener = koinInject<ExternalMediaOpener>()

    LaunchedEffect(state.clipboardNotice) {
        state.clipboardNotice?.let {
            noticeController.show(Notice.success(title = Res.string.events_viewer_id_copied))
            viewModel.dismissClipboardNotice()
        }
    }

    LaunchedEffect(state.mutationErrorNotice) {
        state.mutationErrorNotice?.let { res ->
            noticeController.show(Notice.error(title = res))
            viewModel.dismissMutationNotice()
        }
    }

    LaunchedEffect(state.errorNotice) {
        state.errorNotice?.let { notice ->
            noticeController.show(notice)
            viewModel.dismissErrorNotice()
        }
    }

    EventsScreen(
        state = state,
        onToggleExportMode = viewModel::toggleExportMode,
        onSearchIdChanged = viewModel::onSearchIdChanged,
        onSearchIdCommitted = viewModel::onSearchIdCommitted,
        onOpenDatePicker = viewModel::openDatePicker,
        onResetDate = viewModel::onResetDate,
        onDateSelectionChanged = viewModel::onDateSelectionChanged,
        onCloseDatePicker = viewModel::closeDatePicker,
        onEventTypeToggled = viewModel::onEventTypeToggled,
        onDeviceToggled = viewModel::onDeviceToggled,
        onLocationToggled = viewModel::onLocationToggled,
        onEventTypesApplied = viewModel::onEventTypesApplied,
        onDevicesApplied = viewModel::onDevicesApplied,
        onValidityChanged = viewModel::onValidityChanged,
        onResetFilters = viewModel::resetFilters,
        onLoadNextPage = viewModel::loadNextPage,
        onRefresh = viewModel::refresh,
        onViewportAtTop = viewModel::onViewportAtTop,
        onSelectEvent = viewModel::selectEvent,
        onCloseViewerCompact = viewModel::closeViewerCompact,
        onAcknowledgeEvent = viewModel::acknowledgeEvent,
        onRequestMarkErroneous = viewModel::requestMarkErroneous,
        onConfirmMarkErroneous = viewModel::confirmMarkErroneous,
        onCancelMarkErroneous = viewModel::cancelMarkErroneous,
        onCopyEventId = viewModel::copyEventId,
        onFormatPresetChanged = viewModel::setExportFormatPreset,
        onIncludeMediaLinksChanged = viewModel::setExportIncludeMediaLinks,
        onStartExport = viewModel::startExport,
        onDownloadExport = viewModel::downloadExport,
        onOpenFilterDialog = viewModel::openFilterDialog,
        onCloseFilterDialog = viewModel::closeFilterDialog,
        onOpenVideo = mediaOpener::openMedia,
        onOffscreenLiveEventsDetected = viewModel::onOffscreenLiveEventsDetected,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    state: EventsState,
    onToggleExportMode: () -> Unit,
    onSearchIdChanged: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
    onDateSelectionChanged: (DateSelection) -> Unit,
    onCloseDatePicker: () -> Unit,
    onEventTypeToggled: (String) -> Unit,
    onDeviceToggled: (String) -> Unit,
    onLocationToggled: (LocationFilterGroup) -> Unit,
    onValidityChanged: (EventValidity) -> Unit,
    onResetFilters: () -> Unit,
    onLoadNextPage: () -> Unit,
    onRefresh: () -> Unit,
    onViewportAtTop: () -> Unit,
    onSelectEvent: (String?) -> Unit,
    onCloseViewerCompact: () -> Unit,
    onAcknowledgeEvent: (Event) -> Unit,
    onRequestMarkErroneous: (String) -> Unit,
    onConfirmMarkErroneous: (String, Long) -> Unit,
    onCancelMarkErroneous: () -> Unit,
    onCopyEventId: (String) -> Unit,
    onFormatPresetChanged: (EventsExportFormatPreset) -> Unit,
    onIncludeMediaLinksChanged: (Boolean) -> Unit,
    onStartExport: () -> Unit,
    onDownloadExport: (String) -> Unit,
    onOpenFilterDialog: () -> Unit,
    onCloseFilterDialog: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchIdCommitted: ((String) -> Unit)? = null,
    onResetDate: (() -> Unit)? = null,
    onEventTypesApplied: ((Set<String>) -> Unit)? = null,
    onDevicesApplied: ((Set<String>) -> Unit)? = null,
    onOpenVideo: ((String) -> Unit)? = null,
    onOffscreenLiveEventsDetected: ((count: Int, key: String) -> Unit)? = null,
) {
    val currentToggleExportMode by rememberUpdatedState(onToggleExportMode)
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val windowWidth = with(density) { windowInfo.containerSize.width.toDp() }

    val businessTimeZone =
        remember(state.summary?.businessTimezone) {
            state.summary?.businessTimezone?.let {
                DateFilterUtils.resolveBusinessTimeZone(it)
            }
        }

    val topBarActions: @Composable RowScope.() -> Unit =
        remember(state.isExportModeActive, state.access.canExport) {
            {
                if (state.access.canExport) {
                    IconButton(onClick = { currentToggleExportMode() }) {
                        Icon(
                            painter =
                                painterResource(
                                    if (state.isExportModeActive) {
                                        Res.drawable.ic_bar_chart
                                    } else {
                                        Res.drawable.ic_download
                                    },
                                ),
                            contentDescription =
                                stringResource(
                                    if (state.isExportModeActive) {
                                        Res.string.events_show_statistics
                                    } else {
                                        Res.string.events_export_csv
                                    },
                                ),
                            modifier = Modifier.size(AmberDimens.ListIcon),
                        )
                    }
                }
            }
        }

    ProvideTopBar(
        title = stringResource(Res.string.nav_item_events),
        leadingAction = {
            TuneValidityFilterAction(
                validity = state.filter.validity,
                onValidityChanged = onValidityChanged,
            )
        },
        actions = topBarActions,
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
        val usableContentWidth = maxWidth - AmberDimens.ScreenMargin * 2
        val layoutMode =
            calculateEventsLayoutMode(
                windowWidth = windowWidth,
                usableContentWidth = usableContentWidth,
            )

        when (layoutMode) {
            EventsLayoutMode.Wide -> {
                WideEventsLayout(
                    state = state,
                    listState = listState,
                    businessTimeZone = businessTimeZone,
                    onSearchIdChanged = onSearchIdChanged,
                    onSearchIdCommitted = onSearchIdCommitted ?: onSearchIdChanged,
                    onOpenDatePicker = onOpenDatePicker,
                    onResetDate = onResetDate ?: {},
                    onEventTypesApplied = onEventTypesApplied ?: {},
                    onDevicesApplied = onDevicesApplied ?: {},
                    onResetFilters = onResetFilters,
                    onRefresh = onRefresh,
                    onSelectEvent = onSelectEvent,
                    onAcknowledgeEvent = onAcknowledgeEvent,
                    onLoadNextPage = onLoadNextPage,
                    onViewportAtTop = onViewportAtTop,
                    onCopyEventId = onCopyEventId,
                    onRequestMarkErroneous = onRequestMarkErroneous,
                    onConfirmMarkErroneous = onConfirmMarkErroneous,
                    onCancelMarkErroneous = onCancelMarkErroneous,
                    onFormatPresetChanged = onFormatPresetChanged,
                    onIncludeMediaLinksChanged = onIncludeMediaLinksChanged,
                    onStartExport = onStartExport,
                    onDownloadExport = onDownloadExport,
                    onOpenVideo = onOpenVideo,
                    onOffscreenLiveEventsDetected = onOffscreenLiveEventsDetected,
                )
            }
            EventsLayoutMode.Medium -> {
                MediumEventsLayout(
                    state = state,
                    listState = listState,
                    businessTimeZone = businessTimeZone,
                    onSearchIdChanged = onSearchIdChanged,
                    onSearchIdCommitted = onSearchIdCommitted ?: onSearchIdChanged,
                    onOpenDatePicker = onOpenDatePicker,
                    onResetDate = onResetDate ?: {},
                    onEventTypesApplied = onEventTypesApplied ?: {},
                    onDevicesApplied = onDevicesApplied ?: {},
                    onResetFilters = onResetFilters,
                    onRefresh = onRefresh,
                    onSelectEvent = onSelectEvent,
                    onAcknowledgeEvent = onAcknowledgeEvent,
                    onLoadNextPage = onLoadNextPage,
                    onViewportAtTop = onViewportAtTop,
                    onCopyEventId = onCopyEventId,
                    onRequestMarkErroneous = onRequestMarkErroneous,
                    onConfirmMarkErroneous = onConfirmMarkErroneous,
                    onCancelMarkErroneous = onCancelMarkErroneous,
                    onFormatPresetChanged = onFormatPresetChanged,
                    onIncludeMediaLinksChanged = onIncludeMediaLinksChanged,
                    onStartExport = onStartExport,
                    onDownloadExport = onDownloadExport,
                    onOpenVideo = onOpenVideo,
                    onOffscreenLiveEventsDetected = onOffscreenLiveEventsDetected,
                )
            }
            EventsLayoutMode.Compact -> {
                CompactEventsLayout(
                    state = state,
                    listState = listState,
                    businessTimeZone = businessTimeZone,
                    onSearchIdChanged = onSearchIdChanged,
                    onSearchIdCommitted = onSearchIdCommitted ?: onSearchIdChanged,
                    onOpenDatePicker = onOpenDatePicker,
                    onResetDate = onResetDate ?: {},
                    onEventTypesApplied = onEventTypesApplied ?: {},
                    onDevicesApplied = onDevicesApplied ?: {},
                    onOpenFilterDialog = onOpenFilterDialog,
                    onCloseFilterDialog = onCloseFilterDialog,
                    onResetFilters = onResetFilters,
                    onRefresh = onRefresh,
                    onSelectEvent = onSelectEvent,
                    onCloseViewerCompact = onCloseViewerCompact,
                    onAcknowledgeEvent = onAcknowledgeEvent,
                    onLoadNextPage = onLoadNextPage,
                    onViewportAtTop = onViewportAtTop,
                    onCopyEventId = onCopyEventId,
                    onRequestMarkErroneous = onRequestMarkErroneous,
                    onConfirmMarkErroneous = onConfirmMarkErroneous,
                    onCancelMarkErroneous = onCancelMarkErroneous,
                    onFormatPresetChanged = onFormatPresetChanged,
                    onIncludeMediaLinksChanged = onIncludeMediaLinksChanged,
                    onStartExport = onStartExport,
                    onDownloadExport = onDownloadExport,
                    onOpenVideo = onOpenVideo,
                    onOffscreenLiveEventsDetected = onOffscreenLiveEventsDetected,
                )
            }
        }

        // Date Picker Dialog
        if (state.isDatePickerOpen) {
            val hasDateFilter = state.filter.dateFrom != null || state.filter.dateTo != null
            val parsedSelection: DateSelection? =
                remember(state.filter.dateFrom, state.filter.dateTo, businessTimeZone) {
                    if (businessTimeZone != null && hasDateFilter) {
                        try {
                            DateFilterUtils.utcBoundsToDateSelection(
                                dateFrom = state.filter.dateFrom,
                                dateTo = state.filter.dateTo,
                                businessTimeZone = businessTimeZone,
                            )
                        } catch (_: Exception) {
                            null
                        }
                    } else if (!hasDateFilter) {
                        DateSelection.All
                    } else {
                        null
                    }
                }
            val isCustomInterval = hasDateFilter && parsedSelection == null
            EventsDatePickerDialog(
                initialSelection = parsedSelection ?: DateSelection.All,
                isCustomInterval = isCustomInterval,
                timeZone = businessTimeZone,
                onDismissRequest = onCloseDatePicker,
                onApply = onDateSelectionChanged,
                onReset = onResetDate ?: { onDateSelectionChanged(DateSelection.All) },
            )
        }
    }
}

@Composable
private fun WideEventsLayout(
    state: EventsState,
    businessTimeZone: kotlinx.datetime.TimeZone?,
    onSearchIdChanged: (String) -> Unit,
    onSearchIdCommitted: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
    onResetDate: () -> Unit,
    onEventTypesApplied: (Set<String>) -> Unit,
    onDevicesApplied: (Set<String>) -> Unit,
    onResetFilters: () -> Unit,
    onRefresh: () -> Unit,
    onSelectEvent: (String?) -> Unit,
    onAcknowledgeEvent: (Event) -> Unit,
    onLoadNextPage: () -> Unit,
    onViewportAtTop: () -> Unit,
    onCopyEventId: (String) -> Unit,
    onRequestMarkErroneous: (String) -> Unit,
    onConfirmMarkErroneous: (String, Long) -> Unit,
    onCancelMarkErroneous: () -> Unit,
    onFormatPresetChanged: (EventsExportFormatPreset) -> Unit,
    onIncludeMediaLinksChanged: (Boolean) -> Unit,
    onStartExport: () -> Unit,
    onDownloadExport: (String) -> Unit,
    onOpenVideo: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onOffscreenLiveEventsDetected: ((count: Int, key: String) -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(AmberDimens.ScreenMargin),
    ) {
        // Upper row: Filters on left, Summary/Export slot on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
        ) {
            EventsFilters(
                filter = state.filter,
                filterOptions = state.filterOptions,
                onSearchIdChanged = onSearchIdChanged,
                onSearchIdCommitted = onSearchIdCommitted,
                onOpenDatePicker = onOpenDatePicker,
                onResetDate = onResetDate,
                onEventTypesApplied = onEventTypesApplied,
                onDevicesApplied = onDevicesApplied,
                businessTimeZone = businessTimeZone,
                isFilterOptionsLoading = state.isFilterOptionsLoading,
                isCompact = false,
                modifier = Modifier.weight(1f),
            )
            EventsSummaryExportSlot(
                state = state,
                isCompact = false,
                onFormatPresetChanged = onFormatPresetChanged,
                onIncludeMediaLinksChanged = onIncludeMediaLinksChanged,
                onStartExport = onStartExport,
                onDownload = onDownloadExport,
                modifier = Modifier.width(AmberDimens.EventsListWide),
            )
        }

        Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

        // Lower row: List on left (360.dp), EventViewer on right (weight(1f))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(AmberDimens.EventsListWide)
                        .fillMaxHeight()
                        .clipToBounds(),
            ) {
                EventsList(
                    events = state.visibleEvents,
                    selectedEventId = state.selectedEventId,
                    pendingAckIds = state.pendingAckIds,
                    canAcknowledge = state.access.canAcknowledge,
                    pendingNewerCount = state.pendingNewerCount,
                    hasMore = state.hasMore,
                    isLoadingNextPage = state.isLoadingNextPage,
                    businessTimeZone = businessTimeZone,
                    isCompact = false,
                    isViewerOpenInCompact = state.isViewerOpenInCompact,
                    listState = listState,
                    feedScopeKey = state.feedScopeKey,
                    liveCreatedIds = state.liveCreatedIds,
                    liveRemovedIds = state.liveRemovedIds,
                    isInitialLoading = state.isInitialLoading,
                    pendingHistoryCount = state.pendingHistoryCount,
                    requiresRefresh = state.requiresRefresh,
                    paginationError = state.paginationError,
                    onRefreshHistory = onRefresh,
                    onSelectEvent = onSelectEvent,
                    onAcknowledgeEvent = onAcknowledgeEvent,
                    onLoadNextPage = onLoadNextPage,
                    onViewportAtTop = onViewportAtTop,
                    onOffscreenLiveEventsDetected = onOffscreenLiveEventsDetected,
                )
                if (state.isInitialLoading || state.initialLoadError != null || state.visibleEvents.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                    ) {
                        EventsEmptyState(
                            isLoading = state.isInitialLoading,
                            initialLoadError = state.initialLoadError,
                            hasActiveFilters = state.filter.hasActiveFilters,
                            onResetFilters = onResetFilters,
                            onRetry = onRefresh,
                        )
                    }
                }
            }

            // Reserved EventViewer container
            ReservedEventViewerContainer(
                selectedEvent = state.selectedEvent,
                canAcknowledge = state.access.canAcknowledge,
                isMarkingErroneous = state.isMarkingErroneous,
                isConfirmationOpen = state.erroneousConfirmationEventId != null,
                confirmationTarget = state.confirmationTarget,
                timeZone = businessTimeZone,
                onCopyEventId = onCopyEventId,
                onRequestMarkErroneous = onRequestMarkErroneous,
                onConfirmMarkErroneous = onConfirmMarkErroneous,
                onCancelMarkErroneous = onCancelMarkErroneous,
                onOpenVideo = onOpenVideo,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun MediumEventsLayout(
    state: EventsState,
    businessTimeZone: kotlinx.datetime.TimeZone?,
    onSearchIdChanged: (String) -> Unit,
    onSearchIdCommitted: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
    onResetDate: () -> Unit,
    onEventTypesApplied: (Set<String>) -> Unit,
    onDevicesApplied: (Set<String>) -> Unit,
    onResetFilters: () -> Unit,
    onRefresh: () -> Unit,
    onSelectEvent: (String?) -> Unit,
    onAcknowledgeEvent: (Event) -> Unit,
    onLoadNextPage: () -> Unit,
    onViewportAtTop: () -> Unit,
    onCopyEventId: (String) -> Unit,
    onRequestMarkErroneous: (String) -> Unit,
    onConfirmMarkErroneous: (String, Long) -> Unit,
    onCancelMarkErroneous: () -> Unit,
    onFormatPresetChanged: (EventsExportFormatPreset) -> Unit,
    onIncludeMediaLinksChanged: (Boolean) -> Unit,
    onStartExport: () -> Unit,
    onDownloadExport: (String) -> Unit,
    onOpenVideo: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onOffscreenLiveEventsDetected: ((count: Int, key: String) -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(AmberDimens.ScreenMargin),
    ) {
        // Upper row: Filters across full width (2x2 grid)
        EventsFilters(
            filter = state.filter,
            filterOptions = state.filterOptions,
            onSearchIdChanged = onSearchIdChanged,
            onSearchIdCommitted = onSearchIdCommitted,
            onOpenDatePicker = onOpenDatePicker,
            onResetDate = onResetDate,
            onEventTypesApplied = onEventTypesApplied,
            onDevicesApplied = onDevicesApplied,
            businessTimeZone = businessTimeZone,
            isFilterOptionsLoading = state.isFilterOptionsLoading,
            isCompact = false,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

        // Lower row: List on left (320.dp), Right Column: Summary/Export + EventViewer
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(AmberDimens.EventsListMedium)
                        .fillMaxHeight()
                        .clipToBounds(),
            ) {
                EventsList(
                    events = state.visibleEvents,
                    selectedEventId = state.selectedEventId,
                    pendingAckIds = state.pendingAckIds,
                    canAcknowledge = state.access.canAcknowledge,
                    pendingNewerCount = state.pendingNewerCount,
                    hasMore = state.hasMore,
                    isLoadingNextPage = state.isLoadingNextPage,
                    businessTimeZone = businessTimeZone,
                    isCompact = false,
                    isViewerOpenInCompact = state.isViewerOpenInCompact,
                    listState = listState,
                    feedScopeKey = state.feedScopeKey,
                    liveCreatedIds = state.liveCreatedIds,
                    liveRemovedIds = state.liveRemovedIds,
                    isInitialLoading = state.isInitialLoading,
                    pendingHistoryCount = state.pendingHistoryCount,
                    requiresRefresh = state.requiresRefresh,
                    paginationError = state.paginationError,
                    onRefreshHistory = onRefresh,
                    onSelectEvent = onSelectEvent,
                    onAcknowledgeEvent = onAcknowledgeEvent,
                    onLoadNextPage = onLoadNextPage,
                    onViewportAtTop = onViewportAtTop,
                    onOffscreenLiveEventsDetected = onOffscreenLiveEventsDetected,
                )
                if (state.isInitialLoading || state.initialLoadError != null || state.visibleEvents.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                    ) {
                        EventsEmptyState(
                            isLoading = state.isInitialLoading,
                            initialLoadError = state.initialLoadError,
                            hasActiveFilters = state.filter.hasActiveFilters,
                            onResetFilters = onResetFilters,
                            onRetry = onRefresh,
                        )
                    }
                }
            }

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
            ) {
                EventsSummaryExportSlot(
                    state = state,
                    isCompact = false,
                    onFormatPresetChanged = onFormatPresetChanged,
                    onIncludeMediaLinksChanged = onIncludeMediaLinksChanged,
                    onStartExport = onStartExport,
                    onDownload = onDownloadExport,
                    modifier = Modifier.fillMaxWidth(),
                )

                ReservedEventViewerContainer(
                    selectedEvent = state.selectedEvent,
                    canAcknowledge = state.access.canAcknowledge,
                    isMarkingErroneous = state.isMarkingErroneous,
                    isConfirmationOpen = state.erroneousConfirmationEventId != null,
                    confirmationTarget = state.confirmationTarget,
                    timeZone = businessTimeZone,
                    onCopyEventId = onCopyEventId,
                    onRequestMarkErroneous = onRequestMarkErroneous,
                    onConfirmMarkErroneous = onConfirmMarkErroneous,
                    onCancelMarkErroneous = onCancelMarkErroneous,
                    onOpenVideo = onOpenVideo,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactEventsLayout(
    state: EventsState,
    businessTimeZone: kotlinx.datetime.TimeZone?,
    onSearchIdChanged: (String) -> Unit,
    onSearchIdCommitted: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
    onResetDate: () -> Unit,
    onEventTypesApplied: (Set<String>) -> Unit,
    onDevicesApplied: (Set<String>) -> Unit,
    onOpenFilterDialog: () -> Unit,
    onCloseFilterDialog: () -> Unit,
    onResetFilters: () -> Unit,
    onRefresh: () -> Unit,
    onSelectEvent: (String?) -> Unit,
    onCloseViewerCompact: () -> Unit,
    onAcknowledgeEvent: (Event) -> Unit,
    onLoadNextPage: () -> Unit,
    onViewportAtTop: () -> Unit,
    onCopyEventId: (String) -> Unit,
    onRequestMarkErroneous: (String) -> Unit,
    onConfirmMarkErroneous: (String, Long) -> Unit,
    onCancelMarkErroneous: () -> Unit,
    onFormatPresetChanged: (EventsExportFormatPreset) -> Unit,
    onIncludeMediaLinksChanged: (Boolean) -> Unit,
    onStartExport: () -> Unit,
    onDownloadExport: (String) -> Unit,
    onOpenVideo: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onOffscreenLiveEventsDetected: ((count: Int, key: String) -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(AmberDimens.ScreenMargin),
    ) {
        // Pinned CompactFilterBar directly below TopAppBar
        CompactFilterBar(
            isExpanded = state.isFilterDialogOpen,
            activeFiltersCount = state.filter.activeFiltersCount,
            onToggle = {
                if (state.isFilterDialogOpen) {
                    onCloseFilterDialog()
                } else {
                    onOpenFilterDialog()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Animated pinned filters panel (not part of LazyColumn)
        AnimatedVisibility(
            visible = state.isFilterDialogOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = AmberDimens.GapSmall),
            ) {
                EventsFilters(
                    filter = state.filter,
                    filterOptions = state.filterOptions,
                    onSearchIdChanged = onSearchIdChanged,
                    onSearchIdCommitted = onSearchIdCommitted,
                    onOpenDatePicker = onOpenDatePicker,
                    onResetDate = onResetDate,
                    onEventTypesApplied = onEventTypesApplied,
                    onDevicesApplied = onDevicesApplied,
                    businessTimeZone = businessTimeZone,
                    isFilterOptionsLoading = state.isFilterOptionsLoading,
                    isCompact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(AmberDimens.GapSmall))

        // EventsSummaryExportSlot shell with fixed height
        EventsSummaryExportSlot(
            state = state,
            isCompact = true,
            onFormatPresetChanged = onFormatPresetChanged,
            onIncludeMediaLinksChanged = onIncludeMediaLinksChanged,
            onStartExport = onStartExport,
            onDownload = onDownloadExport,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(AmberDimens.GapSmall))

        // EventsList occupies remaining height
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds(),
        ) {
            EventsList(
                events = state.visibleEvents,
                selectedEventId = state.selectedEventId,
                pendingAckIds = state.pendingAckIds,
                canAcknowledge = state.access.canAcknowledge,
                pendingNewerCount = state.pendingNewerCount,
                hasMore = state.hasMore,
                isLoadingNextPage = state.isLoadingNextPage,
                businessTimeZone = businessTimeZone,
                isCompact = true,
                isViewerOpenInCompact = state.isViewerOpenInCompact,
                listState = listState,
                feedScopeKey = state.feedScopeKey,
                liveCreatedIds = state.liveCreatedIds,
                liveRemovedIds = state.liveRemovedIds,
                isInitialLoading = state.isInitialLoading,
                pendingHistoryCount = state.pendingHistoryCount,
                requiresRefresh = state.requiresRefresh,
                paginationError = state.paginationError,
                onRefreshHistory = onRefresh,
                onSelectEvent = onSelectEvent,
                onAcknowledgeEvent = onAcknowledgeEvent,
                onLoadNextPage = onLoadNextPage,
                onViewportAtTop = onViewportAtTop,
                onOffscreenLiveEventsDetected = onOffscreenLiveEventsDetected,
            )
            if (state.isInitialLoading || state.initialLoadError != null || state.visibleEvents.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                ) {
                    EventsEmptyState(
                        isLoading = state.isInitialLoading,
                        initialLoadError = state.initialLoadError,
                        hasActiveFilters = state.filter.hasActiveFilters,
                        onResetFilters = onResetFilters,
                        onRetry = onRefresh,
                    )
                }
            }
        }

        // Compact Viewer Modal
        if (state.isViewerOpenInCompact && state.selectedEvent != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = onCloseViewerCompact,
                sheetState = sheetState,
            ) {
                EventViewer(
                    event = state.selectedEvent,
                    canAcknowledge = state.access.canAcknowledge,
                    isMarkingErroneous = state.isMarkingErroneous,
                    isConfirmationOpen = state.erroneousConfirmationEventId != null,
                    confirmationTarget = state.confirmationTarget,
                    timeZone = businessTimeZone,
                    onCopyId = onCopyEventId,
                    onRequestMarkErroneous = onRequestMarkErroneous,
                    onConfirmMarkErroneous = onConfirmMarkErroneous,
                    onCancelMarkErroneous = onCancelMarkErroneous,
                    onCloseCompact = onCloseViewerCompact,
                    onOpenVideo = onOpenVideo,
                )
            }
        }
    }
}

@Composable
private fun CompactFilterBar(
    isExpanded: Boolean,
    activeFiltersCount: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .height(AmberDimens.EventsCompactFilterBarHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                ),
        shape = RoundedCornerShape(AmberCorners.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = AmberDimens.ScreenMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.events_filters_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (activeFiltersCount > 0) {
                Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                Surface(
                    shape = RoundedCornerShape(AmberCorners.Small),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "$activeFiltersCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier =
                            Modifier.padding(
                                horizontal = AmberDimens.StatusBadgePaddingHorizontal,
                                vertical = AmberDimens.StatusBadgePaddingVertical,
                            ),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Icon(
                painter =
                    painterResource(
                        if (isExpanded) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more,
                    ),
                contentDescription = null,
                modifier = Modifier.size(AmberDimens.ListIcon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReservedEventViewerContainer(
    selectedEvent: Event?,
    canAcknowledge: Boolean,
    isMarkingErroneous: Boolean,
    isConfirmationOpen: Boolean,
    confirmationTarget: studio.acmenra.control.feature.events.presentation.ErroneousConfirmationTarget?,
    timeZone: kotlinx.datetime.TimeZone?,
    onCopyEventId: (String) -> Unit,
    onRequestMarkErroneous: (String) -> Unit,
    onConfirmMarkErroneous: (String, Long) -> Unit,
    onCancelMarkErroneous: () -> Unit,
    onOpenVideo: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (selectedEvent != null) {
            EventViewer(
                event = selectedEvent,
                canAcknowledge = canAcknowledge,
                isMarkingErroneous = isMarkingErroneous,
                isConfirmationOpen = isConfirmationOpen,
                confirmationTarget = confirmationTarget,
                timeZone = timeZone,
                onCopyId = onCopyEventId,
                onRequestMarkErroneous = onRequestMarkErroneous,
                onConfirmMarkErroneous = onConfirmMarkErroneous,
                onCancelMarkErroneous = onCancelMarkErroneous,
                onOpenVideo = onOpenVideo,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(AmberDimens.CardPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_videocam),
                        contentDescription = null,
                        modifier = Modifier.size(AmberDimens.ListIconCircle),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.height(AmberDimens.GapMedium))
                    Text(
                        text = stringResource(Res.string.events_viewer_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun TuneValidityFilterAction(
    validity: EventValidity,
    onValidityChanged: (EventValidity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val isFiltered = validity != EventValidity.ALL

    Box(modifier = modifier) {
        if (isFiltered) {
            FilledTonalIconButton(
                onClick = { expanded = true },
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_tune),
                    contentDescription = stringResource(Res.string.events_filter_tune),
                    modifier = Modifier.size(AmberDimens.ListIcon),
                )
            }
        } else {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_tune),
                    contentDescription = stringResource(Res.string.events_filter_tune),
                    modifier = Modifier.size(AmberDimens.ListIcon),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(Res.string.events_validity_all),
                        fontWeight = if (validity == EventValidity.ALL) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = {
                    expanded = false
                    onValidityChanged(EventValidity.ALL)
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(Res.string.events_validity_valid_only),
                        fontWeight = if (validity == EventValidity.VALID) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = {
                    expanded = false
                    onValidityChanged(EventValidity.VALID)
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(Res.string.events_validity_erroneous_only),
                        fontWeight = if (validity == EventValidity.ERRONEOUS) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = {
                    expanded = false
                    onValidityChanged(EventValidity.ERRONEOUS)
                },
            )
        }
    }
}
