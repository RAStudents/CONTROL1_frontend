package studio.acmenra.control.feature.events.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.core.network.auth.AccessInvalidationListener
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.feature.events.data.datasource.EventsApiException
import studio.acmenra.control.feature.events.data.datasource.EventsRemoteDataSource
import studio.acmenra.control.feature.events.data.datasource.EventsWebSocketDataSource
import studio.acmenra.control.feature.events.data.datasource.FilterOptionsFetchResult
import studio.acmenra.control.feature.events.data.mapper.EventsDtoMapper
import studio.acmenra.control.feature.events.data.realtime.EventsFeedSessionImpl
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsBootstrap
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

    override suspend fun bootstrap(
        companyId: String,
        query: EventsQuery,
        limit: Int,
    ): EventsBootstrap {
        val dto = remoteDataSource.getBootstrap(companyId, query, limit)
        return EventsDtoMapper.mapBootstrap(dto)
    }

    override suspend fun getEventsPage(
        companyId: String,
        cursor: String,
        limit: Int,
    ): EventsPage {
        val dto = remoteDataSource.getEventsPage(companyId, cursor, limit)
        return EventsDtoMapper.mapPage(dto)
    }

    override suspend fun getFilterOptions(companyId: String): EventsFilterOptions {
        val stateBefore = companyFilterStates.value[companyId] ?: CompanyFilterCacheState()
        val startCacheGen: Long = stateBefore.cacheGeneration
        val requestGen: Long = nextFilterRequestGeneration(companyId)

        val cached = stateBefore.cached
        val result =
            remoteDataSource.getFilterOptions(
                companyId = companyId,
                ifNoneMatchEtag = cached?.etag,
            )

        filterOptionsMutex.withLock {
            return when (result) {
                is FilterOptionsFetchResult.Success -> {
                    val mapped = EventsDtoMapper.mapFilterOptions(result.options)
                    companyFilterStates.update { map ->
                        val current = map[companyId] ?: CompanyFilterCacheState()
                        if (current.cacheGeneration == startCacheGen && requestGen >= current.latestAppliedRequestGen) {
                            map + (
                                companyId to
                                    current.copy(
                                        latestAppliedRequestGen = requestGen,
                                        cached = CachedFilterOptions(options = mapped, etag = result.etag),
                                    )
                            )
                        } else {
                            map
                        }
                    }
                    mapped
                }
                FilterOptionsFetchResult.NotModified -> {
                    val current = companyFilterStates.value[companyId] ?: CompanyFilterCacheState()
                    if (current.cacheGeneration == startCacheGen &&
                        current.cached != null &&
                        requestGen >= current.latestAppliedRequestGen
                    ) {
                        current.cached.options
                    } else {
                        // Cached body missing or invalidated in-flight, re-fetch without ETag
                        val freshResult = remoteDataSource.getFilterOptions(companyId, ifNoneMatchEtag = null)
                        if (freshResult is FilterOptionsFetchResult.Success) {
                            val mapped = EventsDtoMapper.mapFilterOptions(freshResult.options)
                            companyFilterStates.update { map ->
                                val now = map[companyId] ?: CompanyFilterCacheState()
                                if (now.cacheGeneration == current.cacheGeneration &&
                                    requestGen >= now.latestAppliedRequestGen
                                ) {
                                    map + (
                                        companyId to
                                            now.copy(
                                                latestAppliedRequestGen = requestGen,
                                                cached = CachedFilterOptions(options = mapped, etag = freshResult.etag),
                                            )
                                    )
                                } else {
                                    map
                                }
                            }
                            mapped
                        } else {
                            throw IllegalStateException(
                                "Server returned NotModified (304) on unconditional filter options request",
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun createExport(
        companyId: String,
        request: EventsExportRequest,
        idempotencyKey: String,
    ): EventsExportStatus {
        val dto = EventsDtoMapper.mapExportRequestToDto(request)
        val statusDto = remoteDataSource.createExport(companyId, dto, idempotencyKey)
        return EventsDtoMapper.mapExportStatus(statusDto)
    }

    override suspend fun getExportStatus(
        companyId: String,
        exportId: String,
    ): EventsExportStatus {
        val statusDto = remoteDataSource.getExportStatus(companyId, exportId)
        return EventsDtoMapper.mapExportStatus(statusDto)
    }

    override suspend fun downloadExport(
        companyId: String,
        exportId: String,
    ): EventsExportDownload = remoteDataSource.downloadExport(companyId, exportId)

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
                if (e.message?.contains("403") == true || e.message?.contains("PermissionDenied") == true) {
                    EventsMutationError.PermissionDenied
                } else if (e.message?.contains("Network") == true || e.message?.contains("Connect") == true) {
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
                if (e.message?.contains("403") == true || e.message?.contains("PermissionDenied") == true) {
                    EventsMutationError.PermissionDenied
                } else if (e.message?.contains("Network") == true || e.message?.contains("Connect") == true) {
                    EventsMutationError.Network
                } else {
                    EventsMutationError.Server(e.message)
                }
            EventsOperationResult.Failure(err)
        }
}
