package studio.acmenra.control.core.access.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.access.data.datasource.CachedCompanyAccess
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
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

        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> = globalResult

        override suspend fun getCompanyAccess(
            userId: String,
            companyId: String,
            forceRefresh: Boolean,
        ): NetworkResult<CompanyAccessDomain> = throw UnsupportedOperationException()

        override suspend fun getCachedCompanyAccess(
            userId: String,
            companyId: String,
        ): CachedCompanyAccess? = null

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
    fun logoutResetsToIdleAndClearsCache() =
        runTest {
            sessionManager.saveSession(TokenPair("acc", "ref"))
            assertNotNull(accessManager.currentUserContext())

            sessionManager.logout()
            assertEquals(GlobalAccessState.Idle, accessManager.globalState.value)
            assertEquals(1, repository.clearedCount)
        }
}
