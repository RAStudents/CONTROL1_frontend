package studio.acmenra.control.core.access.domain

import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class TenantAccessValidatorTest {
    private fun createUser(globalPermissions: Set<GlobalPermission> = emptySet()): GlobalUserContext =
        GlobalUserContext(
            userId = "u1",
            serverTime = Instant.fromEpochMilliseconds(0),
            globalPermissions = globalPermissions,
            defaultCompanyId = "c1",
            canBrowseAllCompanies = false,
            memberCompanies = emptyList(),
        )

    @Test
    fun testVendorWithCompaniesManageIsValid() {
        val vendor = createUser(globalPermissions = setOf(GlobalPermission.COMPANIES_MANAGE))
        val isRogue =
            TenantAccessValidator.isRogueVendorOrigin(
                activeOrigins = setOf(AccessOrigin.VENDOR_GLOBAL),
                userContext = vendor,
            )
        assertEquals(false, isRogue)
    }

    @Test
    fun testVendorWithoutCompaniesManageIsRogue() {
        val nonVendor = createUser(globalPermissions = emptySet())
        val isRogue =
            TenantAccessValidator.isRogueVendorOrigin(
                activeOrigins = setOf(AccessOrigin.VENDOR_GLOBAL),
                userContext = nonVendor,
            )
        assertEquals(true, isRogue)
    }

    @Test
    fun testDirectMembershipWithVendorGlobalIsValidEvenWithoutCompaniesManage() {
        val regularUser = createUser(globalPermissions = emptySet())
        val isRogue =
            TenantAccessValidator.isRogueVendorOrigin(
                activeOrigins = setOf(AccessOrigin.VENDOR_GLOBAL, AccessOrigin.DIRECT_MEMBERSHIP),
                userContext = regularUser,
            )
        assertEquals(false, isRogue)
    }

    @Test
    fun testNullUserContextReturnsNullRequiringContextResolution() {
        val isRogue =
            TenantAccessValidator.isRogueVendorOrigin(
                activeOrigins = setOf(AccessOrigin.VENDOR_GLOBAL),
                userContext = null,
            )
        assertNull(isRogue)
    }

    @Test
    fun testVerdictDistinguishesValidRogueAndNeedsGlobal() {
        val vendor = createUser(globalPermissions = setOf(GlobalPermission.COMPANIES_MANAGE))
        val regular = createUser(globalPermissions = emptySet())

        assertEquals(
            OriginVerdict.VALID,
            TenantAccessValidator.verdict(setOf(AccessOrigin.VENDOR_GLOBAL), vendor),
        )
        assertEquals(
            OriginVerdict.ROGUE,
            TenantAccessValidator.verdict(setOf(AccessOrigin.VENDOR_GLOBAL), regular),
        )
        assertEquals(
            OriginVerdict.NEEDS_GLOBAL,
            TenantAccessValidator.verdict(setOf(AccessOrigin.VENDOR_GLOBAL), null),
        )
        assertEquals(
            OriginVerdict.VALID,
            TenantAccessValidator.verdict(setOf(AccessOrigin.DIRECT_MEMBERSHIP), regular),
        )
    }
}
