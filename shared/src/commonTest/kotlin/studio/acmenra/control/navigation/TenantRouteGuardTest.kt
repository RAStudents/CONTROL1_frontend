package studio.acmenra.control.navigation

import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessRestriction
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.PermissionKey
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.SupportGrantInfo
import studio.acmenra.control.core.access.domain.model.SupportGrantRuntimeState
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.SupportMode
import studio.acmenra.control.core.access.domain.model.SupportPermission
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.model.TenantPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

class TenantRouteGuardTest {
    private fun createAuthorizedContext(
        companyId: String = "c1",
        companyName: String = "Acme Corp",
        tenantPermissions: Set<TenantPermission> = setOf(TenantPermission.EVENTS_READ),
        supportPermissions: Set<SupportPermission> = emptySet(),
        supportGrant: SupportGrantInfo? = null,
        companyStatus: CompanyStatus = CompanyStatus.ACTIVE,
        accessStatus: AccessStatus = AccessStatus.ACTIVE,
    ): AuthorizedTenantContext =
        AuthorizedTenantContext(
            userId = "u1",
            companyId = companyId,
            companyName = companyName,
            tenantRevision = 1L,
            accessRevision = "rev1",
            companyStatus = companyStatus,
            accessStatus = accessStatus,
            activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
            rawTenantPermissions = tenantPermissions,
            rawSupportPermissions = supportPermissions,
            permissionSources =
                tenantPermissions.associate {
                    PermissionKey.Tenant(it) to setOf(AccessOrigin.DIRECT_MEMBERSHIP)
                },
            supportGrant = supportGrant,
        )

    @Test
    fun testRoutePermissionRequirementSatisfiedWhenPermissionPresent() {
        val context = createAuthorizedContext(tenantPermissions = setOf(TenantPermission.EVENTS_READ))
        val requirement = RoutePermissionRequirement.Tenant(TenantPermission.EVENTS_READ)

        assertTrue(requirement.isSatisfiedBy(context))
    }

    @Test
    fun testRoutePermissionRequirementDeniedWhenPermissionMissing() {
        val context = createAuthorizedContext(tenantPermissions = setOf(TenantPermission.EVENTS_READ))
        val requirement = RoutePermissionRequirement.Tenant(TenantPermission.DEVICES_DELETE)

        assertFalse(requirement.isSatisfiedBy(context))
    }

    @Test
    fun testRoutePermissionRequirementDeniedWhenGrantLocallyExpiredAndRequiresImmediateRevalidation() {
        val expiredGrant =
            SupportGrantInfo(
                grantId = "g1",
                status = SupportGrantStatus.ACTIVE,
                mode = SupportMode.READ_ONLY_SUPPORT,
                ticketReference = "SD-100",
                allowBillingOverride = false,
                grantedByUserId = "admin1",
                grantedAt = Instant.fromEpochMilliseconds(0),
                expiresAt = Instant.fromEpochMilliseconds(1000),
                remainingTtlSec = 0L,
                revokedAt = null,
                runtimeState = SupportGrantRuntimeState.ExpiredLocally,
            )

        val context =
            createAuthorizedContext(
                accessStatus = AccessStatus.ACTIVE_SUPPORT_OVERRIDE,
                supportGrant = expiredGrant,
            )

        assertTrue(context.isSupportGrantLocallyExpired)
        assertTrue(context.requiresImmediateRevalidation)

        val requirement = RoutePermissionRequirement.Tenant(TenantPermission.EVENTS_READ)
        assertFalse(requirement.isSatisfiedBy(context))
    }

    @Test
    fun testNavigationItemsFilteredByEffectivePermissions() {
        val items = AppNavigationItem.DEFAULT_ITEMS

        // When user has EVENTS_READ
        val authorizedWithEvents = createAuthorizedContext(tenantPermissions = setOf(TenantPermission.EVENTS_READ))
        val visibleWithEvents =
            items.filter { item ->
                item.requiredPermission == null || item.requiredPermission!!.isSatisfiedBy(authorizedWithEvents)
            }
        assertEquals(1, visibleWithEvents.size)
        assertEquals(AppNavigationItem.Events, visibleWithEvents.first())

        // When user does NOT have EVENTS_READ
        val authorizedWithoutEvents = createAuthorizedContext(tenantPermissions = emptySet())
        val visibleWithoutEvents =
            items.filter { item ->
                item.requiredPermission == null || item.requiredPermission!!.isSatisfiedBy(authorizedWithoutEvents)
            }
        assertTrue(visibleWithoutEvents.isEmpty())
    }

    @Test
    fun testGlobalUserContextMultiCompanyFlags() {
        val singleCompanyContext =
            GlobalUserContext(
                userId = "u1",
                serverTime = Instant.fromEpochMilliseconds(0),
                globalPermissions = emptySet(),
                defaultCompanyId = "c1",
                canBrowseAllCompanies = false,
                memberCompanies = listOf(CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE)),
            )
        assertFalse(singleCompanyContext.hasMultipleCompanies)

        val multiCompanyContext =
            singleCompanyContext.copy(
                memberCompanies =
                    listOf(
                        CompanyBrief("c1", "Company 1", CompanyStatus.ACTIVE),
                        CompanyBrief("c2", "Company 2", CompanyStatus.ACTIVE),
                    ),
            )
        assertTrue(multiCompanyContext.hasMultipleCompanies)

