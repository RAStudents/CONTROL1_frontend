package studio.acmenra.control.navigation

import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.PermissionKey
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.model.TenantPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class AppScaffoldUiStateTest {
    private fun createAuthorizedContext(
        companyId: String = "c1",
        companyName: String = "Acme Corp",
        tenantPermissions: Set<TenantPermission> = setOf(TenantPermission.EVENTS_READ),
    ): AuthorizedTenantContext =
        AuthorizedTenantContext(
            userId = "u1",
            companyId = companyId,
            companyName = companyName,
            tenantRevision = 1L,
            accessRevision = "rev1",
            companyStatus = CompanyStatus.ACTIVE,
            accessStatus = AccessStatus.ACTIVE,
            activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
            rawTenantPermissions = tenantPermissions,
            rawSupportPermissions = emptySet(),
            permissionSources =
                tenantPermissions.associate {
                    PermissionKey.Tenant(it) to setOf(AccessOrigin.DIRECT_MEMBERSHIP)
                },
            supportGrant = null,
        )

    private fun createGlobalContext(
        defaultCompanyId: String? = "c1",
        canBrowseAllCompanies: Boolean = false,
        globalPermissions: Set<GlobalPermission> = emptySet(),
    ): GlobalUserContext =
        GlobalUserContext(
            userId = "u1",
            serverTime = Instant.fromEpochMilliseconds(0),
            globalPermissions = globalPermissions,
            defaultCompanyId = defaultCompanyId,
            canBrowseAllCompanies = canBrowseAllCompanies,
            memberCompanies =
                listOf(
                    CompanyBrief("c1", "Acme Corp", CompanyStatus.ACTIVE),
                    CompanyBrief("c2", "Beta Ltd", CompanyStatus.ACTIVE),
                ),
        )

    @Test
    fun testAppScaffoldUiStateForAuthorizedTenantUser() {
        val globalContext = createGlobalContext()
        val authorized = createAuthorizedContext()
        val tenantState = TenantContextState.Authorized(authorized)

        val uiState =
            calculateAppScaffoldUiState(
                globalContext = globalContext,
                tenantState = tenantState,
                destination = null,
                items = AppNavigationItem.DEFAULT_ITEMS,
            )

        assertEquals("c1", uiState.currentCompanyId)
        assertEquals("Acme Corp", uiState.currentCompanyName)
        assertEquals("c1", uiState.effectiveCompanyId)
        assertTrue(uiState.canSwitch)
        assertFalse(uiState.isRestricted)
        assertFalse(uiState.isOnVendorDashboard)
        assertEquals(1, uiState.visibleItems.size)
    }

    @Test
    fun testAppScaffoldUiStateForRestrictedTenantState() {
        val globalContext = createGlobalContext()
        val tenantState =
            TenantContextState.Restricted.NoPermissions(
                companyId = "c1",
                companyName = "Acme Corp",
                canSwitchCompany = true,
            )

        val uiState =
            calculateAppScaffoldUiState(
                globalContext = globalContext,
                tenantState = tenantState,
                destination = null,
                items = AppNavigationItem.DEFAULT_ITEMS,
            )

        assertTrue(uiState.isRestricted)
        assertEquals("c1", uiState.selectedCompanyId)
        assertEquals("Acme Corp", uiState.currentCompanyName)
        assertTrue(uiState.visibleItems.isEmpty())
    }

    @Test
    fun testAppScaffoldUiStateForVendorDashboard() {
        val globalContext =
            createGlobalContext(
                canBrowseAllCompanies = true,
                globalPermissions = setOf(GlobalPermission.COMPANIES_MANAGE),
            )
        val tenantState = TenantContextState.NotSelected

        val uiState =
            calculateAppScaffoldUiState(
                globalContext = globalContext,
                tenantState = tenantState,
                destination = null,
                items = AppNavigationItem.DEFAULT_ITEMS,
            )

        assertTrue(uiState.canSwitch)
        assertEquals(1, uiState.visibleItems.size)
    }
}
