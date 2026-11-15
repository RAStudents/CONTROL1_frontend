package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.access.domain.repository.AccessRepository
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.TokenPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AccessManagerTest {
    private class FakeTokenStorage : TokenStorage {
        private var storedTokens: TokenPair? = null

        override fun getTokens(): TokenPair? = storedTokens

        override fun saveTokens(tokens: TokenPair) {
            storedTokens = tokens
        }

        override fun clear() {
            storedTokens = null
        }

        override fun hasTokens(): Boolean = storedTokens != null
    }

    private class FakeAccessRepository : AccessRepository {
        var globalResult: NetworkResult<GlobalUserContext> =
            NetworkResult.Success(
                GlobalUserContext(
                    userId = "user_1",
                    serverTime = Instant.fromEpochMilliseconds(0),
                    globalPermissions = emptySet(),
                    defaultCompanyId = "c1",
                    canBrowseAllCompanies = false,
                    memberCompanies = listOf(CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE)),
                ),
            )

        var clearedCount = 0
        var globalCallCount = 0

        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> {
            globalCallCount++
            return globalResult
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

        override suspend fun clearCache() {
            clearedCount++
        }
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val storage = FakeTokenStorage()
    private val sessionManager = SessionManager(storage)
    private val repository = FakeAccessRepository()

    private val accessManager = AccessManager(repository, sessionManager, scope = testScope)

    @Test
    fun initialStateIsIdle() =
        runTest {
            assertEquals(GlobalAccessState.Idle, accessManager.globalState.value)
        }

    @Test
    fun loginAutomaticallyRefreshesGlobalContext() =
        runTest {
            sessionManager.saveSession(TokenPair("acc", "ref"))
            val state = accessManager.globalState.first { it is GlobalAccessState.Ready }
            assertTrue(state is GlobalAccessState.Ready)
            assertEquals("user_1", state.context.userId)
            assertEquals("user_1", accessManager.currentUserContext()?.userId)
        }

    @Test
    fun noCompaniesAvailableRestrictionDetected() =
        runTest {
            sessionManager.saveSession(TokenPair("acc", "ref"))
            repository.globalResult =
                NetworkResult.Success(
                    GlobalUserContext(
                        userId = "user_no_company",
                        serverTime = Instant.fromEpochMilliseconds(0),
                        globalPermissions = emptySet(),
                        defaultCompanyId = null,
                        canBrowseAllCompanies = false,
                        memberCompanies = emptyList(),
                    ),
                )

            accessManager.refreshGlobalContext(force = true)
            assertEquals(GlobalAccessState.Restricted.NoCompaniesAvailable, accessManager.globalState.value)
        }

    @Test
    fun accountFrozenRestrictionDetected() =
        runTest {
            sessionManager.saveSession(TokenPair("acc", "ref"))
            repository.globalResult =
                NetworkResult.Failure(
                    NetworkError.Http(
                        statusCode = 403,
                        errorBody = """{"error":"ACCOUNT_FROZEN"}""",
                    ),
                )

            accessManager.refreshGlobalContext(force = true)
            assertTrue(accessManager.globalState.value is GlobalAccessState.Restricted.AccountFrozen)
        }

    @Test
    fun accountFrozenParsesRestrictionBody() =
        runTest {
            sessionManager.saveSession(TokenPair("acc", "ref"))
            repository.globalResult =
                NetworkResult.Failure(
                    NetworkError.Http(
                        statusCode = 403,
                        errorBody =
                            """{"error":"ACCOUNT_FROZEN",""" +
                                """"restriction":{"type":"TERMS_VIOLATION","reason":"Violated ToS"}}""",
                    ),
                )

            accessManager.refreshGlobalContext(force = true)
            val state = accessManager.globalState.value
            assertTrue(state is GlobalAccessState.Restricted.AccountFrozen)
            val restriction = state.restriction
            assertNotNull(restriction)
            assertEquals(RestrictionType.TERMS_VIOLATION, restriction.type)
            assertEquals("Violated ToS", restriction.reason)
        }

    @Test
    fun refreshWithoutForceDoesNotTriggerNetworkIfAlreadyReady() =
        runTest {
            sessionManager.saveSession(TokenPair("acc", "ref"))
            val state = accessManager.globalState.first { it is GlobalAccessState.Ready }
            assertTrue(state is GlobalAccessState.Ready)
            val callCountAfterLogin = repository.globalCallCount

            val result = accessManager.refreshGlobalContext(force = false)
            assertTrue(result is NetworkResult.Success)
            assertEquals(callCountAfterLogin, repository.globalCallCount)
        }

    @Test
    fun logoutResetsToIdleAndClearsCache() =
        runTest {
            sessionManager.saveSession(TokenPair("acc", "ref"))
            assertNotNull(accessManager.currentUserContext())

            sessionManager.logout()
            assertEquals(GlobalAccessState.Idle, accessManager.globalState.value)
            assertEquals(1, repository.clearedCount)
        }

    @Test
    fun concurrentRefreshGlobalContextDeduplicatesAndDoesNotDeadlock() =
        runTest {
            val barrier = CompletableDeferred<Unit>()
            var callCount = 0
            val customRepository =
                object : AccessRepository {
                    override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> {
                        callCount++
                        barrier.await()
                        return NetworkResult.Success(
                            GlobalUserContext(
                                userId = "user_concurrent",
                                serverTime = Instant.fromEpochMilliseconds(0),
                                globalPermissions = emptySet(),
                                defaultCompanyId = "c1",
                                canBrowseAllCompanies = false,
                                memberCompanies = listOf(CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE)),
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

            val customStorage = FakeTokenStorage()
            val customSessionManager = SessionManager(customStorage)
            customSessionManager.saveSession(TokenPair("acc", "ref"))
            val mgr =
                AccessManager(
                    accessRepository = customRepository,
                    sessionManager = customSessionManager,
                    mapper = AccessMapper(),
                    scope = testScope,
                )

            val job1 = async { mgr.refreshGlobalContext(force = true) }
            runCurrent()

            val job2 = async { mgr.refreshGlobalContext(force = false) }
            runCurrent()

            barrier.complete(Unit)

            val res1 = job1.await()
            val res2 = job2.await()

            assertTrue(res1 is NetworkResult.Success)
            assertTrue(res2 is NetworkResult.Success)
            assertEquals("user_concurrent", res1.data.userId)
            assertEquals("user_concurrent", res2.data.userId)
            assertEquals(1, callCount)
            assertTrue(mgr.globalState.value is GlobalAccessState.Ready)
        }

    @Test
    fun forceRefreshGlobalContextDoesNotCoalesceIntoStaleNonForceRefresh() =
        runTest {
            var testStarted = false
            val barrier1 = CompletableDeferred<Unit>()
            val barrier2 = CompletableDeferred<Unit>()
            var callCount = 0
            val customRepository =
                object : AccessRepository {
                    override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> {
                        if (!testStarted) {
                            return NetworkResult.Failure(
                                NetworkError.Http(
                                    statusCode = 403,
                                    errorBody = """{"error":"ACCOUNT_FROZEN"}""",
                                ),
                            )
                        }
                        callCount++
                        if (callCount == 1) {
                            barrier1.await()
                            return NetworkResult.Success(
                                GlobalUserContext(
                                    userId = "user_stale",
                                    serverTime = Instant.fromEpochMilliseconds(0),
                                    globalPermissions = emptySet(),
                                    defaultCompanyId = "c1",
                                    canBrowseAllCompanies = false,
                                    memberCompanies = listOf(CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE)),
                                ),
                            )
                        } else {
                            barrier2.await()
                            return NetworkResult.Failure(
                                NetworkError.Http(
                                    statusCode = 403,
                                    errorBody = """{"error":"ACCOUNT_FROZEN"}""",
                                ),
                            )
                        }
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

            val customStorage = FakeTokenStorage()
            val customSessionManager = SessionManager(customStorage)
            customSessionManager.saveSession(TokenPair("acc", "ref"))
            val mgr =
                AccessManager(
                    accessRepository = customRepository,
                    sessionManager = customSessionManager,
                    mapper = AccessMapper(),
                    scope = testScope,
                )
            runCurrent()
            assertTrue(mgr.globalState.value is GlobalAccessState.Restricted.AccountFrozen)
            testStarted = true

            // Start non-force refresh (call 1)
            val job1 = async { mgr.refreshGlobalContext(force = false) }
            runCurrent()
            assertEquals(1, callCount)

            // Force refresh arrives while non-force is in-flight (call 2)
            val job2 = async { mgr.refreshGlobalContext(force = true) }
            runCurrent()
            assertEquals(2, callCount)

            barrier1.complete(Unit)
            barrier2.complete(Unit)

            val res2 = job2.await()
            assertTrue(res2 is NetworkResult.Failure)
            assertTrue(mgr.globalState.value is GlobalAccessState.Restricted.AccountFrozen)
        }

    @Test
    fun immediateUserSwitchDoesNotReturnStaleUserContext() =
        runTest {
            val userAContext =
                GlobalUserContext(
                    userId = "user_A",
                    serverTime = Instant.fromEpochMilliseconds(0),
                    globalPermissions = emptySet(),
                    defaultCompanyId = "c1",
                    canBrowseAllCompanies = false,
                    memberCompanies = listOf(CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE)),
                )
            val userBContext =
                GlobalUserContext(
                    userId = "user_B",
                    serverTime = Instant.fromEpochMilliseconds(0),
                    globalPermissions = emptySet(),
                    defaultCompanyId = "c2",
                    canBrowseAllCompanies = false,
                    memberCompanies = listOf(CompanyBrief("c2", "Company 2", CompanyStatus.ACTIVE)),
                )

            repository.globalResult = NetworkResult.Success(userAContext)
            sessionManager.saveSession(TokenPair("accA", "refA"))

            val ctxA = accessManager.ensureGlobalContext()
            assertEquals("user_A", ctxA?.userId)
            assertEquals("user_A", accessManager.currentUserContext()?.userId)

            // Switch to User B in SessionManager
            repository.globalResult = NetworkResult.Success(userBContext)
            sessionManager.saveSession(TokenPair("accB", "refB"))

            // Immediate call to currentUserContext before background refresh finishes
            // must NOT return user_A!
            assertEquals(null, accessManager.currentUserContext())

            // Calling ensureGlobalContext fetches user_B
            val ctxB = accessManager.ensureGlobalContext()
            assertEquals("user_B", ctxB?.userId)
            assertEquals("user_B", accessManager.currentUserContext()?.userId)
        }

    @Test
    fun logoutDuringInFlightGlobalRefreshDoesNotRestoreAccess() =
        runTest {
            val customStorage = FakeTokenStorage()
            val customSessionManager = SessionManager(customStorage)
            customSessionManager.saveSession(TokenPair("acc", "ref"))

            val barrier = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()

            val delayedRepo =
                object : AccessRepository {
                    override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> {
                        entered.complete(Unit)
                        barrier.await()
                        return NetworkResult.Success(
                            GlobalUserContext(
                                userId = "user_stale",
                                serverTime = Instant.fromEpochMilliseconds(0),
                                globalPermissions = emptySet(),
                                defaultCompanyId = "c1",
                                canBrowseAllCompanies = false,
                                memberCompanies = listOf(CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE)),
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
                    accessRepository = delayedRepo,
                    sessionManager = customSessionManager,
                    mapper = AccessMapper(),
                    scope = testScope,
                )

            // When mgr is created while authenticated, init triggers global context refresh.
            // Wait until delayedRepo is entered and in-flight.
            entered.await()

            // User logs out while request is in-flight
            customSessionManager.logout()
            mgr.reset()
            assertEquals(GlobalAccessState.Idle, mgr.globalState.value)

            // In-flight network response finishes
            barrier.complete(Unit)
            runCurrent()

            // Stale response must NOT restore access or set globalState to Ready
            assertEquals(GlobalAccessState.Idle, mgr.globalState.value)
            assertEquals(null, mgr.currentUserContext())
        }

    @Test
    fun userSwitchFromAccountFrozenToAuthenticatedForcesRefresh() =
        runTest {
            val customStorage = FakeTokenStorage()
            val customSessionManager = SessionManager(customStorage)
            val mgr =
                AccessManager(
                    accessRepository = repository,
                    sessionManager = customSessionManager,
                    mapper = AccessMapper(),
                    scope = testScope,
                )

            val userBContext =
                GlobalUserContext(
                    userId = "user_B",
                    serverTime = Instant.fromEpochMilliseconds(0),
                    globalPermissions = emptySet(),
                    defaultCompanyId = "c2",
                    canBrowseAllCompanies = false,
                    memberCompanies = listOf(CompanyBrief("c2", "Company 2", CompanyStatus.ACTIVE)),
                )

            repository.globalResult =
                NetworkResult.Failure(
                    NetworkError.Http(
                        statusCode = 403,
                        errorBody = """{"error":"ACCOUNT_FROZEN"}""",
                    ),
                )
            customSessionManager.saveSession(TokenPair("accA", "refA"))
            runCurrent()
            assertTrue(mgr.globalState.value is GlobalAccessState.Restricted.AccountFrozen)

            // Switch to User B with valid access
            repository.globalResult = NetworkResult.Success(userBContext)
            customSessionManager.logout()
            customSessionManager.saveSession(TokenPair("accB", "refB"))
            runCurrent()

            val state = mgr.globalState.value
            assertTrue(state is GlobalAccessState.Ready)
            assertEquals("user_B", state.context.userId)
        }
}
