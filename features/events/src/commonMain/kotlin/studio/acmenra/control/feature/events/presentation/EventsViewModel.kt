package studio.acmenra.control.feature.events.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_export_download_error
import studio.acmenra.control.core.ui.generated.resources.events_export_error_access_revoked
import studio.acmenra.control.core.ui.generated.resources.events_export_error_generic
import studio.acmenra.control.core.ui.generated.resources.events_export_error_limit_exceeded
import studio.acmenra.control.core.ui.generated.resources.events_export_expired_msg
import studio.acmenra.control.core.ui.generated.resources.events_mutation_conflict
import studio.acmenra.control.core.ui.generated.resources.events_mutation_generic_error
import studio.acmenra.control.core.ui.generated.resources.events_mutation_network_error
import studio.acmenra.control.core.ui.generated.resources.events_mutation_payload_mismatch
import studio.acmenra.control.core.ui.generated.resources.events_mutation_permission_denied
import studio.acmenra.control.feature.events.data.datasource.EventsApiException
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventValidity
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsMutationError
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
import studio.acmenra.control.feature.events.domain.model.LocationToggleState
import studio.acmenra.control.feature.events.domain.model.locationToggleState
import studio.acmenra.control.feature.events.domain.repository.EventsFeedSession
import studio.acmenra.control.feature.events.domain.usecase.AcknowledgeEventUseCase
import studio.acmenra.control.feature.events.domain.usecase.CreateEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.DownloadEventsExportUseCase
import studio.acmenra.control.feature.events.domain.usecase.LoadEventFilterOptionsUseCase
import studio.acmenra.control.feature.events.domain.usecase.MarkEventErroneousUseCase
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsExportStatusUseCase
import studio.acmenra.control.feature.events.domain.usecase.ObserveEventsFeedUseCase
import studio.acmenra.control.feature.events.presentation.platform.ExportFileSaver

