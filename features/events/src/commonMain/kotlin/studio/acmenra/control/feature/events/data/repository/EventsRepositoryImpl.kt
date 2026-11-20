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
    private val filterCache = MutableStateFlow<Map<String, CachedFilterOptions>>(emptyMap())
    private val cacheGenerations = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val filterRequestGenerations = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val latestAppliedRequestGenerations = MutableStateFlow<Map<String, Long>>(emptyMap())

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

    override fun invalidateFilterCache(companyId: String) {
        val nextGen = (cacheGenerations.value[companyId] ?: 0L) + 1L
        cacheGenerations.update { it + (companyId to nextGen) }
        filterCache.update { it - companyId }
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
        val startCacheGen: Long = cacheGenerations.value[companyId] ?: 0L
        val requestGen: Long = nextFilterRequestGeneration(companyId)

        val cached = filterCache.value[companyId]
        val result =
            remoteDataSource.getFilterOptions(
                companyId = companyId,
                ifNoneMatchEtag = cached?.etag,
            )

        filterOptionsMutex.withLock {
            val currentCacheGen: Long = cacheGenerations.value[companyId] ?: 0L
            val latestAppliedReq: Long = latestAppliedRequestGenerations.value[companyId] ?: 0L
            val isCurrentRequest: Boolean = requestGen >= latestAppliedReq && currentCacheGen == startCacheGen

            return when (result) {
                is FilterOptionsFetchResult.Success -> {
                    val mapped = EventsDtoMapper.mapFilterOptions(result.options)
                    if (isCurrentRequest) {
                        latestAppliedRequestGenerations.update { it + (companyId to requestGen) }
                        filterCache.update {
                            it +
                                (companyId to CachedFilterOptions(options = mapped, etag = result.etag))
                        }
                    }
                    mapped
                }
                FilterOptionsFetchResult.NotModified -> {
                    val currentCached = filterCache.value[companyId]
                    if (isCurrentRequest && currentCached != null) {
                        currentCached.options
                    } else {
                        // Cached body missing or invalidated in-flight, re-fetch without ETag
                        val freshResult = remoteDataSource.getFilterOptions(companyId, ifNoneMatchEtag = null)
                        if (freshResult is FilterOptionsFetchResult.Success) {
                            val mapped = EventsDtoMapper.mapFilterOptions(freshResult.options)
                            val nowCacheGen: Long = cacheGenerations.value[companyId] ?: 0L
                            val nowLatestReq: Long = latestAppliedRequestGenerations.value[companyId] ?: 0L
                            if (requestGen >= nowLatestReq && nowCacheGen == currentCacheGen) {
                                latestAppliedRequestGenerations.update { it + (companyId to requestGen) }
                                filterCache.update {
                                    it +
                                        (companyId to CachedFilterOptions(options = mapped, etag = freshResult.etag))
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
