package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val _globalState = MutableStateFlow<GlobalAccessState>(GlobalAccessState.Idle)
    val globalState: StateFlow<GlobalAccessState> = _globalState.asStateFlow()

    init {
        scope.launch {
            sessionManager.sessionState.collect { sessionState ->
                when (sessionState) {
                    is SessionState.Authenticated -> {
                        if (_globalState.value is GlobalAccessState.Idle) {
                            refreshGlobalContext(force = false)
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

    fun currentUserContext(): GlobalUserContext? =
        when (val current = _globalState.value) {
            is GlobalAccessState.Ready -> current.context
            else -> null
        }

    suspend fun ensureGlobalContext(): GlobalUserContext? {
        val existing = currentUserContext()
        if (existing != null) return existing
        refreshGlobalContext(force = false)
        return currentUserContext()
    }

    suspend fun refreshGlobalContext(force: Boolean = false): NetworkResult<GlobalUserContext> =
        mutex.withLock {
            val currentState = _globalState.value
            _globalState.value =
                when (currentState) {
                    is GlobalAccessState.Ready -> currentState.copy(isRefreshing = true)
                    else -> GlobalAccessState.InitialLoading
                }

            when (val result = accessRepository.getGlobalContext()) {
                is NetworkResult.Success -> {
                    val context = result.data
                    val newState =
                        when {
                            context.memberCompanies.isEmpty() && !context.canBrowseAllCompanies -> {
                                GlobalAccessState.Restricted.NoCompaniesAvailable
                            }

                            else -> {
                                GlobalAccessState.Ready(context = context, isRefreshing = false)
                            }
                        }
                    _globalState.value = newState
                    result
                }

                is NetworkResult.Failure -> {
                    val error = result.error
                    val isAccountFrozen =
                        error is NetworkError.Http &&
                            error.statusCode == 403 &&
                            error.errorBody?.contains("ACCOUNT_FROZEN") == true

                    _globalState.value =
                        when {
                            isAccountFrozen -> {
                                GlobalAccessState.Restricted.AccountFrozen(null)
                            }

                            currentState is GlobalAccessState.Ready -> {
                                currentState.copy(isRefreshing = false)
                            }

                            else -> {
                                GlobalAccessState.Error(error)
                            }
                        }
                    result
                }
            }
        }

    suspend fun reset() {
        mutex.withLock {
            _globalState.value = GlobalAccessState.Idle
            accessRepository.clearCache()
        }
    }
}
