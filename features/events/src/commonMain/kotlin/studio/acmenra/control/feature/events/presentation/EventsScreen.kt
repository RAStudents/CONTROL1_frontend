package studio.acmenra.control.feature.events.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import studio.acmenra.control.core.ui.components.ProvideTopBar
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_export_csv
import studio.acmenra.control.core.ui.generated.resources.events_filter_tune
import studio.acmenra.control.core.ui.generated.resources.events_show_statistics
import studio.acmenra.control.core.ui.generated.resources.events_viewer_id_copied
import studio.acmenra.control.core.ui.generated.resources.ic_bar_chart
import studio.acmenra.control.core.ui.generated.resources.ic_download
import studio.acmenra.control.core.ui.generated.resources.ic_tune
import studio.acmenra.control.core.ui.generated.resources.nav_item_events
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.core.ui.notice.NoticeController
import studio.acmenra.control.core.ui.theme.AmberBreakpoints
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventValidity
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
import studio.acmenra.control.feature.events.presentation.components.EventViewer
import studio.acmenra.control.feature.events.presentation.components.EventsDatePickerDialog
import studio.acmenra.control.feature.events.presentation.components.EventsEmptyState
import studio.acmenra.control.feature.events.presentation.components.EventsExportPanel
import studio.acmenra.control.feature.events.presentation.components.EventsFilters
import studio.acmenra.control.feature.events.presentation.components.EventsList
import studio.acmenra.control.feature.events.presentation.components.EventsSummaryCard
import studio.acmenra.control.feature.events.presentation.platform.ExternalMediaOpener

