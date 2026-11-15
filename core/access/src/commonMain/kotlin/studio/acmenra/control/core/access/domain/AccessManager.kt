package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.data.model.AccessErrorResponseDto
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.repository.AccessRepository
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult

class AccessManager(
    private val accessRepository: AccessRepository,
    private val sessionManager: SessionManager,
    private val mapper: AccessMapper = AccessMapper(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val _globalState = MutableStateFlow<GlobalAccessState>(GlobalAccessState.Idle)
    val globalState: StateFlow<GlobalAccessState> = _globalState.asStateFlow()

    private var currentGeneration: Long = 0L
    private var inFlightDeferred: Deferred<NetworkResult<GlobalUserContext>>? = null
    private var inFlightRevision: Long = -1L
    private var inFlightForce: Boolean = false

    init {
        scope.launch {
            sessionManager.sessionState.collect { sessionState ->
                when (sessionState) {
                    is SessionState.Authenticated -> {
                        val currentRev = sessionManager.currentSessionRevision()
                        val current = _globalState.value
                        if (current !is GlobalAccessState.Ready || current.sessionRevision != currentRev) {
                            refreshGlobalContext(force = true)
                        }
                    }

                    is SessionState.Unauthenticated, is SessionState.SessionExpired -> {
                        if (_globalState.value !is GlobalAccessState.Idle) {
                            reset()
                        }
                    }
                }
            }
        }
    }

    fun currentUserContext(): GlobalUserContext? {
        val currentRev = sessionManager.currentSessionRevision()
        return when (val current = _globalState.value) {
            is GlobalAccessState.Ready -> if (current.sessionRevision == currentRev) current.context else null
            else -> null
        }
    }

    suspend fun ensureGlobalContext(): GlobalUserContext? {
        val currentRevision = sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()
        val current = _globalState.value
        if (current is GlobalAccessState.Ready && current.sessionRevision == currentRevision) {
            return current.context
        }
        refreshGlobalContext(force = false)
        return currentUserContext()
    }

    suspend fun refreshGlobalContext(force: Boolean = false): NetworkResult<GlobalUserContext> {
        val deferred =
            mutex.withLock {
                val currentRevision = sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()
                val currentState = _globalState.value
                if (!force &&
                    currentState is GlobalAccessState.Ready &&
                    currentState.sessionRevision == currentRevision
                ) {
                    return NetworkResult.Success(currentState.context)
                }

                val existing = inFlightDeferred
                val canReuseExisting =
                    existing != null &&
                        existing.isActive &&
                        inFlightRevision == currentRevision &&
                        (!force || inFlightForce)
                if (canReuseExisting) {
                    existing
                } else {
                    if (force && existing != null && existing.isActive) {
                        existing.cancel()
                    }
                    inFlightForce = force
                    val generation = ++currentGeneration
                    inFlightRevision = currentRevision

                    _globalState.value =
                        when {
                            currentState is GlobalAccessState.Ready &&
                                currentState.sessionRevision == currentRevision -> {
                                currentState.copy(isRefreshing = true)
                            }

                            else -> GlobalAccessState.InitialLoading
                        }

                    val newDeferred =
                        scope.async {
                            val result = accessRepository.getGlobalContext()
                            mutex.withLock {
                                val latestRevision =
                                    sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()
                                val isAuthenticated =
                                    sessionManager.sessionState.value is SessionState.Authenticated
                                if (isAuthenticated &&
                                    generation == currentGeneration &&
                                    latestRevision == currentRevision
                                ) {
                                    applyGlobalContextResultLocked(result, latestRevision)
                                }
                            }
                            result
                        }

                    inFlightDeferred = newDeferred
                    newDeferred
                }
            }

        return try {
            deferred.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            NetworkResult.Failure(
                NetworkError.Unknown(
                    message = "Global context refresh cancelled",
                    cause = e,
                ),
            )
        }
    }

    suspend fun reset() {
        mutex.withLock {
            currentGeneration++
            inFlightDeferred?.cancel()
            inFlightDeferred = null
            inFlightRevision = -1L
            inFlightForce = false
            _globalState.value = GlobalAccessState.Idle
            accessRepository.clearCache()
        }
    }

    private fun applyGlobalContextResultLocked(
        result: NetworkResult<GlobalUserContext>,
        revision: Long,
    ) {
        when (result) {
            is NetworkResult.Success -> {
                val context = result.data
                val newState =
                    when {
                        context.memberCompanies.isEmpty() && !context.canBrowseAllCompanies -> {
                            GlobalAccessState.Restricted.NoCompaniesAvailable
                        }

                        else -> {
                            GlobalAccessState.Ready(
                                context = context,
                                sessionRevision = revision,
                                isRefreshing = false,
                            )
                        }
                    }
                _globalState.value = newState
            }

            is NetworkResult.Failure -> {
                val error = result.error
                val errorDto =
                    if (error is NetworkError.Http) {
                        AccessErrorResponseDto.parse(error.errorBody)
                    } else {
                        null
                    }
                val isAccountFrozen =
                    error is NetworkError.Http &&
                        error.statusCode == 403 &&
                        errorDto?.resolvedCode == "ACCOUNT_FROZEN"

                _globalState.value =
                    when {
                        isAccountFrozen -> {
                            val restriction = errorDto.restriction?.let { mapper.mapRestriction(it) }
                            GlobalAccessState.Restricted.AccountFrozen(restriction)
                        }

                        _globalState.value is GlobalAccessState.Ready -> {
                            (_globalState.value as GlobalAccessState.Ready).copy(isRefreshing = false)
                        }

                        else -> {
                            GlobalAccessState.Error(error)
                        }
                    }
            }
        }
    }
}
