package studio.acmenra.control.core.access.domain

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.access.data.datasource.KtorAccessRemoteDataSource
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.access.domain.model.TenantContextState
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

        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> {
            globalRefreshCount++
            return NetworkResult.Failure(
                NetworkError.Http(
                    statusCode = 403,
                    errorBody =
                        """{"error":"ACCOUNT_FROZEN",""" +
                            """"restriction":{"type":"BILLING_DEBT","reason":"Unpaid invoice"}}""",
                ),
            )
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

    private val coordinator = AccessInvalidationCoordinator(accessManager, tenantCoordinator)
    private val dispatcher = AccessInvalidationDispatcher().apply { addListener(coordinator) }

    @Test
    fun accountFrozenErrorCodeTriggersGlobalContextRefresh() =
        runTest {
            val initialCount = repository.globalRefreshCount
            coordinator.onAccessInvalidated(errorCode = "ACCOUNT_FROZEN", companyId = null)

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

            coordinator.onAccessInvalidated(errorCode = "PERMISSION_DENIED", companyId = "c1")

            assertEquals(initialCount + 1, repository.companyRefreshCount)
            assertEquals("c1", repository.lastRequestedCompanyId)
            assertTrue(repository.lastForceRefresh)
        }

    @Test
    fun companyRestrictedErrorCodeTriggersTenantRefresh() =
        runTest {
            tenantCoordinator.selectTenant("c1")
            val initialCount = repository.companyRefreshCount

            coordinator.onAccessInvalidated(errorCode = "COMPANY_RESTRICTED", companyId = "c1")

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

            coordinator.onAccessInvalidated(errorCode = "SUPPORT_GRANT_REVOKED", companyId = "c1")

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
    fun http403FromKtorAccessRemoteDataSourceAutomaticallyTriggersAccessInvalidation() =
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
                    invalidationListener = customDispatcher,
                )

            val result = remoteDataSource.getCompanyAccess("comp_99", ifNoneMatch = null)
            assertTrue(result is NetworkResult.Failure)
            assertEquals("SUPPORT_GRANT_EXPIRED", invalidatedCode)
            assertEquals("comp_99", invalidatedCompany)
        }
}
