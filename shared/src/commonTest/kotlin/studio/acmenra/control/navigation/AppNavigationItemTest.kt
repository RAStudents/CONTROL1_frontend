package studio.acmenra.control.navigation

import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.PermissionKey
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.TenantPermission
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.ic_nav_events
import studio.acmenra.control.core.ui.generated.resources.nav_item_events
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class AppNavigationItemTest {
    private fun createAuthorizedContext(
        tenantPermissions: Set<TenantPermission> = setOf(TenantPermission.EVENTS_READ),
    ): AuthorizedTenantContext =
        AuthorizedTenantContext(
            userId = "u1",
            companyId = "c1",
            companyName = "Acme Corp",
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
        memberCompanies: List<CompanyBrief> = listOf(CompanyBrief("c1", "Acme", CompanyStatus.ACTIVE)),
    ): GlobalUserContext =
        GlobalUserContext(
            userId = "u1",
            serverTime = Instant.fromEpochMilliseconds(0),
            globalPermissions = globalPermissions,
            defaultCompanyId = defaultCompanyId,
            canBrowseAllCompanies = canBrowseAllCompanies,
            memberCompanies = memberCompanies,
        )

    @Test
    fun testDefaultItemsContainEvents() {
        assertEquals(1, AppNavigationItem.DEFAULT_ITEMS.size)
        assertEquals(AppNavigationItem.Events, AppNavigationItem.DEFAULT_ITEMS.first())
    }

    @Test
    fun testEventsItemProperties() {
        val item = AppNavigationItem.Events
        assertEquals(Res.string.nav_item_events, item.labelRes)
        assertEquals(Res.drawable.ic_nav_events, item.iconRes)
        assertEquals(
            RoutePermissionRequirement.Tenant(TenantPermission.EVENTS_READ),
            item.requiredPermission,
        )
    }

    @Test
    fun testIsSelectedWithNullDestination() {
        assertFalse(AppNavigationItem.Events.isSelected(null))
    }

    @Test
    fun testVisibleItemsWhenAuthorizedWithEventsRead() {
        val items = AppNavigationItem.DEFAULT_ITEMS
        val authorized = createAuthorizedContext(setOf(TenantPermission.EVENTS_READ))
        val visible = AppNavigationItem.filterVisibleNavigationItems(items, authorized)

        assertEquals(1, visible.size)
        assertEquals(AppNavigationItem.Events, visible.first())
    }

    @Test
    fun testVisibleItemsWhenAuthorizedWithoutEventsRead() {
        val items = AppNavigationItem.DEFAULT_ITEMS
        val authorized = createAuthorizedContext(emptySet())
        val visible = AppNavigationItem.filterVisibleNavigationItems(items, authorized)

        assertTrue(visible.isEmpty())
    }

    @Test
    fun testVisibleItemsWhenNotAuthorizedAndRegularUser() {
        val items = AppNavigationItem.DEFAULT_ITEMS
        val global = createGlobalContext()
        val visible =
            AppNavigationItem.filterVisibleNavigationItems(
                items = items,
                authorizedContext = null,
                globalContext = global,
            )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun testVisibleItemsWhenNotAuthorizedAndVendorAdmin() {
        val items = AppNavigationItem.DEFAULT_ITEMS
        val vendor =
            createGlobalContext(
                canBrowseAllCompanies = true,
                globalPermissions = setOf(GlobalPermission.COMPANIES_MANAGE),
            )
        val visible =
            AppNavigationItem.filterVisibleNavigationItems(
                items = items,
                authorizedContext = null,
                globalContext = vendor,
            )

        assertEquals(1, visible.size)
        assertEquals(AppNavigationItem.Events, visible.first())
    }

    @Test
    fun testVisibleItemsWhenNotAuthorizedAndSupportEngineer() {
        val items = AppNavigationItem.DEFAULT_ITEMS
        val support =
            createGlobalContext(
                canBrowseAllCompanies = true,
                globalPermissions = setOf(GlobalPermission.COMPANIES_MONITOR),
            )
        val visible =
            AppNavigationItem.filterVisibleNavigationItems(
                items = items,
                authorizedContext = null,
                globalContext = support,
            )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun testVisibleItemsWhenVendorAdminAndNoCompaniesAvailable() {
        val items = AppNavigationItem.DEFAULT_ITEMS
        val vendorNoCompanies =
            createGlobalContext(
                defaultCompanyId = null,
                canBrowseAllCompanies = true,
                globalPermissions = setOf(GlobalPermission.COMPANIES_MANAGE),
                memberCompanies = emptyList(),
            )
        val visible =
            AppNavigationItem.filterVisibleNavigationItems(
                items = items,
                authorizedContext = null,
                globalContext = vendorNoCompanies,
            )

        assertTrue(visible.isEmpty())
    }
}
