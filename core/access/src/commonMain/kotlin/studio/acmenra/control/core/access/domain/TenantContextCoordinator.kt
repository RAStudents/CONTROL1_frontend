package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.data.model.AccessErrorResponseDto
import studio.acmenra.control.core.access.domain.concurrent.TenantRequestKey
import studio.acmenra.control.core.access.domain.concurrent.TenantSingleFlight
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val singleFlight: TenantSingleFlight = TenantSingleFlight(scope),
) {
    private val mutex = Mutex()
    private var globalTenantEpoch: Long = 0L
    private val companyGenerations = mutableMapOf<String, Long>()
    private var expirationJob: Job? = null
    private var activeSelectionJob: Deferred<Unit>? = null

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
        val deferred =
            mutex.withLock {
                expirationJob?.cancel()
                expirationJob = null
                activeSelectionJob?.cancel()

                val requestEpoch = globalTenantEpoch
                val generation = (companyGenerations[companyId] ?: 0L) + 1L
                companyGenerations[companyId] = generation
                val requestSessionRevision =
                    sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()

                _tenantState.value = TenantContextState.Loading(companyId)

                val newDeferred =
                    scope.async {
                        doSelectTenant(companyId, generation, requestEpoch, requestSessionRevision)
                    }
                activeSelectionJob = newDeferred
                newDeferred
            }

        deferred.await()
    }

    private suspend fun doSelectTenant(
        companyId: String,
        generation: Long,
        requestEpoch: Long,
        requestSessionRevision: Long,
    ) {
        val snapshot = sessionManager.getSessionSnapshot()
        if (snapshot == null) {
            mutex.withLock {
                val latest = _tenantState.value
                val latestRevision = sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()
                val currentGen = companyGenerations[companyId] ?: 0L
                if (requestEpoch == globalTenantEpoch &&
                    generation == currentGen &&
                    latestRevision == requestSessionRevision &&
                    isSameCompanyAsLatest(latest, companyId)
                ) {
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
                sessionRevision = requestSessionRevision,
                userId = userId,
                companyId = companyId,
                forceRefresh = false,
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
            val latest = _tenantState.value
            val latestRevision = sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()
            val isAuthenticated = sessionManager.sessionState.value is SessionState.Authenticated
            val currentGen = companyGenerations[companyId] ?: 0L

            if (requestEpoch != globalTenantEpoch ||
                generation != currentGen ||
                latestRevision != requestSessionRevision ||
                !isAuthenticated ||
                !isSameCompanyAsLatest(latest, companyId)
            ) {
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

                    applyDomainResultLocked(
                        domain = domain,
                        companyId = companyId,
                        canSwitch = canSwitch,
                        context = context,
                    )
                }

                is NetworkResult.Failure -> {
                    val error = result.error
                    val errorDto =
                        if (error is NetworkError.Http) {
                            AccessErrorResponseDto.parse(error.errorBody)
                        } else {
                            null
                        }
                    val resolvedCode = errorDto?.resolvedCode

                    val isAccessDenied =
                        error is NetworkError.Http &&
                            error.statusCode == 403 &&
                            (resolvedCode == "COMPANY_ACCESS_DENIED" || resolvedCode == "ACCESS_DENIED")

                    val isCompanyRestricted =
                        error is NetworkError.Http &&
                            error.statusCode == 403 &&
                            (resolvedCode == "COMPANY_RESTRICTED" || resolvedCode == "ACCOUNT_FROZEN")

                    _tenantState.value =
                        when {
                            isAccessDenied -> {
                                TenantContextState.Restricted.AccessDenied(
                                    companyId = companyId,
                                    canSwitchCompany = canSwitch,
                                )
                            }

                            isCompanyRestricted -> {
                                val restriction = errorDto.restriction?.let { mapper.mapRestriction(it) }
                                TenantContextState.Restricted.CompanyFrozen(
                                    companyId = companyId,
                                    companyName = null,
                                    restriction = restriction,
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
        refreshTenant(targetCompanyId = null, force = force)
    }

    suspend fun refreshTenant(
        targetCompanyId: String? = null,
        force: Boolean = true,
    ) {
        val current = _tenantState.value
        val currentCompanyId =
            when (current) {
                is TenantContextState.Authorized -> current.context.companyId
                is TenantContextState.Restricted -> current.companyId
                is TenantContextState.Error -> current.companyId
                is TenantContextState.Loading -> current.requestedCompanyId
                is TenantContextState.NotSelected -> null
            }

        val companyId = targetCompanyId ?: currentCompanyId ?: return
        val isTargetActiveTenant = currentCompanyId == companyId

        val userId =
            when (current) {
                is TenantContextState.Authorized -> current.context.userId
                else -> accessManager.currentUserContext()?.userId ?: ""
            }

        val sessionRevision = sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()

        val (requestEpoch, generation) =
            mutex.withLock {
                if (isTargetActiveTenant && current is TenantContextState.Authorized) {
                    _tenantState.value = current.copy(isRefreshing = true)
                }
                val nextGen = (companyGenerations[companyId] ?: 0L) + 1L
                companyGenerations[companyId] = nextGen
                globalTenantEpoch to nextGen
            }

        val key =
            TenantRequestKey(
                sessionRevision = sessionRevision,
                userId = userId,
                companyId = companyId,
                forceRefresh = force,
            )

        val result =
            singleFlight.execute(key) {
                accessRepository.getCompanyAccess(userId = userId, companyId = companyId, forceRefresh = force)
            }

        mutex.withLock {
            val latest = _tenantState.value
            val latestRevision = sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()
            val isAuthenticated = sessionManager.sessionState.value is SessionState.Authenticated
            val currentGen = companyGenerations[companyId] ?: 0L

            if (requestEpoch != globalTenantEpoch ||
                generation != currentGen ||
                latestRevision != sessionRevision ||
                !isAuthenticated ||
                !isSameCompanyAsLatest(latest, companyId)
            ) {
                return@withLock
            }

            val userContext = accessManager.currentUserContext()
            val canSwitch = userContext?.hasMultipleCompanies ?: false

            when (result) {
                is NetworkResult.Success -> {
                    val domain = result.data
                    val context =
                        mapper.mapAuthorizedTenantContext(
                            userId = userId,
                            domain = domain,
                            tenantRevision = generation,
                        )

                    applyDomainResultLocked(
                        domain = domain,
                        companyId = companyId,
                        canSwitch = canSwitch,
                        context = context,
                    )
                }

                is NetworkResult.Failure -> {
                    val error = result.error
                    val errorDto =
                        if (error is NetworkError.Http) {
                            AccessErrorResponseDto.parse(error.errorBody)
                        } else {
                            null
                        }
                    val resolvedCode = errorDto?.resolvedCode

                    val isAccessDenied =
                        error is NetworkError.Http &&
                            error.statusCode == 403 &&
                            (resolvedCode == "COMPANY_ACCESS_DENIED" || resolvedCode == "ACCESS_DENIED")

                    val isCompanyRestricted =
                        error is NetworkError.Http &&
                            error.statusCode == 403 &&
                            (resolvedCode == "COMPANY_RESTRICTED" || resolvedCode == "ACCOUNT_FROZEN")

                    when {
                        isAccessDenied -> {
                            expirationJob?.cancel()
                            expirationJob = null
                            _tenantState.value =
                                TenantContextState.Restricted.AccessDenied(
                                    companyId = companyId,
                                    canSwitchCompany = canSwitch,
                                )
                        }

                        isCompanyRestricted -> {
                            expirationJob?.cancel()
                            expirationJob = null
                            val restriction = errorDto.restriction?.let { mapper.mapRestriction(it) }
                            _tenantState.value =
                                TenantContextState.Restricted.CompanyFrozen(
                                    companyId = companyId,
                                    companyName = null,
                                    restriction = restriction,
                                    canSwitchCompany = canSwitch,
                                )
                        }

                        latest is TenantContextState.Authorized -> {
                            _tenantState.value = latest.copy(isRefreshing = false)
                        }
                    }
                }
            }
        }
    }

    suspend fun reset() {
        mutex.withLock {
            expirationJob?.cancel()
            expirationJob = null
            activeSelectionJob?.cancel()
            activeSelectionJob = null
            globalTenantEpoch++
            companyGenerations.clear()
            _tenantState.value = TenantContextState.NotSelected
        }
    }

    private fun applyDomainResultLocked(
        domain: CompanyAccessDomain,
        companyId: String,
        canSwitch: Boolean,
        context: AuthorizedTenantContext,
    ) {
        val isAuthorized =
            when {
                domain.companyStatus == CompanyStatus.RESTRICTED -> {
                    domain.accessStatus == AccessStatus.ACTIVE_SUPPORT_OVERRIDE &&
                        context.supportGrant != null &&
                        !context.isSupportGrantLocallyExpired
                }

                domain.companyStatus == CompanyStatus.ACTIVE -> {
                    when (domain.accessStatus) {
                        AccessStatus.ACTIVE -> true
                        AccessStatus.ACTIVE_SUPPORT_OVERRIDE -> {
                            context.supportGrant != null && !context.isSupportGrantLocallyExpired
                        }

                        AccessStatus.RESTRICTED, AccessStatus.UNKNOWN -> false
                    }
                }

                else -> false
            }

        when {
            isAuthorized -> {
                if (context.effectiveTenantPermissions.isEmpty() &&
                    context.effectiveSupportPermissions.isEmpty()
                ) {
                    expirationJob?.cancel()
                    expirationJob = null
                    _tenantState.value =
                        TenantContextState.Restricted.NoPermissions(
                            companyId = companyId,
                            companyName = domain.companyName,
                            canSwitchCompany = canSwitch,
                        )
                } else {
                    applyAuthorizedContextLocked(context)
                }
            }

            domain.companyStatus == CompanyStatus.RESTRICTED ||
                domain.accessStatus == AccessStatus.RESTRICTED -> {
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

            else -> {
                expirationJob?.cancel()
                expirationJob = null
                _tenantState.value =
                    TenantContextState.Restricted.AccessDenied(
                        companyId = companyId,
                        companyName = domain.companyName,
                        canSwitchCompany = canSwitch,
                    )
            }
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

    private fun isSameCompanyAsLatest(
        latest: TenantContextState,
        companyId: String,
    ): Boolean =
        when (latest) {
            is TenantContextState.Authorized -> latest.context.companyId == companyId
            is TenantContextState.Restricted -> latest.companyId == companyId
            is TenantContextState.Error -> latest.companyId == companyId
            is TenantContextState.Loading -> latest.requestedCompanyId == companyId
            is TenantContextState.NotSelected -> false
        }
}
