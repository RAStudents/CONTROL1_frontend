package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.data.model.AccessErrorResponseDto
import studio.acmenra.control.core.access.domain.concurrent.TenantRequestKey
import studio.acmenra.control.core.access.domain.concurrent.TenantSingleFlight
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.repository.AccessRepository
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import kotlin.coroutines.coroutineContext
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
    private var activeSelectionCompanyId: String? = null
    private var activeSelectionJob: Deferred<Unit>? = null

    private data class ActiveRefresh(
        val force: Boolean,
        val job: Deferred<Unit>,
    )

    private val activeRefreshJobs = mutableMapOf<String, ActiveRefresh>()

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
                if (activeSelectionCompanyId == companyId && activeSelectionJob?.isActive == true) {
                    activeSelectionJob!!
                } else {
                    expirationJob?.cancel()
                    expirationJob = null
                    activeSelectionJob?.cancel()

                    val requestEpoch = globalTenantEpoch
                    val generation = (companyGenerations[companyId] ?: 0L) + 1L
                    companyGenerations[companyId] = generation
                    val requestSessionRevision =
                        sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()

                    _tenantState.value = TenantContextState.Loading(companyId)
                    activeSelectionCompanyId = companyId

                    val newDeferred =
                        scope.async {
                            try {
                                doSelectTenant(companyId, generation, requestEpoch, requestSessionRevision)
                            } finally {
                                withContext(NonCancellable) {
                                    mutex.withLock {
                                        if (activeSelectionJob === coroutineContext[Job]) {
                                            activeSelectionCompanyId = null
                                            activeSelectionJob = null
                                        }
                                    }
                                }
                            }
                        }
                    activeSelectionJob = newDeferred
                    newDeferred
                }
            }

        try {
            deferred.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
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
        if (userContext == null) {
            mutex.withLock {
                val latest = _tenantState.value
                val latestRevision = sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()
                val currentGen = companyGenerations[companyId] ?: 0L
                if (requestEpoch == globalTenantEpoch &&
                    generation == currentGen &&
                    latestRevision == requestSessionRevision &&
                    isSameCompanyAsLatest(latest, companyId)
                ) {
                    val globalState = accessManager.globalState.value
                    _tenantState.value =
                        when (globalState) {
                            is GlobalAccessState.Restricted.AccountFrozen -> {
                                TenantContextState.Restricted.CompanyFrozen(
                                    companyId = companyId,
                                    companyName = null,
                                    restriction = globalState.restriction,
                                    canSwitchCompany = false,
                                )
                            }

                            is GlobalAccessState.Restricted.NoCompaniesAvailable -> {
                                TenantContextState.Restricted.AccessDenied(
                                    companyId = companyId,
                                    canSwitchCompany = false,
                                )
                            }

                            else -> {
                                TenantContextState.Error(
                                    companyId = companyId,
                                    error =
                                        NetworkError.Unknown(
                                            message = "Failed to load user global context",
                                        ),
                                    canSwitchCompany = false,
                                )
                            }
                        }
                }
            }
            return
        }

        val userId = userContext.userId
        val canSwitch = userContext.hasMultipleCompanies

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

        val deferred =
            mutex.withLock {
                val existing = activeRefreshJobs[companyId]
                if (existing != null && existing.job.isActive && (existing.force || !force)) {
                    existing.job
                } else {
                    if (force && existing != null && existing.job.isActive) {
                        existing.job.cancel()
                    }
                    val currentState = _tenantState.value
                    val isCurrentActiveTenant =
                        when (currentState) {
                            is TenantContextState.Authorized -> currentState.context.companyId == companyId
                            is TenantContextState.Restricted -> currentState.companyId == companyId
                            is TenantContextState.Error -> currentState.companyId == companyId
                            is TenantContextState.Loading -> currentState.requestedCompanyId == companyId
                            is TenantContextState.NotSelected -> false
                        }
                    if (isCurrentActiveTenant && currentState is TenantContextState.Authorized) {
                        _tenantState.value = currentState.copy(isRefreshing = true)
                    }
                    val requestEpoch = globalTenantEpoch
                    val generation = (companyGenerations[companyId] ?: 0L) + 1L
                    companyGenerations[companyId] = generation
                    val sessionRevision =
                        sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()

                    val newJob =
                        scope.async {
                            try {
                                doRefreshTenant(
                                    companyId = companyId,
                                    generation = generation,
                                    requestEpoch = requestEpoch,
                                    sessionRevision = sessionRevision,
                                    force = force,
                                )
                            } finally {
                                withContext(NonCancellable) {
                                    mutex.withLock {
                                        if (activeRefreshJobs[companyId]?.job === coroutineContext[Job]) {
                                            activeRefreshJobs.remove(companyId)
                                            val latest = _tenantState.value
                                            if (latest is TenantContextState.Authorized &&
                                                latest.context.companyId == companyId &&
                                                latest.isRefreshing
                                            ) {
                                                _tenantState.value = latest.copy(isRefreshing = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    activeRefreshJobs[companyId] = ActiveRefresh(force, newJob)
                    newJob
                }
            }

        try {
            deferred.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun doRefreshTenant(
        companyId: String,
        generation: Long,
        requestEpoch: Long,
        sessionRevision: Long,
        force: Boolean,
    ) {
        val current = _tenantState.value
        val currentUserId =
            when (current) {
                is TenantContextState.Authorized -> current.context.userId
                else -> accessManager.currentUserContext()?.userId
            }

        val userContext =
            if (currentUserId == null) {
                if (force) {
                    when (val res = accessManager.refreshGlobalContext(force = true)) {
                        is NetworkResult.Success -> res.data
                        is NetworkResult.Failure -> null
                    }
                } else {
                    accessManager.ensureGlobalContext()
                }
            } else {
                null
            }

        val userId = currentUserId ?: userContext?.userId

        if (userId == null) {
            mutex.withLock {
                val latest = _tenantState.value
                val latestRevision = sessionManager.getSessionSnapshot()?.revision ?: sessionManager.currentRevision()
                val currentGen = companyGenerations[companyId] ?: 0L
                if (requestEpoch == globalTenantEpoch &&
                    generation == currentGen &&
                    latestRevision == sessionRevision &&
                    isSameCompanyAsLatest(latest, companyId)
                ) {
                    val globalState = accessManager.globalState.value
                    _tenantState.value =
                        when (globalState) {
                            is GlobalAccessState.Restricted.AccountFrozen -> {
                                TenantContextState.Restricted.CompanyFrozen(
                                    companyId = companyId,
                                    companyName = null,
                                    restriction = globalState.restriction,
                                    canSwitchCompany = false,
                                )
                            }

                            is GlobalAccessState.Restricted.NoCompaniesAvailable -> {
                                TenantContextState.Restricted.AccessDenied(
                                    companyId = companyId,
                                    canSwitchCompany = false,
                                )
                            }

                            else -> {
                                TenantContextState.Error(
                                    companyId = companyId,
                                    error =
                                        NetworkError.Unknown(
                                            message = "Failed to load user global context",
                                        ),
                                    canSwitchCompany = false,
                                )
                            }
                        }
                }
            }
            return
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

            val validUserContext = accessManager.currentUserContext() ?: userContext
            val canSwitch = validUserContext?.hasMultipleCompanies ?: false

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

                        latest is TenantContextState.Loading -> {
                            expirationJob?.cancel()
                            expirationJob = null
                            _tenantState.value =
                                TenantContextState.Error(
                                    companyId = companyId,
                                    error = error,
                                    canSwitchCompany = canSwitch,
                                )
                        }

                        latest is TenantContextState.Error -> {
                            expirationJob?.cancel()
                            expirationJob = null
                            _tenantState.value =
                                TenantContextState.Error(
                                    companyId = companyId,
                                    error = error,
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
            activeSelectionCompanyId = null
            activeRefreshJobs.values.forEach { it.job.cancel() }
            activeRefreshJobs.clear()
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
        val remaining = grant?.remainingDurationNow() ?: Duration.ZERO
        val isGrantExpired =
            grant != null &&
                (grant.status != SupportGrantStatus.ACTIVE || remaining <= Duration.ZERO)

        when {
            isGrantExpired -> {
                expirationJob?.cancel()
                expirationJob = null
                val expiredContext = context.withSupportGrantExpiredLocally()
                val userContext = accessManager.currentUserContext()
                val canSwitch = userContext?.hasMultipleCompanies ?: false
                if (context.companyStatus == CompanyStatus.RESTRICTED) {
                    _tenantState.value =
                        TenantContextState.Restricted.CompanyFrozen(
                            companyId = context.companyId,
                            companyName = context.companyName,
                            restriction = context.restriction,
                            canSwitchCompany = canSwitch,
                        )
                } else if (expiredContext.effectiveTenantPermissions.isEmpty()) {
                    _tenantState.value =
                        TenantContextState.Restricted.NoPermissions(
                            companyId = context.companyId,
                            companyName = context.companyName,
                            canSwitchCompany = canSwitch,
                        )
                } else {
                    _tenantState.value =
                        TenantContextState.Authorized(
                            context = expiredContext,
                            isRefreshing = false,
                        )
                }
            }

            grant != null && grant.status == SupportGrantStatus.ACTIVE -> {
                _tenantState.value = TenantContextState.Authorized(context, isRefreshing = false)
                scheduleExpirationTimerLocked(context, remaining)
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
        val targetGrantId = context.supportGrant?.grantId
        val targetRevision = context.tenantRevision
        expirationJob =
            scope.launch {
                delay(remaining)
                mutex.withLock {
                    val current = _tenantState.value
                    if (current is TenantContextState.Authorized &&
                        current.context.companyId == context.companyId &&
                        current.context.tenantRevision == targetRevision &&
                        current.context.supportGrant?.grantId == targetGrantId
                    ) {
                        val expiredContext = current.context.withSupportGrantExpiredLocally()
                        val userContext = accessManager.currentUserContext()
                        val canSwitch = userContext?.hasMultipleCompanies ?: false
                        if (current.context.companyStatus == CompanyStatus.RESTRICTED) {
                            _tenantState.value =
                                TenantContextState.Restricted.CompanyFrozen(
                                    companyId = context.companyId,
                                    companyName = current.context.companyName,
                                    restriction = current.context.restriction,
                                    canSwitchCompany = canSwitch,
                                )
                        } else if (expiredContext.effectiveTenantPermissions.isEmpty()) {
                            _tenantState.value =
                                TenantContextState.Restricted.NoPermissions(
                                    companyId = context.companyId,
                                    companyName = current.context.companyName,
                                    canSwitchCompany = canSwitch,
                                )
                        } else {
                            _tenantState.value =
                                TenantContextState.Authorized(
                                    context = expiredContext,
                                    isRefreshing = false,
                                )
                        }
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
