package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.domain.concurrent.TenantSingleFlight
import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.PermissionKey
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.access.domain.model.SupportGrantInfo
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.SupportMode
import studio.acmenra.control.core.access.domain.model.SupportPermission
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.model.TenantPermission
import studio.acmenra.control.core.access.domain.repository.AccessRepository
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.TokenPair
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class TenantContextCoordinatorTest {
    private class FakeTokenStorage : TokenStorage {
        private var storedTokens: TokenPair? = TokenPair("access_token", "refresh_token")

        override fun getTokens(): TokenPair? = storedTokens

        override fun saveTokens(tokens: TokenPair) {
            storedTokens = tokens
        }

        override fun clear() {
            storedTokens = null
        }

        override fun hasTokens(): Boolean = storedTokens != null
    }

    private class SettableTimeMark(
        var elapsed: Duration = Duration.ZERO,
    ) : TimeMark {
        override fun elapsedNow(): Duration = elapsed

        override fun plus(duration: Duration): TimeMark = SettableTimeMark(elapsed - duration)

        override fun minus(duration: Duration): TimeMark = SettableTimeMark(elapsed + duration)
    }

    private class SettableTimeSource(
        val mark: SettableTimeMark,
    ) : TimeSource {
        override fun markNow(): TimeMark = mark
    }

    private class FakeAccessRepository : AccessRepository {
        var handler: suspend (companyId: String) -> NetworkResult<CompanyAccessDomain> = { companyId ->
            NetworkResult.Success(
                CompanyAccessDomain(
                    companyId = companyId,
                    companyName = "Company $companyId",
                    serverTime = Instant.fromEpochMilliseconds(0),
                    revision = "rev_1",
                    companyStatus = CompanyStatus.ACTIVE,
                    accessStatus = AccessStatus.ACTIVE,
                    activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                    restriction = null,
                    tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                    supportPermissions = emptySet(),
                    permissionSources =
                        mapOf(
                            PermissionKey.Tenant(TenantPermission.EVENTS_READ) to setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                        ),
                    supportGrant = null,
                ),
            )
        }

        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> =
            NetworkResult.Success(
                GlobalUserContext(
                    userId = "user_100",
                    serverTime = Instant.fromEpochMilliseconds(0),
                    globalPermissions = emptySet(),
                    defaultCompanyId = "c1",
                    canBrowseAllCompanies = false,
                    memberCompanies =
                        listOf(
                            CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE),
                            CompanyBrief("c2", "Company 2", CompanyStatus.ACTIVE),
                        ),
                ),
            )

        override suspend fun getCompanyAccess(
            userId: String,
            companyId: String,
            forceRefresh: Boolean,
        ): NetworkResult<CompanyAccessDomain> = handler(companyId)

        override suspend fun getCachedCompanyAccess(
            userId: String,
            companyId: String,
        ): CompanyAccessDomain? = null

        override suspend fun clearCache() {}
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val storage = FakeTokenStorage()
    private val sessionManager = SessionManager(storage)
    private val repository = FakeAccessRepository()
    private val timeMark = SettableTimeMark()
    private val timeSource = SettableTimeSource(timeMark)
    private val mapper = AccessMapper(timeSource)
    private val accessManager = AccessManager(repository, sessionManager, scope = testScope)
    private val singleFlight = TenantSingleFlight(testScope)

    private val coordinator =
        TenantContextCoordinator(
            accessRepository = repository,
            accessManager = accessManager,
            sessionManager = sessionManager,
            mapper = mapper,
            scope = testScope,
            singleFlight = singleFlight,
        )

    @BeforeTest
    fun setUp() =
        runTest {
            timeMark.elapsed = Duration.ZERO
        }

    @Test
    fun selectTenantSetsLoadingThenAuthorized() =
        runTest {
            coordinator.selectTenant("c1")

            val state = coordinator.tenantState.value
            assertTrue(state is TenantContextState.Authorized)
            assertEquals("c1", state.context.companyId)
            assertEquals(setOf(TenantPermission.EVENTS_READ), state.context.effectiveTenantPermissions)
        }

    @Test
    fun generationProtectionPreventsSlowResponseFromOverwritingNewerTenant() =
        runTest {
            val slowDeferred = CompletableDeferred<Unit>()

            repository.handler = { companyId ->
                if (companyId == "slow_company") {
                    slowDeferred.await()
                }
                NetworkResult.Success(
                    CompanyAccessDomain(
                        companyId = companyId,
                        companyName = "Company $companyId",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        revision = "rev_1",
                        companyStatus = CompanyStatus.ACTIVE,
                        accessStatus = AccessStatus.ACTIVE,
                        activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                        restriction = null,
                        tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                        supportPermissions = emptySet(),
                        permissionSources = emptyMap(),
                        supportGrant = null,
                    ),
                )
            }

            // Start selecting slow_company
            val jobSlow = testScope.launch { coordinator.selectTenant("slow_company") }

            // Immediately switch to fast_company
            coordinator.selectTenant("fast_company")
            val currentFast = coordinator.tenantState.value
            assertTrue(currentFast is TenantContextState.Authorized)
            assertEquals("fast_company", currentFast.context.companyId)

            // Now slow company finishes
            slowDeferred.complete(Unit)
            jobSlow.join()

            // State MUST remain fast_company because slow_company's generation is obsolete!
            val latest = coordinator.tenantState.value
            assertTrue(latest is TenantContextState.Authorized)
            assertEquals("fast_company", latest.context.companyId)
        }

    @Test
    fun reactiveTimerExpiresGrantAndStripsSupportPermissions() =
        runTest {
            val grant =
                SupportGrantInfo(
                    grantId = "g1",
                    status = SupportGrantStatus.ACTIVE,
                    mode = SupportMode.ENGINEERING_SUPPORT,
                    ticketReference = "T-1",
                    allowBillingOverride = false,
                    grantedByUserId = "u",
                    grantedAt = null,
                    expiresAt = null,
                    remainingTtlSec = 10L,
                    revokedAt = null,
                    fetchedAtMark = timeMark,
                )

            repository.handler = { companyId ->
                NetworkResult.Success(
                    CompanyAccessDomain(
                        companyId = companyId,
                        companyName = "Grant Co",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        revision = "r_grant",
                        companyStatus = CompanyStatus.ACTIVE,
                        accessStatus = AccessStatus.ACTIVE,
                        activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP, AccessOrigin.SUPPORT_GRANT),
                        restriction = null,
                        tenantPermissions = setOf(TenantPermission.EVENTS_READ, TenantPermission.AUDIT_READ),
                        supportPermissions = setOf(SupportPermission.DEVICE_RESTART),
                        permissionSources =
                            mapOf(
                                PermissionKey.Tenant(TenantPermission.EVENTS_READ) to
                                    setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                                PermissionKey.Tenant(TenantPermission.AUDIT_READ) to setOf(AccessOrigin.SUPPORT_GRANT),
                                PermissionKey.Support(SupportPermission.DEVICE_RESTART) to
                                    setOf(AccessOrigin.SUPPORT_GRANT),
                            ),
                        supportGrant = grant,
                    ),
                )
            }

            coordinator.selectTenant("c_grant")
            val authorized = coordinator.tenantState.value as TenantContextState.Authorized
            assertEquals(setOf(SupportPermission.DEVICE_RESTART), authorized.context.effectiveSupportPermissions)
            assertTrue(TenantPermission.AUDIT_READ in authorized.context.effectiveTenantPermissions)

            // Advance time and timeMark past the 10-second TTL
            timeMark.elapsed = 11.seconds
            testScope.advanceTimeBy(11_000)

            val expired = coordinator.tenantState.value as TenantContextState.Authorized
            assertTrue(expired.context.isSupportGrantLocallyExpired)
            assertTrue(expired.context.effectiveSupportPermissions.isEmpty())
            // Non-grant permission remains, support-grant-sourced permission is stripped
            assertTrue(TenantPermission.EVENTS_READ in expired.context.effectiveTenantPermissions)
            assertFalse(TenantPermission.AUDIT_READ in expired.context.effectiveTenantPermissions)
        }

    @Test
    fun restrictedCompanyWithActiveSupportOverrideAdmitsUserThenFreezesOnExpiration() =
        runTest {
            val grant =
                SupportGrantInfo(
                    grantId = "g_override",
                    status = SupportGrantStatus.ACTIVE,
                    mode = SupportMode.ENGINEERING_SUPPORT,
                    ticketReference = "OVERRIDE-1",
                    allowBillingOverride = false,
                    grantedByUserId = "u",
                    grantedAt = null,
                    expiresAt = null,
                    remainingTtlSec = 5L,
                    revokedAt = null,
                    fetchedAtMark = timeMark,
                )

            repository.handler = { companyId ->
                NetworkResult.Success(
                    CompanyAccessDomain(
                        companyId = companyId,
                        companyName = "Frozen Co",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        revision = "r_froz",
                        companyStatus = CompanyStatus.RESTRICTED,
                        accessStatus = AccessStatus.ACTIVE_SUPPORT_OVERRIDE,
                        activeOrigins = setOf(AccessOrigin.SUPPORT_GRANT),
                        restriction = null,
                        tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                        supportPermissions = setOf(SupportPermission.DEVICE_DIAGNOSTICS),
                        permissionSources =
                            mapOf(
                                PermissionKey.Tenant(TenantPermission.EVENTS_READ) to setOf(AccessOrigin.SUPPORT_GRANT),
                            ),
                        supportGrant = grant,
                    ),
                )
            }

            coordinator.selectTenant("c_frozen")
            assertTrue(coordinator.tenantState.value is TenantContextState.Authorized)

            // Now expire grant
            timeMark.elapsed = 6.seconds
            testScope.advanceTimeBy(6_000)

            // When override grant expires on a restricted company, it cannot stay authorized and refreshes to frozen
            val postExpireState = coordinator.tenantState.value
            assertTrue(postExpireState is TenantContextState.Restricted.CompanyFrozen)
            assertEquals("c_frozen", postExpireState.companyId)
        }

    @Test
    fun invalidationListenerTriggersRefresh() =
        runTest {
            var refreshCalled = false
            repository.handler = { companyId ->
                refreshCalled = true
                NetworkResult.Success(
                    CompanyAccessDomain(
                        companyId = companyId,
                        companyName = "Refreshed Co",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        revision = "rev_ref",
                        companyStatus = CompanyStatus.ACTIVE,
                        accessStatus = AccessStatus.ACTIVE,
                        activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                        restriction = null,
                        tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                        supportPermissions = emptySet(),
                        permissionSources = emptyMap(),
                        supportGrant = null,
                    ),
                )
            }

            coordinator.selectTenant("c1")
            refreshCalled = false

            val invalidationCoordinator = AccessInvalidationCoordinator(accessManager, coordinator, scope = testScope)
            invalidationCoordinator.onAccessInvalidatedAsync("PERMISSION_DENIED", "c1").join()

            assertTrue(refreshCalled)
        }

    @Test
    fun resetCancelsJobsAndSetsNotSelected() =
        runTest {
            coordinator.selectTenant("c1")
            assertTrue(coordinator.tenantState.value is TenantContextState.Authorized)

            coordinator.reset()
            assertEquals(TenantContextState.NotSelected, coordinator.tenantState.value)
        }

    @Test
    fun serverError403WithCompanyRestrictedAndRestrictionBodySetsCompanyFrozen() =
        runTest {
            repository.handler = { companyId ->
                NetworkResult.Failure(
                    NetworkError.Http(
                        statusCode = 403,
                        errorBody =
                            """{"error":"COMPANY_RESTRICTED","company_id":"$companyId",""" +
                                """"restriction":{"type":"ADMIN_BLOCKED","reason":"Security audit pending"}}""",
                    ),
                )
            }

            coordinator.selectTenant("c_blocked")
            val state = coordinator.tenantState.value
            assertTrue(state is TenantContextState.Restricted.CompanyFrozen)
            assertEquals("c_blocked", state.companyId)
            val restriction = state.restriction
            assertNotNull(restriction)
            assertEquals(RestrictionType.ADMIN_BLOCKED, restriction.type)
            assertEquals("Security audit pending", restriction.reason)
        }

    @Test
    fun restrictedAccessStatusWithNonEmptyPermissionsRejectsAuthorization() =
        runTest {
            repository.handler = { companyId ->
                NetworkResult.Success(
                    CompanyAccessDomain(
                        companyId = companyId,
                        companyName = "Restricted Access Co",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        revision = "rev_1",
                        companyStatus = CompanyStatus.ACTIVE,
                        accessStatus = AccessStatus.RESTRICTED,
                        activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                        restriction = null,
                        tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                        supportPermissions = emptySet(),
                        permissionSources = emptyMap(),
                        supportGrant = null,
                    ),
                )
            }

            coordinator.selectTenant("c_restricted_access")
            val state = coordinator.tenantState.value
            assertTrue(state is TenantContextState.Restricted.CompanyFrozen)
            assertEquals("c_restricted_access", state.companyId)
            assertFalse(coordinator.tenantState.value is TenantContextState.Authorized)
        }

    @Test
    fun unknownCompanyStatusWithNonEmptyPermissionsFailsClosedToAccessDenied() =
        runTest {
            repository.handler = { companyId ->
                NetworkResult.Success(
                    CompanyAccessDomain(
                        companyId = companyId,
                        companyName = "Unknown Status Co",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        revision = "rev_1",
                        companyStatus = CompanyStatus.UNKNOWN,
                        accessStatus = AccessStatus.ACTIVE,
                        activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                        restriction = null,
                        tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                        supportPermissions = emptySet(),
                        permissionSources = emptyMap(),
                        supportGrant = null,
                    ),
                )
            }

            coordinator.selectTenant("c_unknown_company")
            val state = coordinator.tenantState.value
            assertTrue(state is TenantContextState.Restricted.AccessDenied)
            assertEquals("c_unknown_company", state.companyId)
        }

    @Test
    fun unknownAccessStatusWithNonEmptyPermissionsFailsClosedToAccessDenied() =
        runTest {
            repository.handler = { companyId ->
                NetworkResult.Success(
                    CompanyAccessDomain(
                        companyId = companyId,
                        companyName = "Unknown Access Co",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        revision = "rev_1",
                        companyStatus = CompanyStatus.ACTIVE,
                        accessStatus = AccessStatus.UNKNOWN,
                        activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                        restriction = null,
                        tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                        supportPermissions = emptySet(),
                        permissionSources = emptyMap(),
                        supportGrant = null,
                    ),
                )
            }

            coordinator.selectTenant("c_unknown_access")
            val state = coordinator.tenantState.value
            assertTrue(state is TenantContextState.Restricted.AccessDenied)
            assertEquals("c_unknown_access", state.companyId)
        }

    @Test
    fun staleNormalRefreshDoesNotOverwriteNewerForceRefresh() =
        runTest {
            val slowRefreshBarrier = CompletableDeferred<Unit>()
            val slowRefreshEntered = CompletableDeferred<Unit>()
            var slowRefreshEnabled = false

            // Next calls to repository:
            // 1. Initial selection: normal fast response
            // 2. Slow normal refresh (slowRefreshEnabled=true, force=false): pauses at slowRefreshBarrier
            // 3. Fast force refresh (force=true): returns restricted company
            val customRepo =
                object : AccessRepository {
                    override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> =
                        repository.getGlobalContext()

                    override suspend fun getCompanyAccess(
                        userId: String,
                        companyId: String,
                        forceRefresh: Boolean,
                    ): NetworkResult<CompanyAccessDomain> =
                        if (slowRefreshEnabled && !forceRefresh) {
                            slowRefreshEntered.complete(Unit)
                            slowRefreshBarrier.await()
                            NetworkResult.Success(
                                CompanyAccessDomain(
                                    companyId = companyId,
                                    companyName = "Old Co",
                                    serverTime = Instant.fromEpochMilliseconds(0),
                                    revision = "rev_old",
                                    companyStatus = CompanyStatus.ACTIVE,
                                    accessStatus = AccessStatus.ACTIVE,
                                    activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                                    restriction = null,
                                    tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                                    supportPermissions = emptySet(),
                                    permissionSources = emptyMap(),
                                    supportGrant = null,
                                ),
                            )
                        } else if (forceRefresh) {
                            NetworkResult.Success(
                                CompanyAccessDomain(
                                    companyId = companyId,
                                    companyName = "Frozen Co",
                                    serverTime = Instant.fromEpochMilliseconds(0),
                                    revision = "rev_frozen",
                                    companyStatus = CompanyStatus.RESTRICTED,
                                    accessStatus = AccessStatus.RESTRICTED,
                                    activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                                    restriction = null,
                                    tenantPermissions = emptySet(),
                                    supportPermissions = emptySet(),
                                    permissionSources = emptyMap(),
                                    supportGrant = null,
                                ),
                            )
                        } else {
                            NetworkResult.Success(
                                CompanyAccessDomain(
                                    companyId = companyId,
                                    companyName = "Initial Co",
                                    serverTime = Instant.fromEpochMilliseconds(0),
                                    revision = "rev_init",
                                    companyStatus = CompanyStatus.ACTIVE,
                                    accessStatus = AccessStatus.ACTIVE,
                                    activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                                    restriction = null,
                                    tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                                    supportPermissions = emptySet(),
                                    permissionSources = emptyMap(),
                                    supportGrant = null,
                                ),
                            )
                        }

                    override suspend fun getCachedCompanyAccess(
                        userId: String,
                        companyId: String,
                    ): CompanyAccessDomain? = null

                    override suspend fun clearCache() {}
                }

            val testCoordinator =
                TenantContextCoordinator(
                    accessRepository = customRepo,
                    accessManager = accessManager,
                    sessionManager = sessionManager,
                    mapper = mapper,
                )

            try {
                // Select tenant first to be in Authorized
                testCoordinator.selectTenant("c1")
                assertTrue(testCoordinator.tenantState.value is TenantContextState.Authorized)

                // Now enable slow refresh and start slow normal refresh
                slowRefreshEnabled = true
                val slowJob = launch { testCoordinator.refreshCurrentTenant(force = false) }
                slowRefreshEntered.await()

                // Fast force refresh runs and completes
                testCoordinator.refreshCurrentTenant(force = true)
                assertTrue(testCoordinator.tenantState.value is TenantContextState.Restricted.CompanyFrozen)

                // Complete the slow refresh
                slowRefreshBarrier.complete(Unit)
                slowJob.join()

                // State MUST remain CompanyFrozen (stale normal refresh result was discarded by generation check)
                assertTrue(testCoordinator.tenantState.value is TenantContextState.Restricted.CompanyFrozen)
            } finally {
                testCoordinator.reset()
            }
        }

    @Test
    fun companySelectionSequenceABAPreventsStaleFirstARequestFromOverwritingNewA() =
        runTest {
            val enteredA1 = CompletableDeferred<Unit>()
            val barrierA1 = CompletableDeferred<Unit>()
            var compACallCount = 0

            repository.handler = { companyId ->
                when (companyId) {
                    "comp_A" -> {
                        compACallCount++
                        val currentCall = compACallCount
                        if (currentCall == 1) {
                            enteredA1.complete(Unit)
                            barrierA1.await()
                        }
                        NetworkResult.Success(
                            CompanyAccessDomain(
                                companyId = "comp_A",
                                companyName = "Company A v$currentCall",
                                serverTime = Instant.fromEpochMilliseconds(0),
                                revision = "rev_A$currentCall",
                                companyStatus = CompanyStatus.ACTIVE,
                                accessStatus = AccessStatus.ACTIVE,
                                activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                                restriction = null,
                                tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                                supportPermissions = emptySet(),
                                permissionSources = emptyMap(),
                                supportGrant = null,
                            ),
                        )
                    }

                    else -> {
                        NetworkResult.Success(
                            CompanyAccessDomain(
                                companyId = companyId,
                                companyName = "Company $companyId",
                                serverTime = Instant.fromEpochMilliseconds(0),
                                revision = "rev_$companyId",
                                companyStatus = CompanyStatus.ACTIVE,
                                accessStatus = AccessStatus.ACTIVE,
                                activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                                restriction = null,
                                tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                                supportPermissions = emptySet(),
                                permissionSources = emptyMap(),
                                supportGrant = null,
                            ),
                        )
                    }
                }
            }

            // Step 1: Start selection of A (A1), which suspends on barrierA1
            val jobA1 = testScope.launch { coordinator.selectTenant("comp_A") }
            enteredA1.await()

            // Step 2: Switch to B while A1 is still in flight (cancels A1's activeSelectionJob)
            coordinator.selectTenant("comp_B")
            val stateB = coordinator.tenantState.value
            assertTrue(stateB is TenantContextState.Authorized)
            assertEquals("comp_B", stateB.context.companyId)

            // Step 3: Switch back to A (A2 in background so we unblock A1 barrier)
            val jobA2 = testScope.launch { coordinator.selectTenant("comp_A") }

            // Step 4: Release the first slow A1 request
            barrierA1.complete(Unit)
            jobA1.join()
            jobA2.join()

            // Step 5: State MUST be Authorized for comp_A, and A1 generation (1) was invalidated by A2 generation (2)
            val finalState = coordinator.tenantState.value
            assertTrue(finalState is TenantContextState.Authorized)
            assertEquals("comp_A", finalState.context.companyId)
        }

    @Test
    fun logoutDuringParallelRefreshOfDifferentCompaniesDiscardsAllResponses() =
        runTest {
            val barrierA = CompletableDeferred<Unit>()
            val barrierB = CompletableDeferred<Unit>()
            val enteredA = CompletableDeferred<Unit>()
            val enteredB = CompletableDeferred<Unit>()
            var enableBarriers = false

            repository.handler = { companyId ->
                if (enableBarriers) {
                    when (companyId) {
                        "c1" -> {
                            enteredA.complete(Unit)
                            barrierA.await()
                        }

                        "c2" -> {
                            enteredB.complete(Unit)
                            barrierB.await()
                        }
                    }
                }
                NetworkResult.Success(
                    CompanyAccessDomain(
                        companyId = companyId,
                        companyName = "Company $companyId",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        revision = "rev_1",
                        companyStatus = CompanyStatus.ACTIVE,
                        accessStatus = AccessStatus.ACTIVE,
                        activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                        restriction = null,
                        tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                        supportPermissions = emptySet(),
                        permissionSources = emptyMap(),
                        supportGrant = null,
                    ),
                )
            }

            // Select c1 first so it's authorized
            coordinator.selectTenant("c1")
            assertTrue(coordinator.tenantState.value is TenantContextState.Authorized)

            // Enable barriers for the background refreshes
            enableBarriers = true

            // Start parallel refreshes for c1 and c2
            val jobA = testScope.launch { coordinator.refreshTenant("c1", force = true) }
            val jobB = testScope.launch { coordinator.refreshTenant("c2", force = true) }

            enteredA.await()
            enteredB.await()

            // User logs out while both refreshes are in flight
            sessionManager.logout()

            // State immediately transitioned to NotSelected
            assertEquals(TenantContextState.NotSelected, coordinator.tenantState.value)

            // Release network responses
            barrierA.complete(Unit)
            barrierB.complete(Unit)
            jobA.join()
            jobB.join()

            // Both in-flight responses must be discarded due to globalTenantEpoch bump and unauthenticated session
            assertEquals(TenantContextState.NotSelected, coordinator.tenantState.value)
        }

    @Test
    fun selectTenantDuringInFlightRefreshOfSameCompanySupercedesStaleRefresh() =
        runTest {
            val refreshBarrier = CompletableDeferred<Unit>()
            val refreshEntered = CompletableDeferred<Unit>()
            var refreshCount = 0

            repository.handler = { companyId ->
                refreshCount++
                val count = refreshCount
                if (count == 2) {
                    refreshEntered.complete(Unit)
                    refreshBarrier.await()
                }
                NetworkResult.Success(
                    CompanyAccessDomain(
                        companyId = companyId,
                        companyName = "Company c1 v$count",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        revision = "rev_$count",
                        companyStatus = CompanyStatus.ACTIVE,
                        accessStatus = AccessStatus.ACTIVE,
                        activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                        restriction = null,
                        tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                        supportPermissions = emptySet(),
                        permissionSources = emptyMap(),
                        supportGrant = null,
                    ),
                )
            }

            // 1. Initial selection (refreshCount = 1)
            coordinator.selectTenant("c1")
            val initial = coordinator.tenantState.value
            assertTrue(initial is TenantContextState.Authorized)
            assertEquals("Company c1 v1", initial.context.companyName)

            // 2. Start refreshTenant (refreshCount = 2, generation for c1 becomes 2)
            val refreshJob = testScope.launch { coordinator.refreshCurrentTenant(force = true) }
            refreshEntered.await()
            assertTrue((coordinator.tenantState.value as TenantContextState.Authorized).isRefreshing)

            // 3. User explicitly re-selects c1 while refresh is in flight (refreshCount = 3, generation becomes 3)
            coordinator.selectTenant("c1")
            val reselected = coordinator.tenantState.value
            assertTrue(reselected is TenantContextState.Authorized)
            assertEquals("Company c1 v3", reselected.context.companyName)
            assertFalse(reselected.isRefreshing)

            // 4. Stale refresh finishes (generation 2)
            refreshBarrier.complete(Unit)
            refreshJob.join()

            // 5. State must remain v3, not reverted to stale refresh v2
            val finalState = coordinator.tenantState.value
            assertTrue(finalState is TenantContextState.Authorized)
            assertEquals("Company c1 v3", finalState.context.companyName)
            assertFalse(finalState.isRefreshing)
        }

    @Test
    fun cancellingActiveSelectionAllowsSubsequentSelectionWithoutHanging() =
        runTest {
            val barrier = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()

            repository.handler = { companyId ->
                if (companyId == "slow") {
                    entered.complete(Unit)
                    barrier.await()
                }
                NetworkResult.Success(
                    CompanyAccessDomain(
                        companyId = companyId,
                        companyName = "Company $companyId",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        revision = "rev_1",
                        companyStatus = CompanyStatus.ACTIVE,
                        accessStatus = AccessStatus.ACTIVE,
                        activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                        restriction = null,
                        tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                        supportPermissions = emptySet(),
                        permissionSources = emptyMap(),
                        supportGrant = null,
                    ),
                )
            }

            val slowJob = testScope.launch { coordinator.selectTenant("slow") }
            entered.await()
            slowJob.cancel()
            barrier.complete(Unit)
            slowJob.join()

            // Selecting another tenant works immediately
            coordinator.selectTenant("c1")
            val state = coordinator.tenantState.value
            assertTrue(state is TenantContextState.Authorized)
            assertEquals("c1", state.context.companyId)
        }

    @Test
    fun refreshOfInactiveCompanyDoesNotAlterActiveCompanyState() =
        runTest {
            repository.handler = { companyId ->
                when (companyId) {
                    "c1" -> {
                        NetworkResult.Success(
                            CompanyAccessDomain(
                                companyId = "c1",
                                companyName = "Active Company 1",
                                serverTime = Instant.fromEpochMilliseconds(0),
                                revision = "rev_1",
                                companyStatus = CompanyStatus.ACTIVE,
                                accessStatus = AccessStatus.ACTIVE,
                                activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                                restriction = null,
                                tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                                supportPermissions = emptySet(),
                                permissionSources = emptyMap(),
                                supportGrant = null,
                            ),
                        )
                    }

                    "c2" -> {
                        NetworkResult.Success(
                            CompanyAccessDomain(
                                companyId = "c2",
                                companyName = "Inactive Frozen Company 2",
                                serverTime = Instant.fromEpochMilliseconds(0),
                                revision = "rev_1",
                                companyStatus = CompanyStatus.RESTRICTED,
                                accessStatus = AccessStatus.RESTRICTED,
                                activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                                restriction = null,
                                tenantPermissions = emptySet(),
                                supportPermissions = emptySet(),
                                permissionSources = emptyMap(),
                                supportGrant = null,
                            ),
                        )
                    }

                    else -> error("Unexpected company: $companyId")
                }
            }

            // Select active company c1
            coordinator.selectTenant("c1")
            val activeState = coordinator.tenantState.value
            assertTrue(activeState is TenantContextState.Authorized)
            assertEquals("c1", activeState.context.companyId)
            assertEquals("Active Company 1", activeState.context.companyName)

            // Trigger background refresh for inactive company c2
            coordinator.refreshTenant(targetCompanyId = "c2", force = true)

            // Active tenant state must remain completely unchanged
            val stateAfterBRefresh = coordinator.tenantState.value
            assertTrue(stateAfterBRefresh is TenantContextState.Authorized)
            assertEquals("c1", stateAfterBRefresh.context.companyId)
            assertEquals("Active Company 1", stateAfterBRefresh.context.companyName)
            assertFalse(stateAfterBRefresh.isRefreshing)
        }
}
