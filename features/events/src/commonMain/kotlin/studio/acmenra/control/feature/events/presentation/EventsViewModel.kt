package studio.acmenra.control.feature.events.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_export_download_error
import studio.acmenra.control.core.ui.generated.resources.events_export_download_initiated
import studio.acmenra.control.core.ui.generated.resources.events_export_error_access_revoked
import studio.acmenra.control.core.ui.generated.resources.events_export_error_generic
import studio.acmenra.control.core.ui.generated.resources.events_export_error_limit_exceeded
import studio.acmenra.control.core.ui.generated.resources.events_export_expired_msg
import studio.acmenra.control.core.ui.generated.resources.events_export_media_links_warning
import studio.acmenra.control.core.ui.generated.resources.events_export_polling_timeout
import studio.acmenra.control.core.ui.generated.resources.events_export_status_failed
import studio.acmenra.control.core.ui.generated.resources.events_mutation_conflict
import studio.acmenra.control.core.ui.generated.resources.events_mutation_generic_error
import studio.acmenra.control.core.ui.generated.resources.events_mutation_network_error
import studio.acmenra.control.core.ui.generated.resources.events_mutation_payload_mismatch
import studio.acmenra.control.core.ui.generated.resources.events_mutation_permission_denied
import studio.acmenra.control.core.ui.notice.Notice
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventValidity
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsDomainException
import studio.acmenra.control.feature.events.domain.model.EventsExportFormatPreset
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatusCode
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsMutationError
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.EventsQueryRouteCodec
import studio.acmenra.control.feature.events.domain.model.LocationFilterGroup
import studio.acmenra.control.feature.events.domain.model.LocationToggleState
import studio.acmenra.control.feature.events.domain.model.RealtimeOverlay
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
import kotlin.time.Clock

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

    internal var feedSession: EventsFeedSession? = null
    private var feedCollectJob: Job? = null
    private var feedNoticeJob: Job? = null
    private var feedFilterOptionsJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var exportObservationJob: Job? = null
    private var exportSubmissionJob: Job? = null
    private var downloadExportJob: Job? = null
    private val acknowledgeJobs = mutableMapOf<String, Job>()
    private var markErroneousJob: Job? = null
    private var filterOptionsJob: Job? = null
    private var activeExportKey: String? = null
    private var activeExportRequest: EventsExportRequest? = null
    private var lastExportStartTime: Long = 0L
    private var autoDownloadAttemptedExportId: String? = null
    private var companyGeneration: Long = 0L
    private var filterRequestGeneration: Long = 0L
    private var filterOptionsGeneration: Long = 0L
    private var downloadGeneration: Long = 0L

    var onQueryHashChanged: ((String?) -> Unit)? = null
    private var activeQueryHash: String? = null

    private fun syncFilterToUrl(filter: EventsFilter) {
        val companyId = _state.value.companyId
        if (companyId.isBlank()) return
        val encoded = EventsQueryRouteCodec.encodeHash(companyId, EventsQuery(filter = filter))
        if (encoded != activeQueryHash) {
            activeQueryHash = encoded
            onQueryHashChanged?.invoke(encoded)
        }
    }

    private fun applyIncomingQueryHash(
        companyId: String,
        queryHash: String?,
    ) {
        val baseFilter =
            if (!queryHash.isNullOrBlank()) {
                val (decodedCompanyId, decodedQuery) = EventsQueryRouteCodec.decodeHash(queryHash)
                if (!decodedCompanyId.isNullOrBlank() && decodedCompanyId != companyId) {
                    EventsFilter()
                } else {
                    decodedQuery.filter
                }
            } else {
                EventsFilter()
            }
        val decodedFilter = _state.value.filterOptions?.sanitize(baseFilter) ?: baseFilter
        if (decodedFilter != _state.value.filter) {
            _state.update { it.copy(filter = decodedFilter) }
            restartFeedSession()
            if (decodedFilter != baseFilter) {
                syncFilterToUrl(decodedFilter)
            }
        }
    }

    fun initialize(
        companyId: String,
        accessCapabilities: EventsAccessCapabilities,
        queryHash: String? = null,
    ) {
        val currentCompany = _state.value.companyId
        if (currentCompany == companyId && feedSession != null) {
            if (queryHash != activeQueryHash) {
                activeQueryHash = queryHash
                applyIncomingQueryHash(companyId, queryHash)
            }
            if (_state.value.access != accessCapabilities) {
                val wasRead = _state.value.access.eventsRead
                // Fail-closed updates when capabilities change
                val shouldClearFeed = !accessCapabilities.eventsRead
                val shouldClearReview = !accessCapabilities.canAcknowledge
                val shouldClearExport = !accessCapabilities.canExport

                if (shouldClearFeed) {
                    ++filterOptionsGeneration
                    filterOptionsJob?.cancel()
                }
                if (shouldClearReview) {
                    acknowledgeJobs.values.forEach { it.cancel() }
                    acknowledgeJobs.clear()
                    markErroneousJob?.cancel()
                }
                if (shouldClearExport) {
                    exportObservationJob?.cancel()
                    exportSubmissionJob?.cancel()
                    downloadExportJob?.cancel()
                    activeExportKey = null
                    activeExportRequest = null
                    autoDownloadAttemptedExportId = null
                }

                _state.update {
                    if (shouldClearFeed) {
                        it.copy(
                            access = accessCapabilities,
                            visibleEvents = emptyList(),
                            realtimeOverlay = RealtimeOverlay(),
                            asOfSequence = 0L,
                            snapshotTotalCount = null,
                            hasMore = false,
                            isLoadingNextPage = false,
                            paginationError = null,
                            summary = null,
                            pendingNewerCount = 0,
                            pendingHistoryCount = 0,
                            selectedEventId = null,
                            isViewerOpenInCompact = false,
                            filterOptions = null,
                            isFilterOptionsLoading = false,
                            isInitialLoading = false,
                            initialLoadError = null,
                            confirmationTarget = if (shouldClearReview) null else it.confirmationTarget,
                            isMarkingErroneous = if (shouldClearReview) false else it.isMarkingErroneous,
                            isExportModeActive = if (shouldClearExport) false else it.isExportModeActive,
                            exportJobStatus = if (shouldClearExport) null else it.exportJobStatus,
                            isExportSubmitting = if (shouldClearExport) false else it.isExportSubmitting,
                            isExportDownloading = if (shouldClearExport) false else it.isExportDownloading,
                        )
                    } else {
                        it.copy(
                            access = accessCapabilities,
                            realtimeOverlay =
                                if (shouldClearReview) {
                                    it.realtimeOverlay.copy(pendingAckIds = emptySet())
                                } else {
                                    it.realtimeOverlay
                                },
                            confirmationTarget = if (shouldClearReview) null else it.confirmationTarget,
                            isMarkingErroneous = if (shouldClearReview) false else it.isMarkingErroneous,
                            isExportModeActive = if (shouldClearExport) false else it.isExportModeActive,
                            exportJobStatus = if (shouldClearExport) null else it.exportJobStatus,
                            isExportSubmitting = if (shouldClearExport) false else it.isExportSubmitting,
                            isExportDownloading = if (shouldClearExport) false else it.isExportDownloading,
                        )
                    }
                }
                feedSession?.updateAccessCapabilities(accessCapabilities)
                if (!wasRead && accessCapabilities.eventsRead) {
                    loadFilterOptions(companyId)
                }
            }
            return
        }

        // Company changed: cancel all active jobs and increment generation
        val currentGen = ++companyGeneration
        feedCollectJob?.cancel()
        feedNoticeJob?.cancel()
        feedFilterOptionsJob?.cancel()
        searchDebounceJob?.cancel()
        exportObservationJob?.cancel()
        exportSubmissionJob?.cancel()
        downloadExportJob?.cancel()
        acknowledgeJobs.values.forEach { it.cancel() }
        acknowledgeJobs.clear()
        markErroneousJob?.cancel()
        filterOptionsJob?.cancel()
        activeExportKey = null
        activeExportRequest = null
        autoDownloadAttemptedExportId = null
        feedSession?.close()
        feedSession = null

        activeQueryHash = queryHash
        val decodedFilter =
            if (!queryHash.isNullOrBlank()) {
                val (decodedCompanyId, decodedQuery) = EventsQueryRouteCodec.decodeHash(queryHash)
                if (!decodedCompanyId.isNullOrBlank() && decodedCompanyId != companyId) {
                    EventsFilter()
                } else {
                    decodedQuery.filter
                }
            } else {
                EventsFilter()
            }

        _state.update {
            EventsState(
                companyId = companyId,
                access = accessCapabilities,
                isInitialLoading = accessCapabilities.eventsRead && companyId.isNotBlank(),
                initialLoadError = null,
                visibleEvents = emptyList(),
                selectedEventId = null,
                filter = decodedFilter,
                filterOptions = null,
                isExportModeActive = false,
                exportJobStatus = null,
                isExportSubmitting = false,
                confirmationTarget = null,
            )
        }

        if (accessCapabilities.eventsRead) {
            loadFilterOptions(companyId)
        }
        restartFeedSession()
    }

    private fun loadFilterOptions(companyId: String) {
        if (!_state.value.access.eventsRead) {
            _state.update { it.copy(isFilterOptionsLoading = false) }
            return
        }
        filterOptionsJob?.cancel()
        val sessionCompanyGen = companyGeneration
        val gen = ++filterOptionsGeneration

        filterOptionsJob =
            viewModelScope.launch {
                _state.update { it.copy(isFilterOptionsLoading = true) }
                try {
                    val options = loadEventFilterOptionsUseCase(companyId)
                    if (sessionCompanyGen == companyGeneration &&
                        gen == filterOptionsGeneration &&
                        _state.value.companyId == companyId
                    ) {
                        val sanitizedFilter = options.sanitize(_state.value.filter)
                        val filterChanged = sanitizedFilter != _state.value.filter
                        _state.update {
                            it.copy(
                                filter = sanitizedFilter,
                                filterOptions = options,
                                isFilterOptionsLoading = false,
                            )
                        }
                        if (filterChanged) {
                            restartFeedSession()
                            syncFilterToUrl(sanitizedFilter)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (sessionCompanyGen == companyGeneration && gen == filterOptionsGeneration) {
                        _state.update { it.copy(isFilterOptionsLoading = false) }
                    }
                }
            }
    }

    private fun restartFeedSession() {
        val companyId = _state.value.companyId
        if (companyId.isBlank()) return

        val sessionCompanyGen = companyGeneration
        val currentGen = ++filterRequestGeneration
        feedCollectJob?.cancel()
        feedNoticeJob?.cancel()
        feedFilterOptionsJob?.cancel()
        feedSession?.close()

        val query = EventsQuery(filter = _state.value.filter)
        val session = observeEventsFeedUseCase(companyId, query, _state.value.access)
        feedSession = session

        feedCollectJob =
            viewModelScope.launch {
                session.state.collect { feedState ->
                    if (currentGen != filterRequestGeneration || sessionCompanyGen != companyGeneration) return@collect
                    val canRead = feedState.access.eventsRead && _state.value.access.eventsRead
                    val tz =
                        feedState.summary?.businessTimezone?.let {
                            DateFilterUtils.resolveBusinessTimeZone(it)
                        }
                    _state.update { current ->
                        current.copy(
                            visibleEvents = if (canRead) feedState.visibleEvents else emptyList(),
                            realtimeOverlay = if (canRead) feedState.realtimeOverlay else RealtimeOverlay(),
                            asOfSequence = if (canRead) feedState.asOfSequence else 0L,
                            snapshotTotalCount = if (canRead) feedState.snapshotTotalCount else null,
                            hasMore = if (canRead) feedState.hasMore else false,
                            isInitialLoading = if (canRead) feedState.isInitialLoading else false,
                            initialLoadError = if (canRead) feedState.initialLoadError else null,
                            isLoadingNextPage = if (canRead) feedState.isLoadingNextPage else false,
                            paginationError = if (canRead) feedState.paginationError else null,
                            connectionState = feedState.connectionState,
                            pendingNewerCount = if (canRead) feedState.pendingNewerCount else 0,
                            pendingHistoryCount = if (canRead) feedState.pendingHistoryCount else 0,
                            summary = if (canRead) feedState.summary else null,
                            access = current.access,
                        )
                    }
                }
            }

        feedNoticeJob =
            viewModelScope.launch {
                session.connectionNotice.collect { notice ->
                    if (sessionCompanyGen != companyGeneration) return@collect
                    _state.update { it.copy(errorNotice = notice) }
                }
            }

        feedFilterOptionsJob =
            viewModelScope.launch {
                session.filterOptionsInvalidated.collect {
                    if (sessionCompanyGen != companyGeneration) return@collect
                    loadFilterOptions(companyId)
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
                syncFilterToUrl(_state.value.filter)
            }
    }

    fun onSearchIdCommitted(newSearchId: String) {
        searchDebounceJob?.cancel()
        val trimmed = newSearchId.trim().ifBlank { null }
        if (_state.value.filter.searchId == trimmed) return
        val newFilter = _state.value.filter.copy(searchId = trimmed)
        _state.update { it.copy(filter = newFilter) }
        restartFeedSession()
        syncFilterToUrl(newFilter)
    }

    fun onResetDate() {
        val newFilter =
            _state.value.filter.copy(
                dateFrom = null,
                dateTo = null,
            )
        _state.update { it.copy(filter = newFilter, isDatePickerOpen = false) }
        restartFeedSession()
        syncFilterToUrl(newFilter)
    }

    fun onEventTypesApplied(typeIds: Set<String>) {
        val sanitized =
            _state.value.filterOptions
                ?.sanitize(
                    _state.value.filter.copy(eventTypeIds = typeIds),
                )?.eventTypeIds ?: typeIds
        if (_state.value.filter.eventTypeIds == sanitized) return
        val newFilter = _state.value.filter.copy(eventTypeIds = sanitized)
        _state.update { it.copy(filter = newFilter) }
        restartFeedSession()
        syncFilterToUrl(newFilter)
    }

    fun onDevicesApplied(deviceIds: Set<String>) {
        val sanitized =
            _state.value.filterOptions
                ?.sanitize(
                    _state.value.filter.copy(deviceIds = deviceIds),
                )?.deviceIds ?: deviceIds
        if (_state.value.filter.deviceIds == sanitized) return
        val newFilter = _state.value.filter.copy(deviceIds = sanitized)
        _state.update { it.copy(filter = newFilter) }
        restartFeedSession()
        syncFilterToUrl(newFilter)
    }

    fun onDateSelectionChanged(
        dateSelection: DateSelection,
        businessTimeZone: TimeZone? = null,
    ) {
        if (dateSelection is DateSelection.All) {
            val newFilter =
                _state.value.filter.copy(
                    dateFrom = null,
                    dateTo = null,
                )
            _state.update { it.copy(filter = newFilter, isDatePickerOpen = false) }
            restartFeedSession()
            syncFilterToUrl(newFilter)
            return
        }
        val tzId =
            businessTimeZone?.id
                ?: _state.value.summary?.businessTimezone
        val tz = tzId?.let { DateFilterUtils.resolveBusinessTimeZone(it) }
        if (tz == null) {
            // Strictly require business_timezone to avoid silently falling back to system timezone
            return
        }
        val (from, to) = DateFilterUtils.dateSelectionToUtcBounds(dateSelection, tz)
        val newFilter =
            _state.value.filter.copy(
                dateFrom = from,
                dateTo = to,
            )
        _state.update { it.copy(filter = newFilter, isDatePickerOpen = false) }
        restartFeedSession()
        syncFilterToUrl(newFilter)
    }

    fun onEventTypeToggled(typeId: String) {
        val current = _state.value.filter.eventTypeIds
        val updated = if (typeId in current) current - typeId else current + typeId
        val newFilter = _state.value.filter.copy(eventTypeIds = updated)
        _state.update { it.copy(filter = newFilter) }
        restartFeedSession()
        syncFilterToUrl(newFilter)
    }

    fun onDeviceToggled(deviceId: String) {
        val current = _state.value.filter.deviceIds
        val updated = if (deviceId in current) current - deviceId else current + deviceId
        val newFilter = _state.value.filter.copy(deviceIds = updated)
        _state.update { it.copy(filter = newFilter) }
        restartFeedSession()
        syncFilterToUrl(newFilter)
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
        val newFilter = _state.value.filter.copy(deviceIds = updated)
        _state.update { it.copy(filter = newFilter) }
        restartFeedSession()
        syncFilterToUrl(newFilter)
    }

    fun onValidityChanged(validity: EventValidity) {
        if (_state.value.filter.validity == validity) return
        val newFilter = _state.value.filter.copy(validity = validity)
        _state.update { it.copy(filter = newFilter) }
        restartFeedSession()
        syncFilterToUrl(newFilter)
    }

    fun resetFilters() {
        searchDebounceJob?.cancel()
        val newFilter = EventsFilter()
        _state.update { it.copy(filter = newFilter) }
        restartFeedSession()
        syncFilterToUrl(newFilter)
    }

    fun loadNextPage() {
        feedSession?.loadNextPage()
    }

    fun refresh() {
        feedSession?.refresh()
    }

    fun retryRebootstrap() {
        feedSession?.retryRebootstrap()
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
        if (_state.value.pendingAckIds.contains(event.id) || acknowledgeJobs[event.id]?.isActive == true) return
        val currentCompanyGen = companyGeneration
        val requestCompanyId = _state.value.companyId
        val requestSession = feedSession ?: return

        requestSession.markOptimisticAckPending(event.id)
        val job =
            viewModelScope.launch {
                try {
                    val result = acknowledgeEventUseCase(requestCompanyId, event.id)
                    if (currentCompanyGen != companyGeneration || _state.value.companyId != requestCompanyId) {
                        return@launch
                    }
                    when (result) {
                        is EventsOperationResult.Success -> {
                            requestSession.applyAcknowledged(result.data)
                            val activeSession = feedSession
                            if (activeSession != null && activeSession !== requestSession) {
                                activeSession.applyAcknowledged(result.data)
                            }
                        }
                        is EventsOperationResult.Failure -> {
                            requestSession.clearOptimisticAckPending(event.id)
                            feedSession?.clearOptimisticAckPending(event.id)
                            _state.update { it.copy(mutationErrorNotice = mapMutationErrorToResource(result.error)) }
                        }
                    }
                } catch (e: CancellationException) {
                    if (currentCompanyGen == companyGeneration) {
                        requestSession.clearOptimisticAckPending(event.id)
                        feedSession?.clearOptimisticAckPending(event.id)
                    }
                    throw e
                } finally {
                    if (acknowledgeJobs[event.id] === coroutineContext.job) {
                        acknowledgeJobs.remove(event.id)
                    }
                }
            }
        acknowledgeJobs[event.id] = job
    }

    fun requestMarkErroneous(event: Event) {
        if (!_state.value.access.canAcknowledge) return
        _state.update {
            it.copy(
                confirmationTarget =
                    ErroneousConfirmationTarget(
                        eventId = event.id,
                        expectedClassificationVersion = event.classificationVersion,
                        eventTypeName = event.eventTypeName,
                    ),
            )
        }
    }

    fun requestMarkErroneous(eventId: String) {
        val event = _state.value.visibleEvents.firstOrNull { it.id == eventId }
        if (event != null) {
            requestMarkErroneous(event)
        } else {
            // Do not fabricate expectedClassificationVersion; fail-closed
            _state.update {
                it.copy(mutationErrorNotice = Res.string.events_mutation_conflict)
            }
        }
    }

    fun cancelMarkErroneous() {
        _state.update { it.copy(confirmationTarget = null) }
    }

    private data class ErroneousOperationIdentity(
        val companyId: String,
        val eventId: String,
        val expectedClassificationVersion: Long,
    )

    private var activeMarkErroneousIdentity: ErroneousOperationIdentity? = null
    private var activeMarkErroneousKey: String? = null

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun confirmMarkErroneous(
        eventId: String? = null,
        expectedVersion: Long? = null,
    ) {
        if (!_state.value.access.canAcknowledge || _state.value.isMarkingErroneous) return
        val target = _state.value.confirmationTarget
        val targetEventId = eventId ?: target?.eventId ?: return
        val targetVersion = expectedVersion ?: target?.expectedClassificationVersion ?: return
        val currentCompanyGen = companyGeneration
        val requestCompanyId = _state.value.companyId
        val requestSession = feedSession ?: return

        _state.update {
            it.copy(
                confirmationTarget = null,
                isMarkingErroneous = true,
            )
        }

        markErroneousJob?.cancel()
        markErroneousJob =
            viewModelScope.launch {
                try {
                    val opIdentity =
                        ErroneousOperationIdentity(
                            companyId = requestCompanyId,
                            eventId = targetEventId,
                            expectedClassificationVersion = targetVersion,
                        )
                    val idempotencyKey =
                        if (activeMarkErroneousIdentity == opIdentity && activeMarkErroneousKey != null) {
                            activeMarkErroneousKey!!
                        } else {
                            val newKey =
                                kotlin.uuid.Uuid
                                    .random()
                                    .toString()
                            activeMarkErroneousIdentity = opIdentity
                            activeMarkErroneousKey = newKey
                            newKey
                        }
                    val result =
                        markEventErroneousUseCase(
                            companyId = requestCompanyId,
                            eventId = targetEventId,
                            expectedClassificationVersion = targetVersion,
                            idempotencyKey = idempotencyKey,
                        )

                    if (currentCompanyGen != companyGeneration || _state.value.companyId != requestCompanyId) {
                        return@launch
                    }
                    when (result) {
                        is EventsOperationResult.Success -> {
                            activeMarkErroneousIdentity = null
                            activeMarkErroneousKey = null
                            requestSession.applyEventMutationResult(result.data)
                            val activeSession = feedSession
                            if (activeSession != null && activeSession !== requestSession) {
                                activeSession.applyEventMutationResult(result.data)
                            }
                        }
                        is EventsOperationResult.Failure -> {
                            val isDefiniteFailure =
                                when (result.error) {
                                    is EventsMutationError.PermissionDenied -> true
                                    is EventsMutationError.StateConflict -> true
                                    is EventsMutationError.PayloadMismatch -> true
                                    is EventsMutationError.Network -> false
                                    is EventsMutationError.Server -> false
                                }
                            if (isDefiniteFailure) {
                                activeMarkErroneousIdentity = null
                                activeMarkErroneousKey = null
                            }
                            _state.update {
                                it.copy(
                                    mutationErrorNotice = mapMutationErrorToResource(result.error),
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } finally {
                    if (currentCompanyGen == companyGeneration) {
                        _state.update { it.copy(isMarkingErroneous = false) }
                    }
                }
            }
    }

    fun toggleExportMode() {
        if (!_state.value.access.canExport) return
        _state.update { it.copy(isExportModeActive = !it.isExportModeActive) }
    }

    fun setExportFormatPreset(preset: EventsExportFormatPreset) {
        _state.update { it.copy(exportFormatPreset = preset) }
    }

    fun setExportIncludeMediaLinks(include: Boolean) {
        val wasIncluded = _state.value.exportIncludeMediaLinks
        _state.update { it.copy(exportIncludeMediaLinks = include) }
        if (include && !wasIncluded) {
            _state.update {
                it.copy(
                    errorNotice =
                        Notice.info(
                            title = Res.string.events_export_media_links_warning,
                            key = "media_links_warning",
                        ),
                )
            }
        }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun startExport() {
        if (!_state.value.access.canExport) {
            _state.update {
                it.copy(
                    errorNotice =
                        Notice.error(
                            title = Res.string.events_export_error_access_revoked,
                            key = "export_access_denied",
                        ),
                )
            }
            return
        }
        val currentJob = _state.value.exportJobStatus?.status
        if (_state.value.isExportSubmitting ||
            _state.value.isExportDownloading ||
            currentJob == EventsExportStatusCode.QUEUED ||
            currentJob == EventsExportStatusCode.PROCESSING
        ) {
            return
        }
        val requestCompanyId = _state.value.companyId
        if (requestCompanyId.isBlank()) return
        val currentCompanyGen = companyGeneration

        searchDebounceJob?.cancel()
        autoDownloadAttemptedExportId = null
        _state.update { it.copy(isExportSubmitting = true, exportError = null) }

        val request =
            EventsExportRequest(
                query = EventsQuery(filter = _state.value.filter),
                includeMediaLinks = _state.value.exportIncludeMediaLinks,
                formatPreset = _state.value.exportFormatPreset,
            )
        val idempotencyKey =
            if (activeExportRequest == request && activeExportKey != null) {
                activeExportKey!!
            } else {
                val newKey =
                    kotlin.uuid.Uuid
                        .random()
                        .toString()
                activeExportKey = newKey
                activeExportRequest = request
                newKey
            }

        exportSubmissionJob?.cancel()
        exportSubmissionJob =
            viewModelScope.launch {
                try {
                    val status = createEventsExportUseCase(requestCompanyId, request, idempotencyKey)
                    if (currentCompanyGen != companyGeneration || _state.value.companyId != requestCompanyId) {
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            exportJobStatus = status,
                            isExportSubmitting = false,
                        )
                    }

                    if (status.status == EventsExportStatusCode.QUEUED ||
                        status.status == EventsExportStatusCode.PROCESSING
                    ) {
                        observeExport(status.exportId, requestCompanyId, currentCompanyGen)
                    } else if (status.status == EventsExportStatusCode.COMPLETED) {
                        activeExportKey = null
                        activeExportRequest = null
                        if (autoDownloadAttemptedExportId != status.exportId && _state.value.access.canExport) {
                            autoDownloadAttemptedExportId = status.exportId
                            triggerAutoDownload(status.exportId, requestCompanyId, currentCompanyGen)
                        }
                    } else if (status.status == EventsExportStatusCode.FAILED ||
                        status.status == EventsExportStatusCode.EXPIRED
                    ) {
                        activeExportKey = null
                        activeExportRequest = null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val isDefiniteFailure =
                        when (e) {
                            is EventsDomainException.PermissionDenied -> true
                            is EventsDomainException.LimitExceeded -> true
                            is EventsDomainException.ExportExpired -> true
                            is EventsDomainException.FilterInvalid -> true
                            is EventsDomainException.PayloadMismatch -> true
                            is EventsDomainException.Generic -> !e.retryable
                            else -> false
                        }
                    if (isDefiniteFailure) {
                        activeExportKey = null
                        activeExportRequest = null
                    }
                    if (currentCompanyGen != companyGeneration || _state.value.companyId != requestCompanyId) {
                        return@launch
                    }
                    val errRes = mapExportErrorToResource(e)
                    _state.update {
                        it.copy(
                            isExportSubmitting = false,
                            exportError = errRes,
                            errorNotice =
                                Notice.error(
                                    title = errRes,
                                    key = "export_submit_error",
                                ),
                        )
                    }
                }
            }
    }

    private fun observeExport(
        exportId: String,
        requestCompanyId: String,
        currentCompanyGen: Long,
    ) {
        exportObservationJob?.cancel()
        exportObservationJob =
            viewModelScope.launch {
                var attempt = 0
                val maxAttempts = 60
                while (isActive && attempt < maxAttempts) {
                    if (currentCompanyGen != companyGeneration || _state.value.companyId != requestCompanyId) {
                        return@launch
                    }
                    if (!_state.value.access.canExport) {
                        activeExportKey = null
                        activeExportRequest = null
                        _state.update {
                            it.copy(
                                isExportModeActive = false,
                                exportJobStatus = null,
                            )
                        }
                        return@launch
                    }
                    attempt++
                    val delayMs = (1500L + (attempt * 250L)).coerceAtMost(8000L)
                    delay(delayMs)
                    try {
                        val status = observeEventsExportStatusUseCase(requestCompanyId, exportId)
                        if (currentCompanyGen != companyGeneration || _state.value.companyId != requestCompanyId) {
                            return@launch
                        }
                        _state.update { it.copy(exportJobStatus = status) }
                        if (status.status == EventsExportStatusCode.COMPLETED) {
                            activeExportKey = null
                            activeExportRequest = null
                            if (autoDownloadAttemptedExportId != status.exportId && _state.value.access.canExport) {
                                autoDownloadAttemptedExportId = status.exportId
                                triggerAutoDownload(status.exportId, requestCompanyId, currentCompanyGen)
                            }
                            return@launch
                        } else if (status.status == EventsExportStatusCode.FAILED) {
                            activeExportKey = null
                            activeExportRequest = null
                            _state.update {
                                it.copy(
                                    exportError = Res.string.events_export_status_failed,
                                    errorNotice =
                                        Notice.error(
                                            title = Res.string.events_export_status_failed,
                                            key = "export_failed_${status.exportId}",
                                        ),
                                )
                            }
                            return@launch
                        } else if (status.status == EventsExportStatusCode.EXPIRED) {
                            activeExportKey = null
                            activeExportRequest = null
                            _state.update {
                                it.copy(
                                    exportError = Res.string.events_export_expired_msg,
                                    errorNotice =
                                        Notice.error(
                                            title = Res.string.events_export_expired_msg,
                                            key = "export_expired_${status.exportId}",
                                        ),
                                )
                            }
                            return@launch
                        } else if (status.status == EventsExportStatusCode.UNKNOWN) {
                            return@launch
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: EventsDomainException.ExportExpired) {
                        activeExportKey = null
                        activeExportRequest = null
                        if (currentCompanyGen == companyGeneration && _state.value.companyId == requestCompanyId) {
                            _state.update {
                                it.copy(
                                    exportJobStatus =
                                        it.exportJobStatus?.copy(
                                            status = EventsExportStatusCode.EXPIRED,
                                        ),
                                    exportError = Res.string.events_export_expired_msg,
                                    errorNotice =
                                        Notice.error(
                                            title = Res.string.events_export_expired_msg,
                                            key = "export_expired_$exportId",
                                        ),
                                )
                            }
                        }
                        return@launch
                    } catch (e: EventsDomainException.PermissionDenied) {
                        activeExportKey = null
                        activeExportRequest = null
                        if (currentCompanyGen == companyGeneration && _state.value.companyId == requestCompanyId) {
                            _state.update {
                                it.copy(
                                    isExportModeActive = false,
                                    exportJobStatus = null,
                                    exportError = Res.string.events_export_error_access_revoked,
                                    errorNotice =
                                        Notice.error(
                                            title = Res.string.events_export_error_access_revoked,
                                            key = "export_revoked_$exportId",
                                        ),
                                )
                            }
                        }
                        return@launch
                    } catch (_: Throwable) {
                        // Transient network or server error during polling
                    }
                }
                if (isActive && attempt >= maxAttempts) {
                    if (currentCompanyGen == companyGeneration && _state.value.companyId == requestCompanyId) {
                        _state.update {
                            it.copy(
                                exportError = Res.string.events_export_polling_timeout,
                                errorNotice =
                                    Notice.error(
                                        title = Res.string.events_export_polling_timeout,
                                        key = "export_timeout_$exportId",
                                    ),
                            )
                        }
                    }
                }
            }
    }

    private fun triggerAutoDownload(
        exportId: String,
        requestCompanyId: String,
        currentCompanyGen: Long,
    ) {
        if (!_state.value.access.canExport) return
        val currentDownloadGen = ++downloadGeneration
        downloadExportJob?.cancel()
        downloadExportJob =
            viewModelScope.launch {
                _state.update { it.copy(isExportDownloading = true) }
                try {
                    val download = downloadEventsExportUseCase(requestCompanyId, exportId)
                    if (currentCompanyGen != companyGeneration ||
                        currentDownloadGen != downloadGeneration ||
                        _state.value.companyId != requestCompanyId ||
                        !_state.value.access.canExport
                    ) {
                        return@launch
                    }
                    val saved = exportFileSaver.saveExportFile(download)
                    if (currentCompanyGen != companyGeneration ||
                        currentDownloadGen != downloadGeneration ||
                        _state.value.companyId != requestCompanyId ||
                        !_state.value.access.canExport
                    ) {
                        return@launch
                    }
                    if (saved) {
                        _state.update {
                            it.copy(
                                errorNotice =
                                    Notice.success(
                                        title = Res.string.events_export_download_initiated,
                                        key = "export_download_$exportId",
                                    ),
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                exportError = Res.string.events_export_download_error,
                                errorNotice =
                                    Notice.error(
                                        title = Res.string.events_export_download_error,
                                        key = "export_download_error_$exportId",
                                    ),
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (currentCompanyGen == companyGeneration &&
                        currentDownloadGen == downloadGeneration &&
                        _state.value.companyId == requestCompanyId &&
                        _state.value.access.canExport
                    ) {
                        val errRes = mapExportErrorToResource(e)
                        _state.update {
                            it.copy(
                                exportError = errRes,
                                errorNotice =
                                    Notice.error(
                                        title = errRes,
                                        key = "export_error_$exportId",
                                    ),
                            )
                        }
                    }
                } finally {
                    if (currentCompanyGen == companyGeneration && currentDownloadGen == downloadGeneration) {
                        _state.update { it.copy(isExportDownloading = false) }
                    }
                }
            }
    }

    fun downloadExport(exportId: String) {
        if (!_state.value.access.canExport) return
        if (_state.value.isExportDownloading) return
        val jobStatus = _state.value.exportJobStatus
        val expiresAt = jobStatus?.expiresAt
        val now = Clock.System.now()
        if (jobStatus?.status == EventsExportStatusCode.EXPIRED ||
            (expiresAt != null && now >= expiresAt)
        ) {
            _state.update {
                it.copy(
                    exportJobStatus =
                        jobStatus?.copy(
                            status = EventsExportStatusCode.EXPIRED,
                        ),
                    exportError = Res.string.events_export_expired_msg,
                    errorNotice =
                        Notice.error(
                            title = Res.string.events_export_expired_msg,
                            key = "export_expired_$exportId",
                        ),
                )
            }
            return
        }
        if (jobStatus != null && !jobStatus.isDownloadable) return

        val requestCompanyId = _state.value.companyId
        val currentCompanyGen = companyGeneration
        val currentDownloadGen = ++downloadGeneration
        downloadExportJob?.cancel()
        downloadExportJob =
            viewModelScope.launch {
                _state.update { it.copy(isExportDownloading = true) }
                try {
                    val download = downloadEventsExportUseCase(requestCompanyId, exportId)
                    if (currentCompanyGen != companyGeneration ||
                        currentDownloadGen != downloadGeneration ||
                        _state.value.companyId != requestCompanyId ||
                        !_state.value.access.canExport
                    ) {
                        return@launch
                    }
                    val saved = exportFileSaver.saveExportFile(download)
                    if (currentCompanyGen != companyGeneration ||
                        currentDownloadGen != downloadGeneration ||
                        _state.value.companyId != requestCompanyId ||
                        !_state.value.access.canExport
                    ) {
                        return@launch
                    }
                    if (saved) {
                        _state.update {
                            it.copy(
                                errorNotice =
                                    Notice.success(
                                        title = Res.string.events_export_download_initiated,
                                        key = "export_download_$exportId",
                                    ),
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                exportError = Res.string.events_export_download_error,
                                errorNotice =
                                    Notice.error(
                                        title = Res.string.events_export_download_error,
                                        key = "export_download_error_$exportId",
                                    ),
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (currentCompanyGen == companyGeneration &&
                        currentDownloadGen == downloadGeneration &&
                        _state.value.companyId == requestCompanyId &&
                        _state.value.access.canExport
                    ) {
                        val errRes = mapExportErrorToResource(e)
                        _state.update {
                            it.copy(
                                exportError = errRes,
                                errorNotice =
                                    Notice.error(
                                        title = errRes,
                                        key = "export_error_$exportId",
                                    ),
                            )
                        }
                    }
                } finally {
                    if (currentCompanyGen == companyGeneration && currentDownloadGen == downloadGeneration) {
                        _state.update { it.copy(isExportDownloading = false) }
                    }
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
            is EventsDomainException.PermissionDenied -> Res.string.events_export_error_access_revoked
            is EventsDomainException.LimitExceeded -> Res.string.events_export_error_limit_exceeded
            is EventsDomainException.ExportExpired -> Res.string.events_export_expired_msg
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

    fun dismissErrorNotice() {
        _state.update { it.copy(errorNotice = null) }
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

    protected override fun onCleared() {
        feedSession?.close()
        searchDebounceJob?.cancel()
        feedCollectJob?.cancel()
        feedNoticeJob?.cancel()
        feedFilterOptionsJob?.cancel()
        exportObservationJob?.cancel()
        exportSubmissionJob?.cancel()
        downloadExportJob?.cancel()
        acknowledgeJobs.values.forEach { it.cancel() }
        acknowledgeJobs.clear()
        markErroneousJob?.cancel()
        filterOptionsJob?.cancel()
        activeExportKey = null
        activeExportRequest = null
        autoDownloadAttemptedExportId = null
        activeMarkErroneousIdentity = null
        activeMarkErroneousKey = null
        super.onCleared()
    }

    internal fun clearForTest() {
        onCleared()
    }
}
