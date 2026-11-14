package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.domain.concurrent.TenantRequestKey
import studio.acmenra.control.core.access.domain.concurrent.TenantSingleFlight
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.repository.AccessRepository
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import kotlin.time.Duration

class TenantContextCoordinator(
    private val accessRepository: AccessRepository,
    private val accessManager: AccessManager,
    private val sessionManager: SessionManager,
    private val mapper: AccessMapper,
    private val singleFlight: TenantSingleFlight = TenantSingleFlight(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private var currentRequestGeneration: Long = 0L
    private var expirationJob: Job? = null

    private val _tenantState = MutableStateFlow<TenantContextState>(TenantContextState.NotSelected)
    val tenantState: StateFlow<TenantContextState> = _tenantState.asStateFlow()

    init {
        scope.launch {
            sessionManager.sessionState.collect { sessionState ->
                when (sessionState) {
                    is SessionState.Unauthenticated, is SessionState.SessionExpired -> {
                        reset()
                    }

                    is SessionState.Authenticated -> {
                        // Keep current or wait for ensureTenantContext
                    }
                }
            }
        }
    }

    suspend fun ensureTenantContext(requestedCompanyId: String) {
        val current = _tenantState.value
        val currentCompanyId =
            when (current) {
                is TenantContextState.Authorized -> current.context.companyId
                is TenantContextState.Restricted -> current.companyId
                is TenantContextState.Error -> current.companyId
                is TenantContextState.Loading -> current.requestedCompanyId
                is TenantContextState.NotSelected -> null
            }

        if (currentCompanyId == requestedCompanyId) {
            if (current is TenantContextState.Authorized && current.context.requiresImmediateRevalidation) {
                refreshCurrentTenant(force = true)
            }
            return
        }

        selectTenant(requestedCompanyId)
    }

    suspend fun selectTenant(companyId: String) {
        val generation: Long
        mutex.withLock {
            expirationJob?.cancel()
            expirationJob = null
            currentRequestGeneration++
            generation = currentRequestGeneration
            _tenantState.value = TenantContextState.Loading(companyId)
        }

        val snapshot = sessionManager.getSnapshot()
        if (snapshot == null) {
            mutex.withLock {
                if (generation == currentRequestGeneration) {
                    _tenantState.value =
                        TenantContextState.Restricted.AccessDenied(
                            companyId = companyId,
                            canSwitchCompany = false,
                        )
                }
            }
            return
        }

        val userContext = accessManager.ensureGlobalContext()
        val userId =
            userContext?.userId ?: snapshot.tokens.accessToken
                .hashCode()
                .toString()
        val canSwitch = userContext?.hasMultipleCompanies ?: false

        val key =
            TenantRequestKey(
                sessionRevision = snapshot.revision,
                userId = userId,
                companyId = companyId,
            )

        val result =
            singleFlight.execute(key) {
                accessRepository.getCompanyAccess(
                    userId = userId,
                    companyId = companyId,
                    forceRefresh = false,
                )
            }

        mutex.withLock {
            if (generation != currentRequestGeneration) {
                return@withLock
            }

            when (result) {
                is NetworkResult.Success -> {
                    val domain = result.data
                    val context =
                        mapper.mapAuthorizedTenantContext(
                            userId = userId,
                            domain = domain,
                            tenantRevision = generation,
                        )

                    when {
                        domain.companyStatus == CompanyStatus.RESTRICTED -> {
                            val isOverrideActive =
                                domain.accessStatus == AccessStatus.ACTIVE_SUPPORT_OVERRIDE &&
                                    context.supportGrant != null &&
                                    !context.isSupportGrantLocallyExpired

                            if (isOverrideActive) {
                                applyAuthorizedContextLocked(context)
                            } else {
                                _tenantState.value =
                                    TenantContextState.Restricted.CompanyFrozen(
                                        companyId = companyId,
                                        companyName = domain.companyName,
                                        restriction = domain.restriction,
                                        canSwitchCompany = canSwitch,
                                    )
                            }
                        }

                        context.effectiveTenantPermissions.isEmpty() &&
                            context.effectiveSupportPermissions.isEmpty() -> {
                            _tenantState.value =
                                TenantContextState.Restricted.NoPermissions(
                                    companyId = companyId,
                                    companyName = domain.companyName,
                                    canSwitchCompany = canSwitch,
                                )
                        }

                        else -> {
                            applyAuthorizedContextLocked(context)
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    val error = result.error
                    val isAccessDenied =
                        error is NetworkError.Http &&
                            error.statusCode == 403 &&
                            error.errorBody?.contains("COMPANY_ACCESS_DENIED") == true

                    val isCompanyRestricted =
                        error is NetworkError.Http &&
                            error.statusCode == 403 &&
                            error.errorBody?.contains("COMPANY_RESTRICTED") == true

                    _tenantState.value =
                        when {
                            isAccessDenied -> {
                                TenantContextState.Restricted.AccessDenied(
                                    companyId = companyId,
                                    canSwitchCompany = canSwitch,
                                )
                            }

                            isCompanyRestricted -> {
                                TenantContextState.Restricted.CompanyFrozen(
                                    companyId = companyId,
                                    companyName = null,
                                    restriction = null,
                                    canSwitchCompany = canSwitch,
                                )
                            }

                            else -> {
                                TenantContextState.Error(
                                    companyId = companyId,
                                    error = error,
                                    canSwitchCompany = canSwitch,
                                )
                            }
                        }
                }
            }
        }
    }

    suspend fun refreshCurrentTenant(force: Boolean = true) {
        val current = _tenantState.value
        val (companyId, userId) =
            when (current) {
                is TenantContextState.Authorized -> current.context.companyId to current.context.userId
                is TenantContextState.Restricted ->
                    current.companyId to
                        (accessManager.currentUserContext()?.userId ?: "")
                is TenantContextState.Error -> current.companyId to (accessManager.currentUserContext()?.userId ?: "")
                is TenantContextState.Loading ->
                    current.requestedCompanyId to
                        (accessManager.currentUserContext()?.userId ?: "")
                is TenantContextState.NotSelected -> return
            }

        if (current is TenantContextState.Authorized) {
            _tenantState.value = current.copy(isRefreshing = true)
        }

        val result = accessRepository.getCompanyAccess(userId = userId, companyId = companyId, forceRefresh = force)

        mutex.withLock {
            val latest = _tenantState.value
            val isSameCompany =
                when (latest) {
                    is TenantContextState.Authorized -> latest.context.companyId == companyId
                    is TenantContextState.Restricted -> latest.companyId == companyId
                    is TenantContextState.Error -> latest.companyId == companyId
                    else -> false
                }

            if (!isSameCompany) return@withLock

            val userContext = accessManager.currentUserContext()
            val canSwitch = userContext?.hasMultipleCompanies ?: false

            when (result) {
                is NetworkResult.Success -> {
                    val domain = result.data
                    val context =
                        mapper.mapAuthorizedTenantContext(
                            userId = userId,
                            domain = domain,
                            tenantRevision = currentRequestGeneration,
                        )

                    when {
                        domain.companyStatus == CompanyStatus.RESTRICTED -> {
                            val isOverrideActive =
                                domain.accessStatus == AccessStatus.ACTIVE_SUPPORT_OVERRIDE &&
                                    context.supportGrant != null &&
                                    !context.isSupportGrantLocallyExpired

                            if (isOverrideActive) {
                                applyAuthorizedContextLocked(context)
                            } else {
                                expirationJob?.cancel()
                                expirationJob = null
                                _tenantState.value =
                                    TenantContextState.Restricted.CompanyFrozen(
                                        companyId = companyId,
                                        companyName = domain.companyName,
                                        restriction = domain.restriction,
                                        canSwitchCompany = canSwitch,
                                    )
                            }
                        }

                        context.effectiveTenantPermissions.isEmpty() &&
                            context.effectiveSupportPermissions.isEmpty() -> {
                            expirationJob?.cancel()
                            expirationJob = null
                            _tenantState.value =
                                TenantContextState.Restricted.NoPermissions(
                                    companyId = companyId,
                                    companyName = domain.companyName,
                                    canSwitchCompany = canSwitch,
                                )
                        }

                        else -> {
                            applyAuthorizedContextLocked(context)
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    if (latest is TenantContextState.Authorized) {
                        _tenantState.value = latest.copy(isRefreshing = false)
                    }
                }
            }
        }
    }

    suspend fun reset() {
        mutex.withLock {
            expirationJob?.cancel()
            expirationJob = null
            currentRequestGeneration++
            _tenantState.value = TenantContextState.NotSelected
        }
    }

    private fun applyAuthorizedContextLocked(context: AuthorizedTenantContext) {
        val grant = context.supportGrant
        when {
            context.isSupportGrantLocallyExpired -> {
                expirationJob?.cancel()
                expirationJob = null
                _tenantState.value =
                    TenantContextState.Authorized(
                        context = context.withSupportGrantExpiredLocally(),
                        isRefreshing = false,
                    )
                if (context.requiresImmediateRevalidation) {
                    scope.launch { refreshCurrentTenant(force = true) }
                }
            }

            grant != null && grant.status == SupportGrantStatus.ACTIVE -> {
                val remaining = grant.remainingDurationNow()
                if (remaining <= Duration.ZERO) {
                    expirationJob?.cancel()
                    expirationJob = null
                    _tenantState.value =
                        TenantContextState.Authorized(
                            context = context.withSupportGrantExpiredLocally(),
                            isRefreshing = false,
                        )
                    scope.launch { refreshCurrentTenant(force = true) }
                } else {
                    _tenantState.value = TenantContextState.Authorized(context, isRefreshing = false)
                    scheduleExpirationTimerLocked(context, remaining)
                }
            }

            else -> {
                expirationJob?.cancel()
                expirationJob = null
                _tenantState.value = TenantContextState.Authorized(context, isRefreshing = false)
            }
        }
    }

    private fun scheduleExpirationTimerLocked(
        context: AuthorizedTenantContext,
        remaining: Duration,
    ) {
        expirationJob?.cancel()
        expirationJob =
            scope.launch {
                delay(remaining)
                mutex.withLock {
                    val current = _tenantState.value
                    if (current is TenantContextState.Authorized && current.context.companyId == context.companyId) {
                        _tenantState.value =
                            TenantContextState.Authorized(
                                context = current.context.withSupportGrantExpiredLocally(),
                                isRefreshing = false,
                            )
                    }
                }
                refreshCurrentTenant(force = true)
            }
    }
}
