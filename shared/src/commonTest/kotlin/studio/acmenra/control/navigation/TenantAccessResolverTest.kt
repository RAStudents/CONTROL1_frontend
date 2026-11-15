package studio.acmenra.control.navigation

import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TenantAccessResolverTest {
    private fun createUserContext(
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

    private fun createVendorAdminContext(
        memberCompanies: List<CompanyBrief> = emptyList(),
        defaultCompanyId: String? = null,
    ): GlobalUserContext =
        createUserContext(
            memberCompanies = memberCompanies,
            defaultCompanyId = defaultCompanyId,
            canBrowseAllCompanies = true,
            globalPermissions = setOf(GlobalPermission.COMPANIES_MANAGE),
        )

    private fun createSupportEngineerContext(
        memberCompanies: List<CompanyBrief> =
            listOf(CompanyBrief("c1", "Acme", CompanyStatus.ACTIVE)),
        defaultCompanyId: String? = "c1",
    ): GlobalUserContext =
        createUserContext(
            memberCompanies = memberCompanies,
            defaultCompanyId = defaultCompanyId,
            canBrowseAllCompanies = true,
            globalPermissions = setOf(GlobalPermission.COMPANIES_MONITOR),
        )

    @Test
    fun testRegularUserHasAccessOnlyToMemberCompanies() {
        val user = createUserContext()

        assertTrue(TenantAccessResolver.hasAccess(user, "c1"))
        assertTrue(TenantAccessResolver.hasAccess(user, "c2"))
        assertFalse(TenantAccessResolver.hasAccess(user, "c3"))
        assertFalse(TenantAccessResolver.hasAccess(user, "unauthorized-company"))
        assertFalse(TenantAccessResolver.hasAccess(user, ""))
        assertFalse(TenantAccessResolver.hasAccess(user, "   "))
    }

    @Test
    fun testVendorAdminHasAccessToAnyCompany() {
        val vendor = createVendorAdminContext()

        assertTrue(TenantAccessResolver.isVendorAdmin(vendor))
        assertTrue(TenantAccessResolver.hasAccess(vendor, "c1"))
        assertTrue(TenantAccessResolver.hasAccess(vendor, "c-unknown-999"))
        assertFalse(TenantAccessResolver.hasAccess(vendor, ""))
        assertFalse(TenantAccessResolver.hasAccess(vendor, "   "))
    }

    @Test
    fun testSupportEngineerCannotAccessNonMemberCompany() {
        val support = createSupportEngineerContext()

        assertFalse(TenantAccessResolver.isVendorAdmin(support))
        assertTrue(support.canBrowseAllCompanies)
        assertTrue(TenantAccessResolver.hasAccess(support, "c1"))
        assertFalse(TenantAccessResolver.hasAccess(support, "c2"))
        assertFalse(TenantAccessResolver.hasAccess(support, "comp-002"))
        assertFalse(TenantAccessResolver.hasAccess(support, "c-unknown-999"))
        assertFalse(TenantAccessResolver.hasAccess(support, ""))
        assertFalse(TenantAccessResolver.hasAccess(support, "   "))
    }

    @Test
    fun testResolveFallbackPrefersPreviousAuthorizedCompanyIfAccessible() {
        val user = createUserContext()

        val fallback = TenantAccessResolver.resolveFallbackCompanyId(user, previousAuthorizedCompanyId = "c2")
        assertEquals("c2", fallback)
    }

    @Test
    fun testResolveFallbackIgnoresInaccessiblePreviousCompany() {
        val user = createUserContext(defaultCompanyId = "c1")

        val fallback =
            TenantAccessResolver.resolveFallbackCompanyId(
                user,
                previousAuthorizedCompanyId = "c_inaccessible",
            )
        assertEquals("c1", fallback)
    }

    @Test
    fun testResolveFallbackUsesDefaultCompanyWhenNoPreviousCompany() {
        val user = createUserContext(defaultCompanyId = "c2")

        val fallback = TenantAccessResolver.resolveFallbackCompanyId(user, previousAuthorizedCompanyId = null)
        assertEquals("c2", fallback)
    }

    @Test
    fun testResolveFallbackUsesFirstMemberCompanyWhenDefaultCompanyIsNull() {
        val user = createUserContext(defaultCompanyId = null)

        val fallback = TenantAccessResolver.resolveFallbackCompanyId(user, previousAuthorizedCompanyId = null)
        assertEquals("c1", fallback)
    }

    @Test
    fun testResolveFallbackReturnsNullWhenNoMemberCompaniesAndNotVendor() {
        val user =
            createUserContext(
                memberCompanies = emptyList(),
                defaultCompanyId = null,
                canBrowseAllCompanies = false,
            )

        val fallback = TenantAccessResolver.resolveFallbackCompanyId(user, previousAuthorizedCompanyId = null)
        assertNull(fallback)
    }

    @Test
    fun testResolveFallbackForVendorPreservesPreviousAuthorizedCompany() {
        val vendor = createVendorAdminContext()

        val fallback =
            TenantAccessResolver.resolveFallbackCompanyId(
                vendor,
                previousAuthorizedCompanyId = "any_previous_company",
            )
        assertEquals("any_previous_company", fallback)
    }

    @Test
    fun testResolveFallbackForSupportEngineerRejectsInaccessiblePreviousCompany() {
        val support = createSupportEngineerContext(defaultCompanyId = "c1")

        val fallback =
            TenantAccessResolver.resolveFallbackCompanyId(
                support,
                previousAuthorizedCompanyId = "unauthorized_company",
            )
        assertEquals("c1", fallback)
    }
}