class EventsViewModel(
    private val observeEventsFeedUseCase: ObserveEventsFeedUseCase,
    private val loadEventFilterOptionsUseCase: LoadEventFilterOptionsUseCase,
    private val acknowledgeEventUseCase: AcknowledgeEventUseCase,
    private val markEventErroneousUseCase: MarkEventErroneousUseCase,
    private val createEventsExportUseCase: CreateEventsExportUseCase,
    private val observeEventsExportStatusUseCase: ObserveEventsExportStatusUseCase,
    private val downloadEventsExportUseCase: DownloadEventsExportUseCase,
    private val exportFileSaver: ExportFileSaver,
) : ViewModel() {
    private val _state = MutableStateFlow(EventsState())
    val state: StateFlow<EventsState> = _state.asStateFlow()

    private var feedSession: EventsFeedSession? = null
    private var feedCollectJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var exportObservationJob: Job? = null
    private var filterRequestGeneration: Long = 0L

    fun initialize(
        companyId: String,
        accessCapabilities: EventsAccessCapabilities,
    ) {
        val currentCompany = _state.value.companyId
        if (currentCompany == companyId && feedSession != null) {
            if (_state.value.access != accessCapabilities) {
                _state.update { it.copy(access = accessCapabilities) }
                feedSession?.updateAccessCapabilities(accessCapabilities)
            }
            return
        }

        _state.update {
            it.copy(
                companyId = companyId,
                access = accessCapabilities,
                isInitialLoading = true,
                initialLoadError = null,
                visibleEvents = emptyList(),
                selectedEventId = null,
            )
        }

        loadFilterOptions(companyId)
        restartFeedSession()
    }

    private fun loadFilterOptions(companyId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isFilterOptionsLoading = true) }
            try {
                val options = loadEventFilterOptionsUseCase(companyId)
                _state.update {
                    it.copy(
                        filterOptions = options,
                        isFilterOptionsLoading = false,
                    )
                }
            } catch (e: Throwable) {
                _state.update { it.copy(isFilterOptionsLoading = false) }
            }
        }
    }

    private fun restartFeedSession() {
        val companyId = _state.value.companyId
        if (companyId.isBlank()) return

        val currentGen = ++filterRequestGeneration
        feedCollectJob?.cancel()
        feedSession?.close()

        val query = EventsQuery(filter = _state.value.filter)
        val session = observeEventsFeedUseCase(companyId, query, _state.value.access)
        feedSession = session

        feedCollectJob =
            viewModelScope.launch {
                session.state.collect { feedState ->
                    if (currentGen != filterRequestGeneration) return@collect
                    _state.update { current ->
                        current.copy(
                            visibleEvents = feedState.visibleEvents,
                            realtimeOverlay = feedState.realtimeOverlay,
                            asOfSequence = feedState.asOfSequence,
                            snapshotTotalCount = feedState.snapshotTotalCount,
                            hasMore = feedState.hasMore,
                            isInitialLoading = feedState.isInitialLoading,
                            initialLoadError = feedState.initialLoadError,
                            isLoadingNextPage = feedState.isLoadingNextPage,
                            paginationError = feedState.paginationError,
                            connectionState = feedState.connectionState,
                            pendingNewerCount = feedState.pendingNewerCount,
                            pendingHistoryCount = feedState.pendingHistoryCount,
                            summary = feedState.summary,
                            access = feedState.access,
                        )
                    }
                }
            }
    }

    fun onSearchIdChanged(newSearchId: String) {
        val trimmed = newSearchId.trim()
        val current = _state.value.filter.searchId ?: ""
        if (trimmed == current) return

        _state.update {
            it.copy(filter = it.filter.copy(searchId = trimmed.ifBlank { null }))
        }

        searchDebounceJob?.cancel()
        searchDebounceJob =
            viewModelScope.launch {
                delay(400L)
                restartFeedSession()
            }
    }

    fun onDateSelectionChanged(
        dateSelection: DateSelection,
        businessTimeZone: TimeZone? = null,
    ) {
        val tz =
            businessTimeZone
                ?: _state.value.summary
                    ?.businessTimezone
                    ?.let { runCatching { TimeZone.of(it) }.getOrNull() }
                ?: TimeZone.currentSystemDefault()
        val (from, to) = DateFilterUtils.dateSelectionToUtcBounds(dateSelection, tz)
        val newFilter =
            _state.value.filter.copy(
                dateSelection = dateSelection,
                dateFrom = from,
                dateTo = to,
            )
        _state.update { it.copy(filter = newFilter, isDatePickerOpen = false) }
        restartFeedSession()
    }

    fun onEventTypeToggled(typeId: String) {
        val current = _state.value.filter.eventTypeIds
        val updated = if (typeId in current) current - typeId else current + typeId
        _state.update { it.copy(filter = it.filter.copy(eventTypeIds = updated)) }
        restartFeedSession()
    }

    fun onDeviceToggled(deviceId: String) {
        val current = _state.value.filter.deviceIds
        val updated = if (deviceId in current) current - deviceId else current + deviceId
        _state.update { it.copy(filter = it.filter.copy(deviceIds = updated)) }
        restartFeedSession()
    }

    fun onLocationToggled(location: LocationFilterGroup) {
        val current = _state.value.filter.deviceIds
        val locationDevices = location.devices.map { it.id }.toSet()
        val toggleState = locationToggleState(location, current)
        val updated =
            if (toggleState == LocationToggleState.ON) {
                current - locationDevices
            } else {
                current + locationDevices
            }
        _state.update { it.copy(filter = it.filter.copy(deviceIds = updated)) }
        restartFeedSession()
    }

    fun onValidityChanged(validity: EventValidity) {
        if (_state.value.filter.validity == validity) return
        _state.update { it.copy(filter = it.filter.copy(validity = validity)) }
        restartFeedSession()
    }

    fun resetFilters() {
        _state.update { it.copy(filter = EventsFilter()) }
        restartFeedSession()
    }

    fun loadNextPage() {
        feedSession?.loadNextPage()
    }

    fun refresh() {
        feedSession?.refresh()
    }

    fun onViewportAtTop() {
        feedSession?.onViewportAtTop()
    }

    fun selectEvent(eventId: String?) {
        _state.update {
            it.copy(
                selectedEventId = eventId,
                isViewerOpenInCompact = eventId != null,
            )
        }
        if (eventId != null) {
            val event = _state.value.visibleEvents.firstOrNull { it.id == eventId }
            if (event != null && event.canAcknowledge(_state.value.access)) {
                acknowledgeEvent(event)
            }
        }
    }

    fun closeViewerCompact() {
        _state.update { it.copy(isViewerOpenInCompact = false) }
    }

    fun acknowledgeEvent(event: Event) {
        if (!_state.value.access.canAcknowledge || !event.canAcknowledge(_state.value.access)) return
        val companyId = _state.value.companyId

        feedSession?.markOptimisticAckPending(event.id)
        viewModelScope.launch {
            val result = acknowledgeEventUseCase(companyId, event.id)
            if (result is EventsOperationResult.Failure) {
                feedSession?.clearOptimisticAckPending(event.id)
                _state.update { it.copy(mutationErrorNotice = mapMutationErrorToResource(result.error)) }
            }
        }
    }

    fun requestMarkErroneous(eventId: String) {
        if (!_state.value.access.canAcknowledge) return
        _state.update { it.copy(erroneousConfirmationEventId = eventId) }
    }

    fun cancelMarkErroneous() {
        _state.update { it.copy(erroneousConfirmationEventId = null) }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun confirmMarkErroneous(
        eventId: String,
        expectedVersion: Long,
    ) {
        val companyId = _state.value.companyId
        _state.update {
            it.copy(
                erroneousConfirmationEventId = null,
                isMarkingErroneous = true,
            )
        }

        viewModelScope.launch {
            val idempotencyKey =
                kotlin.uuid.Uuid
                    .random()
                    .toString()
            val result =
                markEventErroneousUseCase(
                    companyId = companyId,
                    eventId = eventId,
                    expectedClassificationVersion = expectedVersion,
                    idempotencyKey = idempotencyKey,
                )

            when (result) {
                is EventsOperationResult.Success -> {
                    _state.update { it.copy(isMarkingErroneous = false) }
                }
                is EventsOperationResult.Failure -> {
                    _state.update {
                        it.copy(
                            isMarkingErroneous = false,
                            mutationErrorNotice = mapMutationErrorToResource(result.error),
                        )
                    }
                }
            }
        }
    }

    fun toggleExportMode() {
        _state.update { it.copy(isExportModeActive = !it.isExportModeActive) }
    }

    fun setExportFormatPreset(preset: EventsExportFormatPreset) {
        _state.update { it.copy(exportFormatPreset = preset) }
    }

    fun setExportIncludeMediaLinks(include: Boolean) {
        _state.update { it.copy(exportIncludeMediaLinks = include) }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun startExport() {
        if (_state.value.isExportSubmitting) return
        val companyId = _state.value.companyId
        if (companyId.isBlank()) return

        val request =
            EventsExportRequest(
                query = EventsQuery(filter = _state.value.filter),
                includeMediaLinks = _state.value.exportIncludeMediaLinks,
                formatPreset = _state.value.exportFormatPreset,
            )
        val idempotencyKey =
            kotlin.uuid.Uuid
                .random()
                .toString()

        viewModelScope.launch {
            _state.update { it.copy(isExportSubmitting = true, exportError = null) }
            try {
                val status = createEventsExportUseCase(companyId, request, idempotencyKey)
                _state.update {
                    it.copy(
                        exportJobStatus = status,
                        isExportSubmitting = false,
                    )
                }

                if (status.status == EventsExportStatusCode.QUEUED ||
                    status.status == EventsExportStatusCode.PROCESSING
                ) {
                    observeExport(status.exportId)
                }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        isExportSubmitting = false,
                        exportError = mapExportErrorToResource(e),
                    )
                }
            }
        }
    }

    private fun observeExport(exportId: String) {
        val companyId = _state.value.companyId
        exportObservationJob?.cancel()
        exportObservationJob =
            viewModelScope.launch {
                while (true) {
                    delay(1000L)
                    try {
                        val status = observeEventsExportStatusUseCase(companyId, exportId)
                        _state.update { it.copy(exportJobStatus = status) }
                        if (status.status == EventsExportStatusCode.COMPLETED ||
                            status.status == EventsExportStatusCode.FAILED ||
                            status.status == EventsExportStatusCode.EXPIRED
                        ) {
                            break
                        }
                    } catch (e: Throwable) {
                        // transient
                    }
                }
            }
    }

    fun downloadExport(exportId: String) {
        val companyId = _state.value.companyId
        viewModelScope.launch {
            try {
                val download = downloadEventsExportUseCase(companyId, exportId)
                val saved = exportFileSaver.saveExportFile(download)
                if (!saved) {
                    _state.update { it.copy(exportError = Res.string.events_export_download_error) }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(exportError = mapExportErrorToResource(e)) }
            }
        }
    }

    private fun mapMutationErrorToResource(error: EventsMutationError): StringResource =
        when (error) {
            is EventsMutationError.PermissionDenied -> Res.string.events_mutation_permission_denied
            is EventsMutationError.StateConflict -> Res.string.events_mutation_conflict
            is EventsMutationError.PayloadMismatch -> Res.string.events_mutation_payload_mismatch
            is EventsMutationError.Network -> Res.string.events_mutation_network_error
            is EventsMutationError.Server -> Res.string.events_mutation_generic_error
        }

    private fun mapExportErrorToResource(throwable: Throwable): StringResource =
        when (throwable) {
            is EventsApiException.PermissionDenied -> Res.string.events_export_error_access_revoked
            is EventsApiException.SnapshotTooLarge -> Res.string.events_export_error_limit_exceeded
            is EventsApiException.ExportExpired -> Res.string.events_export_expired_msg
            else -> Res.string.events_export_error_generic
        }

    fun copyEventId(eventId: String) {
        _state.update { it.copy(clipboardNotice = eventId) }
    }

    fun dismissClipboardNotice() {
        _state.update { it.copy(clipboardNotice = null) }
    }

    fun dismissMutationNotice() {
        _state.update { it.copy(mutationErrorNotice = null) }
    }

    fun openFilterDialog() {
        _state.update { it.copy(isFilterDialogOpen = true) }
    }

    fun closeFilterDialog() {
        _state.update { it.copy(isFilterDialogOpen = false) }
    }

    fun openDatePicker() {
        _state.update { it.copy(isDatePickerOpen = true) }
    }

    fun closeDatePicker() {
        _state.update { it.copy(isDatePickerOpen = false) }
    }

    override fun onCleared() {
        feedSession?.close()
        searchDebounceJob?.cancel()
        feedCollectJob?.cancel()
        exportObservationJob?.cancel()
        super.onCleared()
    }
}
