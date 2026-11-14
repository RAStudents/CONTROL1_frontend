package studio.acmenra.control.core.access.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.domain.AccessManager
import studio.acmenra.control.core.access.domain.TenantContextCoordinator
import studio.acmenra.control.core.access.domain.concurrent.TenantSingleFlight
import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.SupportPermission
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.model.TenantPermission
import studio.acmenra.control.core.access.domain.repository.AccessRepository
import studio.acmenra.control.core.access.domain.usecase.impl.CheckPermissionUseCaseImpl
import studio.acmenra.control.core.access.domain.usecase.impl.ObserveGlobalAccessUseCaseImpl
import studio.acmenra.control.core.access.domain.usecase.impl.ObserveTenantContextUseCaseImpl
import studio.acmenra.control.core.access.domain.usecase.impl.RefreshTenantUseCaseImpl
import studio.acmenra.control.core.access.domain.usecase.impl.SelectTenantUseCaseImpl
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.TokenPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AccessUseCasesTest {
    private class FakeStorage : TokenStorage {
        private var storedTokens: TokenPair? = TokenPair("a", "r")

        override fun getTokens(): TokenPair? = storedTokens

        override fun saveTokens(tokens: TokenPair) {
            storedTokens = tokens
        }

        override fun clear() {
            storedTokens = null
        }

        override fun hasTokens(): Boolean = storedTokens != null
    }

    private class FakeRepo : AccessRepository {
        var refreshCount = 0

        override suspend fun getGlobalContext(): NetworkResult<GlobalUserContext> =
            NetworkResult.Success(
                GlobalUserContext(
                    userId = "u1",
                    serverTime = Instant.fromEpochMilliseconds(0),
                    globalPermissions = emptySet(),
                    defaultCompanyId = "c1",
                    canBrowseAllCompanies = false,
                    memberCompanies = listOf(CompanyBrief("c1", "C1", CompanyStatus.ACTIVE)),
                ),
            )

        override suspend fun getCompanyAccess(
            userId: String,
            companyId: String,
            forceRefresh: Boolean,
        ): NetworkResult<CompanyAccessDomain> {
            if (forceRefresh) refreshCount++
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
                    supportPermissions = setOf(SupportPermission.DEVICE_DIAGNOSTICS),
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

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val sessionManager = SessionManager(FakeStorage())
    private val repo = FakeRepo()
    private val mapper = AccessMapper()
    private val accessManager = AccessManager(repo, sessionManager, scope = testScope)
    private val singleFlight = TenantSingleFlight(testScope)
    private val coordinator =
        TenantContextCoordinator(
            accessRepository = repo,
            accessManager = accessManager,
            sessionManager = sessionManager,
            mapper = mapper,
            scope = testScope,
            singleFlight = singleFlight,
        )

    private val observeGlobalAccessUseCase = ObserveGlobalAccessUseCaseImpl(accessManager)
    private val observeTenantContextUseCase = ObserveTenantContextUseCaseImpl(coordinator)
    private val selectTenantUseCase = SelectTenantUseCaseImpl(coordinator)
    private val refreshTenantUseCase = RefreshTenantUseCaseImpl(coordinator)
    private val checkPermissionUseCase = CheckPermissionUseCaseImpl(coordinator)

    @Test
    fun observeGlobalAccessProvidesState() =
        runTest {
            assertEquals(accessManager.globalState.value, observeGlobalAccessUseCase().value)
        }

    @Test
    fun selectAndRefreshTenantFlow() =
        runTest {
            selectTenantUseCase("c1")
            assertTrue(observeTenantContextUseCase().value is TenantContextState.Authorized)

            refreshTenantUseCase(force = true)
            assertEquals(1, repo.refreshCount)
        }

    @Test
    fun checkPermissionEvaluatesCorrectly() =
        runTest {
            assertFalse(checkPermissionUseCase(RoutePermissionRequirement.Tenant(TenantPermission.EVENTS_READ)))

            selectTenantUseCase("c1")

            assertTrue(checkPermissionUseCase(RoutePermissionRequirement.Tenant(TenantPermission.EVENTS_READ)))
            assertFalse(checkPermissionUseCase(RoutePermissionRequirement.Tenant(TenantPermission.DEVICES_DELETE)))
            assertTrue(checkPermissionUseCase(RoutePermissionRequirement.Support(SupportPermission.DEVICE_DIAGNOSTICS)))
        }
}