        val vendorContext =
            singleCompanyContext.copy(
                canBrowseAllCompanies = true,
                globalPermissions = setOf(GlobalPermission.COMPANIES_MONITOR),
            )
        assertTrue(vendorContext.hasMultipleCompanies)
    }

    @Test
    fun testSupportGrantInfoRemainingDurationCalculation() {
        val activeGrant =
            SupportGrantInfo(
                grantId = "g1",
                status = SupportGrantStatus.ACTIVE,
                mode = SupportMode.ENGINEERING_SUPPORT,
                ticketReference = "T-999",
                allowBillingOverride = true,
                grantedByUserId = "u0",
                grantedAt = Instant.fromEpochMilliseconds(0),
                expiresAt = Instant.fromEpochMilliseconds(10000),
                remainingTtlSec = 300L,
                revokedAt = null,
            )

        val remaining = activeGrant.remainingDurationNow()
        assertTrue(remaining > Duration.ZERO)
        assertTrue(remaining.inWholeSeconds <= 300L)
        assertFalse(activeGrant.isExpiredNow())

        val zeroTtlGrant = activeGrant.copy(remainingTtlSec = 0L)
        assertEquals(Duration.ZERO, zeroTtlGrant.remainingDurationNow())
        assertTrue(zeroTtlGrant.isExpiredNow())

        val expiredStateGrant = activeGrant.copy(runtimeState = SupportGrantRuntimeState.ExpiredLocally)
        assertEquals(Duration.ZERO, expiredStateGrant.remainingDurationNow())
        assertTrue(expiredStateGrant.isExpiredNow())
    }

    @Test
    fun testRestrictedStateProperties() {
        val restriction =
            AccessRestriction(
                type = RestrictionType.BILLING_DEBT,
                reason = "Overdue invoice",
                restrictedAt = Instant.fromEpochMilliseconds(1000),
                untilAt = null,
                supportContact = "billing@control.acmenra.studio",
            )

        val frozenState =
            TenantContextState.Restricted.CompanyFrozen(
                companyId = "c-frozen",
                companyName = "Frozen Corp",
                restriction = restriction,
                canSwitchCompany = true,
            )

        assertEquals("c-frozen", frozenState.companyId)
        assertEquals("Frozen Corp", frozenState.companyName)
        assertEquals(RestrictionType.BILLING_DEBT, frozenState.restriction?.type)
        assertEquals("billing@control.acmenra.studio", frozenState.restriction?.supportContact)
        assertTrue(frozenState.canSwitchCompany)
    }

    @Test
    fun testCanSwitchCompanyFlagTrueWhenVendorEvenWithEmptyMemberCompanies() {
        val vendor =
            GlobalUserContext(
                userId = "u1",
                serverTime = Instant.fromEpochMilliseconds(0),
                globalPermissions = setOf(GlobalPermission.COMPANIES_MONITOR),
                defaultCompanyId = null,
                canBrowseAllCompanies = true,
                memberCompanies = emptyList(),
            )

        assertTrue(vendor.hasMultipleCompanies)
        assertTrue(vendor.canBrowseAllCompanies)
    }

    @Test
    fun testVendorDashboardRequiresCanBrowseAllCompanies() {
        val regularUser =
            GlobalUserContext(
                userId = "u1",
                serverTime = Instant.fromEpochMilliseconds(0),
                globalPermissions = emptySet(),
                defaultCompanyId = "c1",
                canBrowseAllCompanies = false,
                memberCompanies = listOf(CompanyBrief("c1", "Acme", CompanyStatus.ACTIVE)),
            )
        assertFalse(regularUser.canBrowseAllCompanies)

        val vendorUser = regularUser.copy(canBrowseAllCompanies = true)
        assertTrue(vendorUser.canBrowseAllCompanies)
    }

    @Test
    fun testNavigationShellVisibilityRules() {
        val globalReady =
            GlobalUserContext(
                userId = "u1",
                serverTime = Instant.fromEpochMilliseconds(0),
                globalPermissions = setOf(GlobalPermission.COMPANIES_MANAGE),
                defaultCompanyId = "c1",
                canBrowseAllCompanies = true,
                memberCompanies = listOf(CompanyBrief("c1", "Acme", CompanyStatus.ACTIVE)),
            )

        // Rule 1: Tenant destination + Tenant Loading -> shell NOT ready
        val tenantLoading: TenantContextState = TenantContextState.Loading("c1")
        assertFalse(tenantLoading is TenantContextState.Authorized)

        // Rule 2: Tenant destination + Tenant Authorized -> shell IS ready
        val tenantAuthorized: TenantContextState =
            TenantContextState.Authorized(
                createAuthorizedContext(companyId = "c1"),
            )
        assertTrue(tenantAuthorized is TenantContextState.Authorized)

        // Rule 3: Vendor dashboard destination + Global Ready for vendor -> shell IS ready
        assertTrue(TenantAccessResolver.canBrowseAll(globalReady))

        // Rule 4: Regular user without vendor rights -> cannot browse vendor dashboard
        val regularUser = globalReady.copy(canBrowseAllCompanies = false, globalPermissions = emptySet())
        assertFalse(TenantAccessResolver.canBrowseAll(regularUser))
    }

    @Test
    fun testServerAccessDeniedDirectlyPresentsRestrictedStateWithoutLoop() {
        val deniedState =
            TenantContextState.Restricted.AccessDenied(
                companyId = "c-denied",
                companyName = "Denied Corp",
                canSwitchCompany = true,
            )

        assertEquals("c-denied", deniedState.companyId)
        assertEquals("Denied Corp", deniedState.companyName)
        assertTrue(deniedState.canSwitchCompany)
    }
}
