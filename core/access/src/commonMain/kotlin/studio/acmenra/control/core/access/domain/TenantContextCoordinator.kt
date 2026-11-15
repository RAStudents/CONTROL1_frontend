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
import studio.acmenra.control.core.access.domain.concurrent.TenantRequestKey
import studio.acmenra.control.core.access.domain.concurrent.TenantSingleFlight
import studio.acmenra.control.core.access.domain.model.AccessDomainError
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.parser.AccessErrorParser
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
    private val errorParser: AccessErrorParser,
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

    @kotlin.concurrent.Volatile
    private var tenantStateRevision: Long = -1L

    internal fun activeRefreshJobsCount(): Int = activeRefreshJobs.size

    internal fun hasActiveSelectionJob(): Boolean = activeSelectionJob != null

    init {
        scope.launch {
            sessionManager.sessionState.collect { sessionState ->
                when (sessionState) {
                    is SessionState.Unauthenticated, is SessionState.SessionExpired -> {
                        reset()
                    }

                    is SessionState.Authenticated -> {
                        val currentRev = sessionManager.currentRevision()
                        val current = _tenantState.value
                        if (current !is TenantContextState.NotSelected && tenantStateRevision != currentRev) {
                            reset()
                        }
                    }
                }
            }
        }
        scope.launch {
            accessManager.globalState.collect { globalState ->
                if (globalState is GlobalAccessState.Ready) {
                    val current = _tenantState.value
                    if (current is TenantContextState.Loading && activeSelectionJob == null) {
                        selectTenant(current.requestedCompanyId)
                    }
                }
            }
        }
    }

    suspend fun ensureTenantContext(requestedCompanyId: String) {
        val currentRevision = sessionManager.currentRevision()
        val current = _tenantState.value
        if (current !is TenantContextState.NotSelected && tenantStateRevision != currentRevision) {
            reset()
        }
        val updated = _tenantState.value
        val isAlreadySatisfied =
            when (updated) {
                is TenantContextState.Authorized -> updated.context.companyId == requestedCompanyId
                is TenantContextState.Restricted -> updated.companyId == requestedCompanyId
                is TenantContextState.Loading ->
                    updated.requestedCompanyId == requestedCompanyId &&
                        activeSelectionJob?.isActive == true
                is TenantContextState.Error, is TenantContextState.NotSelected -> false
            }

        if (isAlreadySatisfied) {
            if (updated is TenantContextState.Authorized && updated.context.requiresImmediateRevalidation) {
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
                    val requestSessionRevision = sessionManager.currentRevision()

                    setTenantStateLocked(TenantContextState.Loading(companyId), requestSessionRevision)
                    activeSelectionCompanyId = companyId

                    val newDeferred =
                        scope.async {
                            val currentJob = coroutineContext[Job]
                            try {
                                doSelectTenant(companyId, generation, requestEpoch, requestSessionRevision)
                            } finally {
                                withContext(NonCancellable) {
                                    mutex.withLock {
                                        if (activeSelectionJob === currentJob) {
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
        val snapshot = sessionManager.getSnapshot()
        if (snapshot == null) {
            mutex.withLock {
                val latest = _tenantState.value
                val latestRevision = sessionManager.currentRevision()
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
                val latestRevision = sessionManager.currentRevision()
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
            val latestRevision = sessionManager.currentRevision()
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
                        AuthorizedTenantContext.fromDomain(
                            userId = userId,
                            domain = domain,
                            tenantRevision = generation,
                        )

                    applyDomainResultLocked(
                        domain = domain,
                        companyId = companyId,
                        canSwitch = canSwitch,
                        context = context,
                        sessionRevision = latestRevision,
                    )
                }

                is NetworkResult.Failure -> {
                    applyFailureResultLocked(
                        error = result.error,
                        companyId = companyId,
                        canSwitch = canSwitch,
                        sessionRevision = latestRevision,
                        isRefreshOnAuthorized = false,
                    )
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
                    if (currentState is TenantContextState.Authorized && currentState.context.companyId == companyId) {
                        setTenantStateLocked(currentState.copy(isRefreshing = true), currentState.sessionRevision)
                    }
                    val requestEpoch = globalTenantEpoch
                    val generation = (companyGenerations[companyId] ?: 0L) + 1L
                    companyGenerations[companyId] = generation
                    val sessionRevision = sessionManager.currentRevision()

                    val newJob =
                        scope.async {
                            val currentJob = coroutineContext[Job]
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
                                        if (activeRefreshJobs[companyId]?.job === currentJob) {
                                            activeRefreshJobs.remove(companyId)
                                            val latest = _tenantState.value
                                            if (latest is TenantContextState.Authorized &&
                                                latest.context.companyId == companyId &&
                                                latest.isRefreshing
                                            ) {
                                                setTenantStateLocked(
                                                    latest.copy(isRefreshing = false),
                                                    latest.sessionRevision,
                                                )
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
                is TenantContextState.Authorized ->
                    if (current.sessionRevision == sessionRevision) {
                        current.context.userId
                    } else {
                        null
                    }

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
                val latestRevision = sessionManager.currentRevision()
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
            val latestRevision = sessionManager.currentRevision()
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
                        AuthorizedTenantContext.fromDomain(
                            userId = userId,
                            domain = domain,
                            tenantRevision = generation,
                        )

                    applyDomainResultLocked(
                        domain = domain,
                        companyId = companyId,
                        canSwitch = canSwitch,
                        context = context,
                        sessionRevision = latestRevision,
                    )
                }

                is NetworkResult.Failure -> {
                    applyFailureResultLocked(
                        error = result.error,
                        companyId = companyId,
                        canSwitch = canSwitch,
                        sessionRevision = latestRevision,
                        isRefreshOnAuthorized = latest is TenantContextState.Authorized,
                    )
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
            setTenantStateLocked(TenantContextState.NotSelected, -1L)
        }
    }

    private fun setTenantStateLocked(
        state: TenantContextState,
        sessionRevision: Long,
    ) {
        tenantStateRevision = if (state is TenantContextState.NotSelected) -1L else sessionRevision
        _tenantState.value = state
    }

    private fun applyFailureResultLocked(
        error: NetworkError,
        companyId: String,
        canSwitch: Boolean,
        sessionRevision: Long,
        isRefreshOnAuthorized: Boolean = false,
    ) {
        val domainError = errorParser.parse(error)
        when (domainError) {
            is AccessDomainError.AccessDenied -> {
                expirationJob?.cancel()
                expirationJob = null
                setTenantStateLocked(
                    TenantContextState.Restricted.AccessDenied(
                        companyId = companyId,
                        canSwitchCompany = canSwitch,
                    ),
                    sessionRevision,
                )
            }

            is AccessDomainError.CompanyRestricted, is AccessDomainError.AccountFrozen -> {
                expirationJob?.cancel()
                expirationJob = null
                val restriction =
                    when (domainError) {
                        is AccessDomainError.CompanyRestricted -> domainError.restriction
                        is AccessDomainError.AccountFrozen -> domainError.restriction
                        is AccessDomainError.AccessDenied, is AccessDomainError.Unknown -> null
                    }
                setTenantStateLocked(
                    TenantContextState.Restricted.CompanyFrozen(
                        companyId = companyId,
                        companyName = null,
                        restriction = restriction,
                        canSwitchCompany = canSwitch,
                    ),
                    sessionRevision,
                )
            }

            else -> {
                if (isRefreshOnAuthorized) {
                    val latest = _tenantState.value
                    if (latest is TenantContextState.Authorized) {
                        setTenantStateLocked(
                            latest.copy(isRefreshing = false),
                            latest.sessionRevision,
                        )
                    }
                } else {
                    expirationJob?.cancel()
                    expirationJob = null
                    setTenantStateLocked(
                        TenantContextState.Error(
                            companyId = companyId,
                            error = error,
                            canSwitchCompany = canSwitch,
                        ),
                        sessionRevision,
                    )
                }
            }
        }
    }

    private fun applyDomainResultLocked(
        domain: CompanyAccessDomain,
        companyId: String,
        canSwitch: Boolean,
        context: AuthorizedTenantContext,
        sessionRevision: Long = -1L,
    ) {
        val currentUserContext = accessManager.currentUserContext()
        val isRogue = TenantAccessValidator.isRogueVendorOrigin(domain.activeOrigins, currentUserContext)
        if (isRogue == true) {
            expirationJob?.cancel()
            expirationJob = null
            setTenantStateLocked(
                TenantContextState.Restricted.AccessDenied(
                    companyId = companyId,
                    companyName = domain.companyName,
                    canSwitchCompany = canSwitch,
                ),
                sessionRevision,
            )
            return
        } else if (isRogue == null) {
            expirationJob?.cancel()
            expirationJob = null
            setTenantStateLocked(
                TenantContextState.Loading(requestedCompanyId = companyId),
                sessionRevision,
            )
            val global = accessManager.globalState.value
            val isAlreadyLoading =
                global is GlobalAccessState.InitialLoading ||
                    (global is GlobalAccessState.Ready && global.isRefreshing)
            if (!isAlreadyLoading) {
                scope.launch {
                    accessManager.refreshGlobalContext(force = true)
                }
            }
            return
        }

        val isAuthorized =
            when {
                domain.companyStatus == CompanyStatus.RESTRICTED -> {
                    domain.accessStatus == AccessStatus.ACTIVE_SUPPORT_OVERRIDE &&
                        context.supportGrant != null &&
                        !context.isSupportGrantLocallyExpired &&
                        when (context.restriction?.type) {
                            RestrictionType.MAINTENANCE -> true
                            RestrictionType.BILLING_DEBT -> context.supportGrant.allowBillingOverride
                            else -> false
                        }
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
                    setTenantStateLocked(
                        TenantContextState.Restricted.NoPermissions(
                            companyId = companyId,
                            companyName = domain.companyName,
                            canSwitchCompany = canSwitch,
                        ),
                        sessionRevision,
                    )
                } else {
                    applyAuthorizedContextLocked(context, sessionRevision)
                }
            }

            domain.companyStatus == CompanyStatus.RESTRICTED ||
                domain.accessStatus == AccessStatus.RESTRICTED -> {
                expirationJob?.cancel()
                expirationJob = null
                setTenantStateLocked(
                    TenantContextState.Restricted.CompanyFrozen(
                        companyId = companyId,
                        companyName = domain.companyName,
                        restriction = domain.restriction,
                        canSwitchCompany = canSwitch,
                    ),
                    sessionRevision,
                )
            }

            else -> {
                expirationJob?.cancel()
                expirationJob = null
                setTenantStateLocked(
                    TenantContextState.Restricted.AccessDenied(
                        companyId = companyId,
                        companyName = domain.companyName,
                        canSwitchCompany = canSwitch,
                    ),
                    sessionRevision,
                )
            }
        }
    }

    private fun applyAuthorizedContextLocked(
        context: AuthorizedTenantContext,
        sessionRevision: Long = -1L,
    ) {
        val grant = context.supportGrant
        val remaining = grant?.remainingDurationNow() ?: Duration.ZERO
        val isGrantExpired =
            grant != null &&
                (grant.status != SupportGrantStatus.ACTIVE || remaining <= Duration.ZERO)

        when {
            isGrantExpired -> {
                expirationJob?.cancel()
                expirationJob = null
                applySupportGrantExpiredLocked(context, sessionRevision)
            }

            grant != null && grant.status == SupportGrantStatus.ACTIVE -> {
                setTenantStateLocked(
                    TenantContextState.Authorized(
                        context = context,
                        sessionRevision = sessionRevision,
                        isRefreshing = false,
                    ),
                    sessionRevision,
                )
                scheduleExpirationTimerLocked(context, remaining, sessionRevision)
            }

            else -> {
                expirationJob?.cancel()
                expirationJob = null
                setTenantStateLocked(
                    TenantContextState.Authorized(
                        context = context,
                        sessionRevision = sessionRevision,
                        isRefreshing = false,
                    ),
                    sessionRevision,
                )
            }
        }
    }

    private fun applySupportGrantExpiredLocked(
        context: AuthorizedTenantContext,
        sessionRevision: Long,
    ) {
        val expiredContext = context.withSupportGrantExpiredLocally()
        val userContext = accessManager.currentUserContext()
        val canSwitch = userContext?.hasMultipleCompanies ?: false
        val newState =
            when {
                context.companyStatus == CompanyStatus.RESTRICTED ->
                    TenantContextState.Restricted.CompanyFrozen(
                        companyId = context.companyId,
                        companyName = context.companyName,
                        restriction = context.restriction,
                        canSwitchCompany = canSwitch,
                    )

                expiredContext.effectiveTenantPermissions.isEmpty() ->
                    TenantContextState.Restricted.NoPermissions(
                        companyId = context.companyId,
                        companyName = context.companyName,
                        canSwitchCompany = canSwitch,
                    )

                else ->
                    TenantContextState.Authorized(
                        context = expiredContext,
                        sessionRevision = sessionRevision,
                        isRefreshing = false,
                    )
            }
        setTenantStateLocked(newState, sessionRevision)
    }

    private fun scheduleExpirationTimerLocked(
        context: AuthorizedTenantContext,
        remaining: Duration,
        sessionRevision: Long = -1L,
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
                        applySupportGrantExpiredLocked(current.context, sessionRevision)
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
