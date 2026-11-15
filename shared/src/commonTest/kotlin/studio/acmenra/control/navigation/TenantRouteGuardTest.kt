package studio.acmenra.control.navigation

import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessRestriction
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.PermissionKey
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.model.TenantPermission
import studio.acmenra.control.core.network.model.NetworkError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class TenantRouteGuardTest {
    private fun createAuthorizedContext(
        companyId: String = "c1",
        tenantPermissions: Set<TenantPermission> = setOf(TenantPermission.EVENTS_READ),
    ): AuthorizedTenantContext =
        AuthorizedTenantContext(
            userId = "u1",
            companyId = companyId,
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
        memberCompanies: List<CompanyBrief> =
            listOf(
                CompanyBrief("c1", "Acme", CompanyStatus.ACTIVE),
                CompanyBrief("c2", "Beta", CompanyStatus.ACTIVE),
            ),
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
            memberCompanies = memberCompanies,
        )

    @Test
    fun testGlobalStateIdleOrLoadingYieldsShowLoading() {
        val decisionIdle =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Idle,
                tenantState = TenantContextState.NotSelected,
                requestedCompanyId = "c1",
            )
        assertEquals(TenantGuardDecision.ShowLoading, decisionIdle)

        val decisionLoading =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.InitialLoading,
                tenantState = TenantContextState.NotSelected,
                requestedCompanyId = "c1",
            )
        assertEquals(TenantGuardDecision.ShowLoading, decisionLoading)
    }

    @Test
    fun testGlobalStateAccountFrozenYieldsAccountFrozenDecision() {
        val restriction =
            AccessRestriction(
                type = RestrictionType.ADMIN_BLOCKED,
                reason = "Blocked",
                restrictedAt = Instant.fromEpochMilliseconds(0),
                untilAt = null,
                supportContact = null,
            )
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Restricted.AccountFrozen(restriction),
                tenantState = TenantContextState.NotSelected,
                requestedCompanyId = "c1",
            )
        assertEquals(TenantGuardDecision.AccountFrozen(restriction), decision)
    }

    @Test
    fun testGlobalStateNoCompaniesAvailableYieldsNoCompaniesAvailableDecision() {
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Restricted.NoCompaniesAvailable,
                tenantState = TenantContextState.NotSelected,
                requestedCompanyId = "c1",
            )
        assertEquals(TenantGuardDecision.NoCompaniesAvailable, decision)
    }

    @Test
    fun testGlobalStateErrorYieldsGlobalErrorDecision() {
        val error = NetworkError.Connectivity("Network failed")
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Error(error),
                tenantState = TenantContextState.NotSelected,
                requestedCompanyId = "c1",
            )
        assertEquals(TenantGuardDecision.GlobalError(error), decision)
    }

    @Test
    fun testNullRequestedCompanyResolvesFallbackCompany() {
        val globalContext = createGlobalContext(defaultCompanyId = "c2")
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(globalContext),
                tenantState = TenantContextState.NotSelected,
                requestedCompanyId = null,
            )
        assertEquals(TenantGuardDecision.RedirectToCompany("c2"), decision)
    }

    @Test
    fun testBlankRequestedCompanyWithNoMemberCompaniesForVendorRedirectsToDashboard() {
        val vendor =
            createGlobalContext(
                memberCompanies = emptyList(),
                defaultCompanyId = null,
                canBrowseAllCompanies = true,
                globalPermissions = setOf(GlobalPermission.COMPANIES_MANAGE),
            )
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(vendor),
                tenantState = TenantContextState.NotSelected,
                requestedCompanyId = "",
            )
        assertEquals(TenantGuardDecision.RedirectToVendorDashboard, decision)
    }

    @Test
    fun testBlankRequestedCompanyWithNoMemberCompaniesForRegularUserYieldsAccessDenied() {
        val regular =
            createGlobalContext(
                memberCompanies = emptyList(),
                defaultCompanyId = null,
                canBrowseAllCompanies = false,
            )
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(regular),
                tenantState = TenantContextState.NotSelected,
                requestedCompanyId = "",
            )
        assertEquals(TenantGuardDecision.AccessDenied, decision)
    }

    @Test
    fun testUnauthorizedCompanyRequestedRedirectsToAccessibleFallback() {
        val user = createGlobalContext(defaultCompanyId = "c1")
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(user),
                tenantState = TenantContextState.NotSelected,
                requestedCompanyId = "inaccessible_company",
            )
        assertEquals(TenantGuardDecision.RedirectToCompany("c1"), decision)
    }

    @Test
    fun testTenantStateDifferentCompanyYieldsShowLoading() {
        val user = createGlobalContext()
        val authorizedForOther = createAuthorizedContext(companyId = "c2")
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(user),
                tenantState = TenantContextState.Authorized(authorizedForOther),
                requestedCompanyId = "c1",
            )
        assertEquals(TenantGuardDecision.ShowLoading, decision)
    }

    @Test
    fun testTenantStateTenantErrorYieldsTenantError() {
        val user = createGlobalContext()
        val error = NetworkError.Http(500, "Server error")
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(user),
                tenantState = TenantContextState.Error(companyId = "c1", error = error, canSwitchCompany = true),
                requestedCompanyId = "c1",
            )
        assertEquals(TenantGuardDecision.TenantError(error, "c1"), decision)
    }

    @Test
    fun testTenantStateCompanyFrozenYieldsCompanyFrozenDecision() {
        val user = createGlobalContext()
        val restriction =
            AccessRestriction(
                type = RestrictionType.BILLING_DEBT,
                reason = "Invoice overdue",
                restrictedAt = Instant.fromEpochMilliseconds(0),
                untilAt = null,
                supportContact = "help@company.com",
            )
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(user),
                tenantState =
                    TenantContextState.Restricted.CompanyFrozen(
                        companyId = "c1",
                        companyName = "Acme",
                        restriction = restriction,
                        canSwitchCompany = true,
                    ),
                requestedCompanyId = "c1",
            )
        assertEquals(TenantGuardDecision.CompanyFrozen("c1", "Acme", restriction), decision)
    }

    @Test
    fun testTenantStateNoPermissionsYieldsNoPermissionsDecision() {
        val user = createGlobalContext()
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(user),
                tenantState =
                    TenantContextState.Restricted.NoPermissions(
                        companyId = "c1",
                        companyName = "Acme",
                        canSwitchCompany = true,
                    ),
                requestedCompanyId = "c1",
            )
        assertEquals(TenantGuardDecision.NoPermissions("c1", "Acme"), decision)
    }

    @Test
    fun testTenantStateAccessDeniedYieldsTenantAccessDeniedDecision() {
        val user = createGlobalContext()
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(user),
                tenantState =
                    TenantContextState.Restricted.AccessDenied(
                        companyId = "c1",
                        companyName = "Acme",
                        canSwitchCompany = true,
                    ),
                requestedCompanyId = "c1",
            )
        assertEquals(TenantGuardDecision.TenantAccessDenied("c1", "Acme"), decision)
    }

    @Test
    fun testAuthorizedContextSatisfyingRequirementYieldsRenderContent() {
        val user = createGlobalContext()
        val context = createAuthorizedContext(companyId = "c1", tenantPermissions = setOf(TenantPermission.EVENTS_READ))
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(user),
                tenantState = TenantContextState.Authorized(context),
                requestedCompanyId = "c1",
                requirement = RoutePermissionRequirement.Tenant(TenantPermission.EVENTS_READ),
            )
        assertTrue(decision is TenantGuardDecision.RenderContent)
        assertEquals("c1", decision.context.companyId)
    }

    @Test
    fun testAuthorizedContextFailingRequirementYieldsNoPermissions() {
        val user = createGlobalContext()
        val context = createAuthorizedContext(companyId = "c1", tenantPermissions = setOf(TenantPermission.EVENTS_READ))
        val decision =
            resolveTenantGuardDecision(
                globalState = GlobalAccessState.Ready(user),
                tenantState = TenantContextState.Authorized(context),
                requestedCompanyId = "c1",
                requirement = RoutePermissionRequirement.Tenant(TenantPermission.DEVICES_DELETE),
            )
        assertEquals(TenantGuardDecision.NoPermissions("c1", "Acme Corp"), decision)
    }
}