@Composable
fun EventsRouteContent(
    companyId: String = "",
    accessCapabilities: EventsAccessCapabilities = EventsAccessCapabilities(),
    modifier: Modifier = Modifier,
    viewModel: EventsViewModel = koinViewModel(key = companyId),
) {
    LaunchedEffect(companyId, accessCapabilities) {
        viewModel.initialize(companyId, accessCapabilities)
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
        onOpenDatePicker = viewModel::openDatePicker,
        onDateSelectionChanged = viewModel::onDateSelectionChanged,
        onCloseDatePicker = viewModel::closeDatePicker,
        onEventTypeToggled = viewModel::onEventTypeToggled,
        onDeviceToggled = viewModel::onDeviceToggled,
        onLocationToggled = viewModel::onLocationToggled,
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
    onSelectEvent: (String) -> Unit,
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
    onOpenVideo: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val currentToggleExportMode by rememberUpdatedState(onToggleExportMode)
    val currentOpenFilterDialog by rememberUpdatedState(onOpenFilterDialog)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isCompact = maxWidth < AmberBreakpoints.EventsCompact || maxWidth < AmberDimens.EventsMediumWorkspaceMin
        val isWide = maxWidth >= AmberBreakpoints.EventsWide

        val businessTimeZone =
            remember(state.summary?.businessTimezone) {
                state.summary?.businessTimezone?.let {
                    runCatching { TimeZone.of(it) }.getOrNull()
                }
            }

        val topBarActions: @Composable RowScope.() -> Unit =
            remember(state.isExportModeActive, state.access.reportsExport, isCompact) {
                {
                    if (isCompact) {
                        IconButton(onClick = { currentOpenFilterDialog() }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_tune),
                                contentDescription = stringResource(Res.string.events_filter_tune),
                                modifier = Modifier.size(AmberDimens.ListIcon),
                            )
                        }
                    }
                    if (state.access.reportsExport) {
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
            leadingAction = null,
            actions = topBarActions,
        )

        if (isCompact) {
            // Compact: 1 column
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(AmberDimens.ScreenMargin),
            ) {
                if (state.isExportModeActive) {
                    EventsExportPanel(
                        formatPreset = state.exportFormatPreset,
                        includeMediaLinks = state.exportIncludeMediaLinks,
                        totalRecords = state.snapshotTotalCount,
                        jobStatus = state.exportJobStatus,
                        isSubmitting = state.isExportSubmitting,
                        errorMessage = state.exportError,
                        onFormatPresetChanged = onFormatPresetChanged,
                        onIncludeMediaLinksChanged = onIncludeMediaLinksChanged,
                        onStartExport = onStartExport,
                        onDownload = onDownloadExport,
                    )
                } else {
                    EventsSummaryCard(summary = state.summary)
                }

                Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

                if (state.isInitialLoading || state.initialLoadError != null || state.visibleEvents.isEmpty()) {
                    EventsEmptyState(
                        isLoading = state.isInitialLoading,
                        initialLoadError = state.initialLoadError,
                        hasActiveFilters = state.filter.hasActiveFilters,
                        onResetFilters = onResetFilters,
                        onRetry = onRefresh,
                    )
                } else {
                    EventsList(
                        events = state.visibleEvents,
                        selectedEventId = state.selectedEventId,
                        pendingAckIds = state.pendingAckIds,
                        canAcknowledge = state.access.canAcknowledge,
                        pendingNewerCount = state.pendingNewerCount,
                        hasMore = state.hasMore,
                        isLoadingNextPage = state.isLoadingNextPage,
                        timeZone = businessTimeZone,
                        onSelectEvent = onSelectEvent,
                        onAcknowledgeEvent = onAcknowledgeEvent,
                        onLoadNextPage = onLoadNextPage,
                        onViewportAtTop = onViewportAtTop,
                    )
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
        } else {
            // Medium / Wide: 2 columns
            val leftWidth = if (isWide) AmberDimens.EventsListWide else AmberDimens.EventsListMedium

            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(AmberDimens.ScreenMargin),
            ) {
                // Left Column: List + Stats/Export + Filters
                Column(
                    modifier =
                        Modifier
                            .width(leftWidth)
                            .fillMaxHeight(),
                ) {
                    if (state.isExportModeActive) {
                        EventsExportPanel(
                            formatPreset = state.exportFormatPreset,
                            includeMediaLinks = state.exportIncludeMediaLinks,
                            totalRecords = state.snapshotTotalCount,
                            jobStatus = state.exportJobStatus,
                            isSubmitting = state.isExportSubmitting,
                            errorMessage = state.exportError,
                            onFormatPresetChanged = onFormatPresetChanged,
                            onIncludeMediaLinksChanged = onIncludeMediaLinksChanged,
                            onStartExport = onStartExport,
                            onDownload = onDownloadExport,
                        )
                    } else {
                        EventsSummaryCard(summary = state.summary)
                    }

                    Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

                    EventsFilters(
                        filter = state.filter,
                        filterOptions = state.filterOptions,
                        onSearchIdChanged = onSearchIdChanged,
                        onOpenDatePicker = onOpenDatePicker,
                        onEventTypeToggled = onEventTypeToggled,
                        onDeviceToggled = onDeviceToggled,
                        onLocationToggled = onLocationToggled,
                        onValidityChanged = onValidityChanged,
                        onResetFilters = onResetFilters,
                    )

                    Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

                    if (state.isInitialLoading || state.initialLoadError != null || state.visibleEvents.isEmpty()) {
                        EventsEmptyState(
                            isLoading = state.isInitialLoading,
                            initialLoadError = state.initialLoadError,
                            hasActiveFilters = state.filter.hasActiveFilters,
                            onResetFilters = onResetFilters,
                            onRetry = onRefresh,
                        )
                    } else {
                        EventsList(
                            events = state.visibleEvents,
                            selectedEventId = state.selectedEventId,
                            pendingAckIds = state.pendingAckIds,
                            canAcknowledge = state.access.canAcknowledge,
                            pendingNewerCount = state.pendingNewerCount,
                            hasMore = state.hasMore,
                            isLoadingNextPage = state.isLoadingNextPage,
                            timeZone = businessTimeZone,
                            onSelectEvent = onSelectEvent,
                            onAcknowledgeEvent = onAcknowledgeEvent,
                            onLoadNextPage = onLoadNextPage,
                            onViewportAtTop = onViewportAtTop,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(AmberDimens.GapMedium))

                // Right Column: Event Detail Viewer
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
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
                        onOpenVideo = onOpenVideo,
                    )
                }
            }
        }

        // Date Picker Dialog
        if (state.isDatePickerOpen) {
            EventsDatePickerDialog(
                initialSelection = state.filter.dateSelection,
                timeZone = businessTimeZone,
                onDismissRequest = onCloseDatePicker,
                onApply = onDateSelectionChanged,
                onReset = { onDateSelectionChanged(DateSelection.All) },
            )
        }
    }
}
