package studio.acmenra.control.feature.events.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.core.network.auth.AccessInvalidationListener
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.client.isConnectivityError
import studio.acmenra.control.feature.events.data.datasource.EventsApiException
import studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.FilterOptionsFetchResult
import studio.acmenra.control.feature.events.data.mapper.EventsDtoMapper
import studio.acmenra.control.feature.events.data.realtime.EventsFeedSessionImpl
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsBootstrap
import studio.acmenra.control.feature.events.domain.model.EventsDomainException
import studio.acmenra.control.feature.events.domain.model.EventsExportDownload
import studio.acmenra.control.feature.events.domain.model.EventsExportRequest
import studio.acmenra.control.feature.events.domain.model.EventsExportStatus
import studio.acmenra.control.feature.events.domain.model.EventsFilterOptions
import studio.acmenra.control.feature.events.domain.model.EventsMutationError
import studio.acmenra.control.feature.events.domain.model.EventsOperationResult
import studio.acmenra.control.feature.events.domain.model.EventsPage
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.repository.EventsConnectivityProvider
import studio.acmenra.control.feature.events.domain.repository.EventsFeedSession
import studio.acmenra.control.feature.events.domain.repository.EventsRepository

class EventsRepositoryImpl(
    private val remoteDataSource: EventsRemoteDataSource,
    private val webSocketDataSourceFactory: () -> EventsWebSocketDataSource,
    private val sessionManager: SessionManager,
    private val connectivityProvider: EventsConnectivityProvider,
    private val accessInvalidationListener: AccessInvalidationListener? = null,
) : EventsRepository {
    constructor(
        remoteDataSource: EventsRemoteDataSource,
        webSocketDataSource: EventsWebSocketDataSource,
        sessionManager: SessionManager,
        connectivityProvider: EventsConnectivityProvider,
        accessInvalidationListener: AccessInvalidationListener? = null,
    ) : this(
        remoteDataSource = remoteDataSource,
        webSocketDataSourceFactory = { webSocketDataSource },
        sessionManager = sessionManager,
        connectivityProvider = connectivityProvider,
        accessInvalidationListener = accessInvalidationListener,
    )

    private val filterOptionsMutex = Mutex()
    private val companyFilterStates = MutableStateFlow<Map<String, CompanyFilterCacheState>>(emptyMap())
    private val filterRequestGenerations = MutableStateFlow<Map<String, Long>>(emptyMap())

    private fun nextFilterRequestGeneration(companyId: String): Long {
        var nextGen = 1L
        filterRequestGenerations.update { map ->
            val cur = map[companyId] ?: 0L
            nextGen = cur + 1L
            map + (companyId to nextGen)
        }
        return nextGen
    }

    private data class CachedFilterOptions(
        val options: EventsFilterOptions,
        val etag: String?,
    )

    private data class CompanyFilterCacheState(
        val cacheGeneration: Long = 0L,
        val latestAppliedRequestGen: Long = 0L,
        val cached: CachedFilterOptions? = null,
    )

    internal fun isFilterCacheEmptyForTest(companyId: String): Boolean =
        companyFilterStates.value[companyId]?.cached == null

    override fun invalidateFilterCache(companyId: String) {
        companyFilterStates.update { map ->
            val cur = map[companyId] ?: CompanyFilterCacheState()
            map + (companyId to cur.copy(cacheGeneration = cur.cacheGeneration + 1L, cached = null))
        }
    }

    override fun openFeedSession(
        companyId: String,
        query: EventsQuery,
        accessCapabilities: EventsAccessCapabilities,
        parentScope: kotlinx.coroutines.CoroutineScope?,
    ): EventsFeedSession {
        val dedicatedWs = webSocketDataSourceFactory()
        return EventsFeedSessionImpl(
            companyId = companyId,
            query = query,
            initialAccess = accessCapabilities,
            remoteDataSource = remoteDataSource,
            webSocketDataSource = dedicatedWs,
            sessionManager = sessionManager,
            connectivityProvider = connectivityProvider,
            accessInvalidationListener = accessInvalidationListener,
            onFilterOptionsInvalidated = { invalidateFilterCache(companyId) },
            parentScope = parentScope,
        )
    }

    private inline fun <T> wrapDomainErrors(block: () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: EventsApiException) {
            throw when (e) {
                is EventsApiException.PermissionDenied ->
                    EventsDomainException.PermissionDenied(e.reasonCode, e.message ?: "Permission denied")
                is EventsApiException.SnapshotExpired ->
                    EventsDomainException.SnapshotExpired(e.message ?: "Snapshot expired")
                is EventsApiException.ExportExpired ->
                    EventsDomainException.ExportExpired(e.message ?: "Export expired")
                is EventsApiException.ExportDownloadTooLarge ->
                    EventsDomainException.LimitExceeded(e.maxBytes, e.message ?: "Export download too large")
                is EventsApiException.SnapshotTooLarge ->
                    EventsDomainException.LimitExceeded(message = e.message ?: "Snapshot too large")
                is EventsApiException.FilterInvalid ->
                    EventsDomainException.FilterInvalid(e.invalidFields, e.message ?: "Filter invalid")
                is EventsApiException.PayloadMismatch ->
                    EventsDomainException.PayloadMismatch(e.message ?: "Payload mismatch")
                is EventsApiException.StateConflict ->
                    EventsDomainException.StateConflict(
                        e.latestEvent?.let(EventsDtoMapper::mapEvent),
                        e.message ?: "State conflict",
                    )
                is EventsApiException.ExportNotReady ->
                    EventsDomainException.ExportNotReady(e.message ?: "Export not ready")
                is EventsApiException.Generic ->
                    EventsDomainException.Generic(e.code, e.serverMessage, e.retryable)
            }
        }
    }

    override suspend fun bootstrap(
        companyId: String,
        query: EventsQuery,
        limit: Int,
    ): EventsBootstrap =
        wrapDomainErrors {
            val dto = remoteDataSource.getBootstrap(companyId, query, limit)
            EventsDtoMapper.mapBootstrap(dto)
        }

    override suspend fun getEventsPage(
        companyId: String,
        cursor: String,
        limit: Int,
    ): EventsPage =
        wrapDomainErrors {
            val dto = remoteDataSource.getEventsPage(companyId, cursor, limit)
            EventsDtoMapper.mapPage(dto)
        }

    override suspend fun getFilterOptions(companyId: String): EventsFilterOptions =
        wrapDomainErrors {
            var attempt = 0
            while (attempt < 3) {
                attempt++
                val (startCacheGen, etag, requestGen) =
                    filterOptionsMutex.withLock {
                        val stateBefore = companyFilterStates.value[companyId] ?: CompanyFilterCacheState()
                        Triple(
                            stateBefore.cacheGeneration,
                            stateBefore.cached?.etag,
                            nextFilterRequestGeneration(companyId),
                        )
                    }

                val result =
                    remoteDataSource.getFilterOptions(
                        companyId = companyId,
                        ifNoneMatchEtag = etag,
                    )

                when (result) {
                    is FilterOptionsFetchResult.Success -> {
                        val mapped = EventsDtoMapper.mapFilterOptions(result.options)
                        val acceptedOrCurrent =
                            filterOptionsMutex.withLock {
                                val current = companyFilterStates.value[companyId] ?: CompanyFilterCacheState()
                                if (current.cacheGeneration == startCacheGen &&
                                    requestGen >= current.latestAppliedRequestGen
                                ) {
                                    companyFilterStates.update { map ->
                                        map + (
                                            companyId to
                                                current.copy(
                                                    latestAppliedRequestGen = requestGen,
                                                    cached = CachedFilterOptions(options = mapped, etag = result.etag),
                                                )
                                        )
                                    }
                                    mapped
                                } else {
                                    current.cached?.options
                                }
                            }
                        if (acceptedOrCurrent != null) {
                            return@wrapDomainErrors acceptedOrCurrent
                        }
                        // Cache invalidated and empty: retry fetch for the new generation
                        continue
                    }
                    FilterOptionsFetchResult.NotModified -> {
                        val cached =
                            filterOptionsMutex.withLock {
                                val current = companyFilterStates.value[companyId] ?: CompanyFilterCacheState()
                                if (current.cacheGeneration == startCacheGen &&
                                    current.cached != null &&
                                    requestGen >= current.latestAppliedRequestGen
                                ) {
                                    companyFilterStates.update { map ->
                                        map + (companyId to current.copy(latestAppliedRequestGen = requestGen))
                                    }
                                    current.cached.options
                                } else {
                                    current.cached?.options
                                }
                            }
                        if (cached != null) {
                            return@wrapDomainErrors cached
                        }

                        val freshResult = remoteDataSource.getFilterOptions(companyId, ifNoneMatchEtag = null)
                        if (freshResult is FilterOptionsFetchResult.Success) {
                            val mapped = EventsDtoMapper.mapFilterOptions(freshResult.options)
                            val acceptedOrCurrent =
                                filterOptionsMutex.withLock {
                                    val now = companyFilterStates.value[companyId] ?: CompanyFilterCacheState()
                                    if (now.cacheGeneration == startCacheGen &&
                                        requestGen >= now.latestAppliedRequestGen
                                    ) {
                                        companyFilterStates.update { map ->
                                            map + (
                                                companyId to
                                                    now.copy(
                                                        latestAppliedRequestGen = requestGen,
                                                        cached =
                                                            CachedFilterOptions(
                                                                options = mapped,
                                                                etag = freshResult.etag,
                                                            ),
                                                    )
                                            )
                                        }
                                        mapped
                                    } else {
                                        now.cached?.options
                                    }
                                }
                            if (acceptedOrCurrent != null) {
                                return@wrapDomainErrors acceptedOrCurrent
                            }
                            continue
                        } else {
                            throw IllegalStateException(
                                "Server returned NotModified (304) on unconditional filter options request",
                            )
                        }
                    }
                }
            }
            throw EventsDomainException.Generic(
                code = "FILTER_CACHE_INVALIDATED",
                serverMessage = "Filter options cache invalidated during fetch; retries exhausted",
                retryable = true,
            )
        }

    override suspend fun createExport(
        companyId: String,
        request: EventsExportRequest,
        idempotencyKey: String,
    ): EventsExportStatus =
        wrapDomainErrors {
            val dto = EventsDtoMapper.mapExportRequestToDto(request)
            val statusDto = remoteDataSource.createExport(companyId, dto, idempotencyKey)
            EventsDtoMapper.mapExportStatus(statusDto)
        }

    override suspend fun getExportStatus(
        companyId: String,
        exportId: String,
    ): EventsExportStatus =
        wrapDomainErrors {
            val statusDto = remoteDataSource.getExportStatus(companyId, exportId)
            EventsDtoMapper.mapExportStatus(statusDto)
        }

    override suspend fun downloadExport(
        companyId: String,
        exportId: String,
    ): EventsExportDownload = wrapDomainErrors { remoteDataSource.downloadExport(companyId, exportId) }

    override suspend fun acknowledgeEvent(
        companyId: String,
        eventId: String,
    ): EventsOperationResult<Event> =
        try {
            val dto = remoteDataSource.acknowledgeEvent(companyId, eventId)
            EventsOperationResult.Success(EventsDtoMapper.mapEvent(dto))
        } catch (e: CancellationException) {
            throw e
        } catch (e: EventsApiException.StateConflict) {
            EventsOperationResult.Failure(
                EventsMutationError.StateConflict(e.latestEvent?.let(EventsDtoMapper::mapEvent)),
            )
        } catch (e: EventsApiException.PayloadMismatch) {
            EventsOperationResult.Failure(EventsMutationError.PayloadMismatch)
        } catch (e: EventsApiException.PermissionDenied) {
            EventsOperationResult.Failure(EventsMutationError.PermissionDenied)
        } catch (e: EventsApiException.Generic) {
            val err =
                if (e.code == "403" || e.code == "PERMISSION_DENIED") {
                    EventsMutationError.PermissionDenied
                } else if (e.retryable) {
                    EventsMutationError.Network
                } else {
                    EventsMutationError.Server(e.message)
                }
            EventsOperationResult.Failure(err)
        } catch (e: Throwable) {
            val err =
                if (isConnectivityError(e)) {
                    EventsMutationError.Network
                } else {
                    EventsMutationError.Server(e.message)
                }
            EventsOperationResult.Failure(err)
        }

    override suspend fun markEventErroneous(
        companyId: String,
        eventId: String,
        expectedClassificationVersion: Long,
        idempotencyKey: String,
    ): EventsOperationResult<Event> =
        try {
            val dto =
                remoteDataSource.markEventErroneous(
                    companyId = companyId,
                    eventId = eventId,
                    expectedClassificationVersion = expectedClassificationVersion,
                    idempotencyKey = idempotencyKey,
                )
            EventsOperationResult.Success(EventsDtoMapper.mapEvent(dto))
        } catch (e: CancellationException) {
            throw e
        } catch (e: EventsApiException.StateConflict) {
            EventsOperationResult.Failure(
                EventsMutationError.StateConflict(e.latestEvent?.let(EventsDtoMapper::mapEvent)),
            )
        } catch (e: EventsApiException.PayloadMismatch) {
            EventsOperationResult.Failure(EventsMutationError.PayloadMismatch)
        } catch (e: EventsApiException.PermissionDenied) {
            EventsOperationResult.Failure(EventsMutationError.PermissionDenied)
        } catch (e: EventsApiException.Generic) {
            val err =
                if (e.code == "403" || e.code == "PERMISSION_DENIED") {
                    EventsMutationError.PermissionDenied
                } else if (e.retryable) {
                    EventsMutationError.Network
                } else {
                    EventsMutationError.Server(e.message)
                }
            EventsOperationResult.Failure(err)
        } catch (e: Throwable) {
            val err =
                if (isConnectivityError(e)) {
                    EventsMutationError.Network
                } else {
                    EventsMutationError.Server(e.message)
                }
            EventsOperationResult.Failure(err)
        }
}
