package studio.acmenra.control.core.access.domain

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.access.data.datasource.KtorAccessRemoteDataSource
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.model.TenantPermission
import studio.acmenra.control.core.access.domain.repository.AccessRepository
import studio.acmenra.control.core.network.auth.AccessInvalidationDispatcher
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.client.HttpClientFactory
import studio.acmenra.control.core.network.client.safeRequest
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.TokenPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AccessInvalidationTest {
    private class FakeTokenStorage : TokenStorage {
        private var storedTokens: TokenPair? = TokenPair("access", "refresh")

        override fun getTokens(): TokenPair? = storedTokens

        override fun saveTokens(tokens: TokenPair) {
            storedTokens = tokens
        }

        override fun clear() {
            storedTokens = null
        }

        override fun hasTokens(): Boolean = storedTokens != null
    }

    private class RecordingAccessRepository : AccessRepository {
        var globalRefreshCount = 0
        var companyRefreshCount = 0
        var lastRequestedCompanyId: String? = null
        var lastForceRefresh: Boolean = false
        var globalResult: NetworkResult<GlobalUserContext> =
            NetworkResult.Success(
                GlobalUserContext(
                    userId = "user_1",
                    serverTime = Instant.fromEpochMilliseconds(0),
                    globalPermissions = emptySet(),
                    defaultCompanyId = "c1",
                    canBrowseAllCompanies = true,
                    memberCompanies =
                        listOf(
                            CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE),
                            CompanyBrief("comp_A", "Company A", CompanyStatus.ACTIVE),
                            CompanyBrief("comp_B", "Company B", CompanyStatus.ACTIVE),
                        ),
                ),
            )

        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> {
            globalRefreshCount++
            return globalResult
        }

        override suspend fun getCompanyAccess(
            userId: String,
            companyId: String,
            forceRefresh: Boolean,
        ): NetworkResult<CompanyAccessDomain> {
            companyRefreshCount++
            lastRequestedCompanyId = companyId
            lastForceRefresh = forceRefresh
            return NetworkResult.Failure(
                NetworkError.Http(
                    statusCode = 403,
                    errorBody =
                        """{"error":"COMPANY_RESTRICTED","company_id":"$companyId",""" +
                            """"restriction":{"type":"MAINTENANCE","reason":"Routine maintenance"}}""",
                ),
            )
        }

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
    private val repository = RecordingAccessRepository()
    private val mapper = AccessMapper()

    private val accessManager = AccessManager(repository, sessionManager, mapper, scope = testScope)
    private val tenantCoordinator =
        TenantContextCoordinator(
            accessRepository = repository,
            accessManager = accessManager,
            sessionManager = sessionManager,
            mapper = mapper,
            scope = testScope,
        )

    private val coordinator = AccessInvalidationCoordinator(accessManager, tenantCoordinator, scope = testScope)
    private val dispatcher = AccessInvalidationDispatcher().apply { addListener(coordinator) }

    @Test
    fun accountFrozenErrorCodeTriggersGlobalContextRefresh() =
        runTest {
            repository.globalResult =
                NetworkResult.Failure(
                    NetworkError.Http(
                        statusCode = 403,
                        errorBody =
                            """{"error":"ACCOUNT_FROZEN",""" +
                                """"restriction":{"type":"BILLING_DEBT","reason":"Unpaid invoice"}}""",
                    ),
                )
            val initialCount = repository.globalRefreshCount
            coordinator.onAccessInvalidatedAsync(errorCode = "ACCOUNT_FROZEN", companyId = null).join()

            assertEquals(initialCount + 1, repository.globalRefreshCount)
            val state = accessManager.globalState.value
            assertTrue(state is GlobalAccessState.Restricted.AccountFrozen)
            val restriction = state.restriction
            assertNotNull(restriction)
            assertEquals(RestrictionType.BILLING_DEBT, restriction.type)
            assertEquals("Unpaid invoice", restriction.reason)
        }

    @Test
    fun permissionDeniedErrorCodeTriggersTenantRefresh() =
        runTest {
            tenantCoordinator.selectTenant("c1")
            val initialCount = repository.companyRefreshCount

            coordinator.onAccessInvalidatedAsync(errorCode = "PERMISSION_DENIED", companyId = "c1").join()

            assertEquals(initialCount + 1, repository.companyRefreshCount)
            assertEquals("c1", repository.lastRequestedCompanyId)
            assertTrue(repository.lastForceRefresh)
        }

    @Test
    fun companyRestrictedErrorCodeTriggersTenantRefresh() =
        runTest {
            tenantCoordinator.selectTenant("c1")
            val initialCount = repository.companyRefreshCount

            coordinator.onAccessInvalidatedAsync(errorCode = "COMPANY_RESTRICTED", companyId = "c1").join()

            assertEquals(initialCount + 1, repository.companyRefreshCount)
            val state = tenantCoordinator.tenantState.value
            assertTrue(state is TenantContextState.Restricted.CompanyFrozen)
            val restriction = state.restriction
            assertNotNull(restriction)
            assertEquals(RestrictionType.MAINTENANCE, restriction.type)
            assertEquals("Routine maintenance", restriction.reason)
        }

    @Test
    fun supportGrantRevokedErrorCodeTriggersTenantRefresh() =
        runTest {
            tenantCoordinator.selectTenant("c1")
            val initialCount = repository.companyRefreshCount

            coordinator.onAccessInvalidatedAsync(errorCode = "SUPPORT_GRANT_REVOKED", companyId = "c1").join()

            assertEquals(initialCount + 1, repository.companyRefreshCount)
            assertTrue(repository.lastForceRefresh)
        }

    @Test
    fun http403FromHttpClientAutomaticallyTriggersAccessInvalidation() =
        runTest {
            var invalidatedCode: String? = null
            var invalidatedCompany: String? = null

            val customDispatcher =
                AccessInvalidationDispatcher().apply {
                    addListener { code, company ->
                        invalidatedCode = code
                        invalidatedCompany = company
                    }
                }

            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"error":"COMPANY_RESTRICTED","company_id":"comp_42"}""",
                        status = HttpStatusCode.Forbidden,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    accessInvalidationListener = customDispatcher,
                    apiBaseUrl = "https://api.example.com",
                    allowInsecureTransport = true,
                    engine = engine,
                )

            val result =
                client.safeRequest<String> {
                    get("https://api.example.com/api/v1/companies/comp_42/events")
                }

            assertTrue(result is NetworkResult.Failure)
            assertEquals("COMPANY_RESTRICTED", invalidatedCode)
            assertEquals("comp_42", invalidatedCompany)
        }

    @Test
    fun http403FromKtorAccessRemoteDataSourceDoesNotTriggerInvalidationToPreventCycle() =
        runTest {
            var invalidatedCount = 0

            val customDispatcher =
                AccessInvalidationDispatcher().apply {
                    addListener { _, _ ->
                        invalidatedCount++
                    }
                }

            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"error":"SUPPORT_GRANT_EXPIRED","company_id":"comp_99"}""",
                        status = HttpStatusCode.Forbidden,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    accessInvalidationListener = customDispatcher,
                    apiBaseUrl = "https://api.example.com",
                    allowInsecureTransport = true,
                    engine = engine,
                )

            val remoteDataSource =
                KtorAccessRemoteDataSource(
                    httpClient = client,
                )

            val result = remoteDataSource.getCompanyAccess("comp_99", ifNoneMatch = null)
            assertTrue(result is NetworkResult.Failure)
            assertEquals(403, (result.error as NetworkError.Http).statusCode)
            // Access datasource must NOT trigger invalidation listener to prevent infinite recursion
            assertEquals(0, invalidatedCount)
        }

    @Test
    fun repeatGlobalInvalidationDuringSingleRequestTriggersRepeatRevalidation() =
        runTest {
            val localJob = Job()
            val localScope = CoroutineScope(localJob + testDispatcher)
            try {
                var testStarted = false
                var globalCallCount = 0
                val storage = FakeTokenStorage()
                val localSessionManager = SessionManager(storage)
                var coordinatorInstance: AccessInvalidationCoordinator? = null

                val recursiveRepository =
                    object : AccessRepository {
                        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> {
                            if (!testStarted) {
                                return NetworkResult.Success(
                                    GlobalUserContext(
                                        userId = "user_init",
                                        serverTime = Instant.fromEpochMilliseconds(0),
                                        globalPermissions = emptySet(),
                                        defaultCompanyId = "c1",
                                        canBrowseAllCompanies = false,
                                        memberCompanies = listOf(CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE)),
                                    ),
                                )
                            }
                            globalCallCount++
                            if (globalCallCount == 1) {
                                // Simulate second invalidation arriving while first is in flight
                                coordinatorInstance?.onAccessInvalidated("ACCOUNT_FROZEN", null)
                            }
                            return NetworkResult.Failure(
                                NetworkError.Http(
                                    statusCode = 403,
                                    errorBody = """{"error":"ACCOUNT_FROZEN"}""",
                                ),
                            )
                        }

                        override suspend fun getCompanyAccess(
                            userId: String,
                            companyId: String,
                            forceRefresh: Boolean,
                        ): NetworkResult<CompanyAccessDomain> = throw UnsupportedOperationException()

                        override suspend fun getCachedCompanyAccess(
                            userId: String,
                            companyId: String,
                        ): CompanyAccessDomain? = null

                        override suspend fun clearCache() {}
                    }

                val mgr =
                    AccessManager(
                        accessRepository = recursiveRepository,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val tenantCoord =
                    TenantContextCoordinator(
                        accessRepository = recursiveRepository,
                        accessManager = mgr,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val coordinator =
                    AccessInvalidationCoordinator(
                        accessManager = mgr,
                        tenantCoordinator = tenantCoord,
                        scope = localScope,
                    )
                coordinatorInstance = coordinator
                testStarted = true

                coordinator.onAccessInvalidatedAsync("ACCOUNT_FROZEN", null).join()

                // Second invalidation was preserved in pending and executed as repeat revalidation
                assertEquals(2, globalCallCount)
                assertTrue(mgr.globalState.value is GlobalAccessState.Restricted.AccountFrozen)
            } finally {
                localJob.cancel()
            }
        }

    @Test
    fun repeatAccountFrozenDuringInFlightGlobalRefreshTriggersSequentialRevalidation() =
        runTest {
            val localJob = Job()
            val localScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + localJob)
            val storage = FakeTokenStorage()
            val localSessionManager = SessionManager(storage)
            try {
                var testStarted = false
                var activeGlobalRuns = 0
                var maxConcurrentGlobalRuns = 0
                var globalCallCount = 0
                val barrier = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()

                val testRepo =
                    object : AccessRepository {
                        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> {
                            if (!testStarted) {
                                return NetworkResult.Success(
                                    GlobalUserContext(
                                        userId = "user_init",
                                        serverTime = Instant.fromEpochMilliseconds(0),
                                        globalPermissions = emptySet(),
                                        defaultCompanyId = "c1",
                                        canBrowseAllCompanies = false,
                                        memberCompanies = listOf(CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE)),
                                    ),
                                )
                            }
                            globalCallCount++
                            activeGlobalRuns++
                            maxConcurrentGlobalRuns = maxOf(maxConcurrentGlobalRuns, activeGlobalRuns)

                            if (globalCallCount == 1) {
                                entered.complete(Unit)
                                barrier.await()
                            }

                            activeGlobalRuns--
                            return NetworkResult.Failure(
                                NetworkError.Http(
                                    statusCode = 403,
                                    errorBody =
                                        """{"error":"ACCOUNT_FROZEN",""" +
                                            """"restriction":{"type":"BILLING_DEBT","reason":"Unpaid balance"}}""",
                                ),
                            )
                        }

                        override suspend fun getCompanyAccess(
                            userId: String,
                            companyId: String,
                            forceRefresh: Boolean,
                        ): NetworkResult<CompanyAccessDomain> = throw UnsupportedOperationException()

                        override suspend fun getCachedCompanyAccess(
                            userId: String,
                            companyId: String,
                        ): CompanyAccessDomain? = null

                        override suspend fun clearCache() {}
                    }

                val mgr =
                    AccessManager(
                        accessRepository = testRepo,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val tenantCoord =
                    TenantContextCoordinator(
                        accessRepository = testRepo,
                        accessManager = mgr,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val coordinator =
                    AccessInvalidationCoordinator(
                        accessManager = mgr,
                        tenantCoordinator = tenantCoord,
                        scope = localScope,
                    )
                testStarted = true

                // 1. First ACCOUNT_FROZEN invalidation starts and enters getGlobalContext (call 1)
                val job1 = coordinator.onAccessInvalidatedAsync("ACCOUNT_FROZEN", null)
                entered.await()
                assertEquals(1, globalCallCount)
                assertEquals(1, activeGlobalRuns)

                // 2. While first is in-flight, second ACCOUNT_FROZEN invalidation arrives
                val job2 = coordinator.onAccessInvalidatedAsync("ACCOUNT_FROZEN", null)

                // Must NOT run concurrently
                assertEquals(1, activeGlobalRuns)
                assertEquals(1, maxConcurrentGlobalRuns)

                // 3. Release barrier for first call
                barrier.complete(Unit)
                job1.join()
                job2.join()

                // Second invalidation was preserved in pending and executed sequentially
                assertEquals(2, globalCallCount)
                assertEquals(0, activeGlobalRuns)
                assertEquals(1, maxConcurrentGlobalRuns)

                val state = mgr.globalState.value
                assertTrue(state is GlobalAccessState.Restricted.AccountFrozen)
                assertEquals("Unpaid balance", state.restriction?.reason)
            } finally {
                localJob.cancel()
            }
        }

    @Test
    fun twoIndependentInvalidationsForDifferentCompaniesAreBothProcessed() =
        runTest {
            val localJob = Job()
            val localScope = CoroutineScope(localJob + testDispatcher)
            try {
                val barrierCompA = CompletableDeferred<Unit>()
                val compAEntered = CompletableDeferred<Unit>()
                val refreshedCompanies = mutableListOf<String>()
                var compAFrozen = false

                val testRepo =
                    object : AccessRepository {
                        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> =
                            repository.getGlobalContext()

                        override suspend fun getCompanyAccess(
                            userId: String,
                            companyId: String,
                            forceRefresh: Boolean,
                        ): NetworkResult<CompanyAccessDomain> {
                            if (companyId == "comp_A" && compAFrozen) {
                                compAEntered.complete(Unit)
                                barrierCompA.await()
                            }
                            refreshedCompanies.add(companyId)
                            val (companyStatus, accessStatus, perms) =
                                if (companyId == "comp_A" && compAFrozen) {
                                    Triple(CompanyStatus.RESTRICTED, AccessStatus.RESTRICTED, emptySet())
                                } else {
                                    Triple(
                                        CompanyStatus.ACTIVE,
                                        AccessStatus.ACTIVE,
                                        setOf(TenantPermission.EVENTS_READ),
                                    )
                                }

                            return NetworkResult.Success(
                                CompanyAccessDomain(
                                    companyId = companyId,
                                    companyName = "Company $companyId",
                                    serverTime = Instant.fromEpochMilliseconds(0),
                                    revision = "rev_1",
                                    companyStatus = companyStatus,
                                    accessStatus = accessStatus,
                                    activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                                    restriction = null,
                                    tenantPermissions = perms,
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

                val localSessionManager = SessionManager(FakeTokenStorage())

                val mgr =
                    AccessManager(
                        accessRepository = testRepo,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val tenantCoord =
                    TenantContextCoordinator(
                        accessRepository = testRepo,
                        accessManager = mgr,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val coordinator =
                    AccessInvalidationCoordinator(
                        accessManager = mgr,
                        tenantCoordinator = tenantCoord,
                        scope = localScope,
                    )

                // Select Company A initially so it is the active authorized tenant
                tenantCoord.selectTenant("comp_A")
                val initialActive = tenantCoord.tenantState.value
                assertTrue(initialActive is TenantContextState.Authorized)
                assertEquals("comp_A", initialActive.context.companyId)

                // Mark Company A as frozen for the upcoming invalidation
                compAFrozen = true

                // Start invalidation for Company A (will pause at barrierCompA)
                val jobA = coordinator.onAccessInvalidatedAsync("COMPANY_RESTRICTED", "comp_A")
                compAEntered.await()

                // Invalidation for Company B arrives while Company A is in flight
                coordinator.onAccessInvalidatedAsync("COMPANY_RESTRICTED", "comp_B").join()

                // Company B should not be dropped and completes
                assertTrue("comp_B" in refreshedCompanies)

                // Complete Company A
                barrierCompA.complete(Unit)
                jobA.join()

                // Both independent invalidations were processed!
                assertTrue("comp_A" in refreshedCompanies)
                assertTrue("comp_B" in refreshedCompanies)

                // Verify Company A's active state was updated to CompanyFrozen
                val finalState = tenantCoord.tenantState.value
                assertTrue(finalState is TenantContextState.Restricted.CompanyFrozen)
                assertEquals("comp_A", finalState.companyId)
            } finally {
                localJob.cancel()
            }
        }

    @Test
    fun invalidationForOtherTenantDoesNotCorruptActiveTenant() =
        runTest {
            val localJob = Job()
            val localScope = CoroutineScope(localJob + testDispatcher)
            try {
                val refreshedCompanies = mutableListOf<String>()

                val testRepo =
                    object : AccessRepository {
                        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> =
                            repository.getGlobalContext()

                        override suspend fun getCompanyAccess(
                            userId: String,
                            companyId: String,
                            forceRefresh: Boolean,
                        ): NetworkResult<CompanyAccessDomain> {
                            refreshedCompanies.add(companyId)
                            val isRestricted = companyId == "comp_B"
                            return NetworkResult.Success(
                                CompanyAccessDomain(
                                    companyId = companyId,
                                    companyName = "Company $companyId",
                                    serverTime = Instant.fromEpochMilliseconds(0),
                                    revision = "rev_1",
                                    companyStatus =
                                        if (isRestricted) CompanyStatus.RESTRICTED else CompanyStatus.ACTIVE,
                                    accessStatus =
                                        if (isRestricted) AccessStatus.RESTRICTED else AccessStatus.ACTIVE,
                                    activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                                    restriction = null,
                                    tenantPermissions =
                                        if (isRestricted) {
                                            emptySet()
                                        } else {
                                            setOf(TenantPermission.EVENTS_READ)
                                        },
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

                val localSessionManager = SessionManager(FakeTokenStorage())

                val mgr =
                    AccessManager(
                        accessRepository = testRepo,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val tenantCoord =
                    TenantContextCoordinator(
                        accessRepository = testRepo,
                        accessManager = mgr,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val coordinator =
                    AccessInvalidationCoordinator(
                        accessManager = mgr,
                        tenantCoordinator = tenantCoord,
                        scope = localScope,
                    )

                // 1. Select comp_A
                tenantCoord.selectTenant("comp_A")
                val activeA = tenantCoord.tenantState.value
                assertTrue(activeA is TenantContextState.Authorized)
                assertEquals("comp_A", activeA.context.companyId)

                // 2. Receive 403 invalidation for comp_B
                coordinator.onAccessInvalidatedAsync("COMPANY_RESTRICTED", "comp_B").join()

                // comp_B was refreshed
                assertTrue("comp_B" in refreshedCompanies)

                // Active tenant state must remain comp_A and Authorized
                val stateAfter = tenantCoord.tenantState.value
                assertTrue(stateAfter is TenantContextState.Authorized)
                assertEquals("comp_A", stateAfter.context.companyId)
            } finally {
                localJob.cancel()
            }
        }

    @Test
    fun twoInvalidationsForSameCompanyDuringSingleRequestTriggersRepeatRevalidation() =
        runTest {
            val localJob = Job()
            val localScope = CoroutineScope(localJob + testDispatcher)
            try {
                val barrierCompA = CompletableDeferred<Unit>()
                val compAEntered = CompletableDeferred<Unit>()
                var compARefreshCount = 0

                val testRepo =
                    object : AccessRepository {
                        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> =
                            repository.getGlobalContext()

                        override suspend fun getCompanyAccess(
                            userId: String,
                            companyId: String,
                            forceRefresh: Boolean,
                        ): NetworkResult<CompanyAccessDomain> {
                            if (companyId == "comp_A") {
                                compARefreshCount++
                                if (compARefreshCount == 2) {
                                    compAEntered.complete(Unit)
                                    barrierCompA.await()
                                }
                            }
                            return NetworkResult.Success(
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

                        override suspend fun getCachedCompanyAccess(
                            userId: String,
                            companyId: String,
                        ): CompanyAccessDomain? = null

                        override suspend fun clearCache() {}
                    }

                val localSessionManager = SessionManager(FakeTokenStorage())

                val mgr =
                    AccessManager(
                        accessRepository = testRepo,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val tenantCoord =
                    TenantContextCoordinator(
                        accessRepository = testRepo,
                        accessManager = mgr,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val coordinator =
                    AccessInvalidationCoordinator(
                        accessManager = mgr,
                        tenantCoordinator = tenantCoord,
                        scope = localScope,
                    )

                // Select comp_A first
                tenantCoord.selectTenant("comp_A")
                assertEquals(1, compARefreshCount)

                // First invalidation arrives for comp_A with PERMISSION_DENIED
                val job1 = coordinator.onAccessInvalidatedAsync("PERMISSION_DENIED", "comp_A")
                compAEntered.await()

                // Second invalidation arrives simultaneously for comp_A with SUPPORT_GRANT_EXPIRED
                val job2 = coordinator.onAccessInvalidatedAsync("SUPPORT_GRANT_EXPIRED", "comp_A")

                // Release barrier
                barrierCompA.complete(Unit)
                job1.join()
                job2.join()

                // Exactly 3 network calls were made in total: 1 for selectTenant, 1 for the first invalidation,
                // and 1 for the pending retry invalidation that arrived while the first was in flight.
                assertEquals(3, compARefreshCount)

                // A subsequent invalidation after completion executes successfully
                coordinator.onAccessInvalidatedAsync("PERMISSION_DENIED", "comp_A").join()
                assertEquals(4, compARefreshCount)
            } finally {
                localJob.cancel()
            }
        }

    @Test
    fun ownerTokenPreventsStaleFinallyFromUnlockingNewOperationInAccessInvalidationCoordinator() =
        runTest {
            val localJob = Job()
            val localScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + localJob)
            val localSessionManager = SessionManager(FakeTokenStorage())
            try {
                var activeRuns = 0
                var maxConcurrentRuns = 0
                val barrier2 = CompletableDeferred<Unit>()
                val entered2 = CompletableDeferred<Unit>()

                var refreshCount = 0

                val testRepo =
                    object : AccessRepository {
                        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> =
                            NetworkResult.Success(
                                GlobalUserContext(
                                    userId = "u1",
                                    serverTime = Instant.fromEpochMilliseconds(0),
                                    globalPermissions = emptySet(),
                                    defaultCompanyId = "comp_A",
                                    canBrowseAllCompanies = false,
                                    memberCompanies = listOf(CompanyBrief("comp_A", "Company A", CompanyStatus.ACTIVE)),
                                ),
                            )

                        override suspend fun getCompanyAccess(
                            userId: String,
                            companyId: String,
                            forceRefresh: Boolean,
                        ): NetworkResult<CompanyAccessDomain> {
                            refreshCount++
                            activeRuns++
                            maxConcurrentRuns = maxOf(maxConcurrentRuns, activeRuns)

                            if (refreshCount == 2) {
                                entered2.complete(Unit)
                                barrier2.await()
                            }

                            activeRuns--
                            return NetworkResult.Success(
                                CompanyAccessDomain(
                                    companyId = companyId,
                                    companyName = "Company $companyId",
                                    serverTime = Instant.fromEpochMilliseconds(0),
                                    revision = "rev_$refreshCount",
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

                val mgr =
                    AccessManager(
                        accessRepository = testRepo,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val tenantCoord =
                    TenantContextCoordinator(
                        accessRepository = testRepo,
                        accessManager = mgr,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val coord =
                    AccessInvalidationCoordinator(
                        accessManager = mgr,
                        tenantCoordinator = tenantCoord,
                        scope = localScope,
                    )

                // 1. Initial selection
                tenantCoord.selectTenant("comp_A")
                assertEquals(1, refreshCount)

                // 2. Launch op 2: will pause inside getCompanyAccess at refreshCount == 2
                val job2 = coord.onAccessInvalidatedAsync("PERMISSION_DENIED", "comp_A")
                entered2.await()
                assertEquals(2, refreshCount)
                assertEquals(1, activeRuns)

                // 3. While op 2 is suspended holding the lock, launch op 3
                val job3 = coord.onAccessInvalidatedAsync("SUPPORT_GRANT_EXPIRED", "comp_A")

                // Since op 2 is in flight, op 3 must NOT run concurrently
                assertEquals(1, activeRuns)
                assertEquals(1, maxConcurrentRuns)

                // Release barrier for op 2
                barrier2.complete(Unit)
                job2.join()
                job3.join()

                // Op 3 was picked up as pending and executed sequentially after op 2
                assertEquals(3, refreshCount)
                assertEquals(0, activeRuns)
                assertEquals(1, maxConcurrentRuns)
            } finally {
                localJob.cancel()
            }
        }

    @Test
    fun staleFinallyDoesNotDeleteLockBelongingToNewOperation() =
        runTest {
            val localJob = Job()
            val localScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + localJob)
            val localSessionManager = SessionManager(FakeTokenStorage())
            try {
                var activeRuns = 0
                var maxConcurrentRuns = 0
                val op2Barrier = CompletableDeferred<Unit>()
                val op2Entered = CompletableDeferred<Unit>()
                val op1BeforeFinallyReached = CompletableDeferred<Unit>()
                val op1AllowFinally = CompletableDeferred<Unit>()

                var refreshCount = 0

                val testRepo =
                    object : AccessRepository {
                        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> =
                            NetworkResult.Success(
                                GlobalUserContext(
                                    userId = "u1",
                                    serverTime = Instant.fromEpochMilliseconds(0),
                                    globalPermissions = emptySet(),
                                    defaultCompanyId = "comp_A",
                                    canBrowseAllCompanies = false,
                                    memberCompanies = listOf(CompanyBrief("comp_A", "Company A", CompanyStatus.ACTIVE)),
                                ),
                            )

                        override suspend fun getCompanyAccess(
                            userId: String,
                            companyId: String,
                            forceRefresh: Boolean,
                        ): NetworkResult<CompanyAccessDomain> {
                            refreshCount++
                            activeRuns++
                            maxConcurrentRuns = maxOf(maxConcurrentRuns, activeRuns)

                            if (refreshCount == 3) {
                                op2Entered.complete(Unit)
                                op2Barrier.await()
                            }

                            activeRuns--
                            return NetworkResult.Success(
                                CompanyAccessDomain(
                                    companyId = companyId,
                                    companyName = "Company $companyId",
                                    serverTime = Instant.fromEpochMilliseconds(0),
                                    revision = "rev_$refreshCount",
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

                val mgr =
                    AccessManager(
                        accessRepository = testRepo,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val tenantCoord =
                    TenantContextCoordinator(
                        accessRepository = testRepo,
                        accessManager = mgr,
                        sessionManager = localSessionManager,
                        mapper = mapper,
                        scope = localScope,
                    )

                val coord =
                    AccessInvalidationCoordinator(
                        accessManager = mgr,
                        tenantCoordinator = tenantCoord,
                        scope = localScope,
                    )

                // 1. Initial selection (refreshCount == 1)
                tenantCoord.selectTenant("comp_A")
                assertEquals(1, refreshCount)

                // Configure hook to intercept Op 1 right between while loop exit and finally block
                coord.onBeforeFinallyHook = {
                    op1BeforeFinallyReached.complete(Unit)
                    op1AllowFinally.await()
                }

                // 2. Launch Op 1: completes while loop (refreshCount == 2), hits hook before finally
                val job1 = coord.onAccessInvalidatedAsync("PERMISSION_DENIED", "comp_A")
                op1BeforeFinallyReached.await()
                assertEquals(2, refreshCount)

                // Clear hook so subsequent operations don't block
                coord.onBeforeFinallyHook = null

                // 3. Launch Op 2 while Op 1 is paused before finally.
                // Op 2 sees lock was released by loop, acquires it with ownerToken2, and starts getCompanyAccess (refreshCount == 3)
                val job2 = coord.onAccessInvalidatedAsync("PERMISSION_DENIED", "comp_A")
                op2Entered.await()
                assertEquals(3, refreshCount)
                assertEquals(1, activeRuns)

                // 4. Now allow Op 1 to proceed into its finally block!
                // Op 1's finally must NOT delete the lock belonging to Op 2!
                op1AllowFinally.complete(Unit)
                job1.join()

                // 5. While Op 2 is still suspended in getCompanyAccess, launch Op 3
                val job3 = coord.onAccessInvalidatedAsync("SUPPORT_GRANT_EXPIRED", "comp_A")

                // If Op 1's finally had cleared the lock, Op 3 would run concurrently (activeRuns would be 2).
                // With ownerToken protection, Op 3 is queued as pending, so activeRuns remains 1.
                assertEquals(1, activeRuns)
                assertEquals(1, maxConcurrentRuns)

                // 6. Release Op 2 barrier
                op2Barrier.complete(Unit)
                job2.join()
                job3.join()

                // Op 3 executed sequentially after Op 2
                assertEquals(4, refreshCount)
                assertEquals(0, activeRuns)
                assertEquals(1, maxConcurrentRuns)
            } finally {
                localJob.cancel()
            }
        }
}
