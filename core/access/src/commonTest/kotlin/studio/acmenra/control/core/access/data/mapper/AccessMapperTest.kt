package studio.acmenra.control.core.access.data.mapper

import studio.acmenra.control.core.access.data.model.AccessRestrictionDto
import studio.acmenra.control.core.access.data.model.CompanyAccessResponseDto
import studio.acmenra.control.core.access.data.model.CompanyBriefDto
import studio.acmenra.control.core.access.data.model.GlobalContextResponseDto
import studio.acmenra.control.core.access.data.model.SupportGrantDto
import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.PermissionKey
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.SupportMode
import studio.acmenra.control.core.access.domain.model.SupportPermission
import studio.acmenra.control.core.access.domain.model.TenantPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark

class AccessMapperTest {
    private class StaticTestTimeMark(
        private val elapsed: Duration,
    ) : TimeMark {
        override fun elapsedNow(): Duration = elapsed

        override fun plus(duration: Duration): TimeMark = StaticTestTimeMark(elapsed - duration)

        override fun minus(duration: Duration): TimeMark = StaticTestTimeMark(elapsed + duration)
    }

    private val mapper = AccessMapper()

    @Test
    fun mapsGlobalContextWithValidAndMissingFields() {
        val dto =
            GlobalContextResponseDto(
                userId = "user_1",
                serverTime = "2026-11-14T12:00:00Z",
                globalPermissions = setOf(GlobalPermission.COMPANIES_MONITOR),
                defaultCompanyId = "comp_1",
                canBrowseAllCompanies = true,
                memberCompanies =
                    listOf(
                        CompanyBriefDto("c1", "Company 1", CompanyStatus.ACTIVE),
                        CompanyBriefDto("c2", "Company 2", CompanyStatus.UNKNOWN),
                    ),
            )

        val domain = mapper.mapGlobalContext(dto)
        assertEquals("user_1", domain.userId)
        assertEquals(Instant.parse("2026-11-14T12:00:00Z"), domain.serverTime)
        assertEquals(setOf(GlobalPermission.COMPANIES_MONITOR), domain.globalPermissions)
        assertEquals("comp_1", domain.defaultCompanyId)
        assertTrue(domain.canBrowseAllCompanies)
        assertEquals(2, domain.memberCompanies.size)
        assertTrue(domain.hasMultipleCompanies)
    }

    @Test
    fun mapsCompanyAccessWithInjectedTimeMarkAndSupportGrant() {
        val mark = StaticTestTimeMark(elapsed = 100.seconds)
        val dto =
            CompanyAccessResponseDto(
                companyId = "c1",
                companyName = "Acme Corp",
                serverTime = "2026-11-14T12:00:00Z",
                revision = "rev_123",
                companyStatus = CompanyStatus.ACTIVE,
                accessStatus = AccessStatus.ACTIVE_SUPPORT_OVERRIDE,
                activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP, AccessOrigin.SUPPORT_GRANT),
                restriction =
                    AccessRestrictionDto(
                        type = RestrictionType.MAINTENANCE,
                        reason = "Scheduled maintenance",
                        restrictedAt = "2026-11-14T10:00:00Z",
                        untilAt = "2026-11-14T14:00:00Z",
                        supportContact = "support@acmenra.studio",
                    ),
                tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                supportPermissions = setOf(SupportPermission.DEVICE_RESTART),
                permissionSources =
                    mapOf(
                        PermissionKey.Tenant(TenantPermission.EVENTS_READ) to setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                    ),
                supportGrant =
                    SupportGrantDto(
                        grantId = "grant_1",
                        status = SupportGrantStatus.ACTIVE,
                        mode = SupportMode.ENGINEERING_SUPPORT,
                        ticketReference = "TICKET-123",
                        allowBillingOverride = true,
                        grantedByUserId = "admin_1",
                        grantedAt = "2026-11-14T11:00:00Z",
                        expiresAt = "2026-11-14T13:00:00Z",
                        remainingTtlSec = 1000L,
                        revokedAt = null,
                    ),
            )

        val domain = mapper.mapCompanyAccess(dto, fetchedAtMark = mark)
        assertEquals("c1", domain.companyId)
        assertEquals("Acme Corp", domain.companyName)
        assertEquals(CompanyStatus.ACTIVE, domain.companyStatus)
        assertEquals(AccessStatus.ACTIVE_SUPPORT_OVERRIDE, domain.accessStatus)
        assertEquals(RestrictionType.MAINTENANCE, domain.restriction?.type)
        assertEquals("Scheduled maintenance", domain.restriction?.reason)
        assertEquals(Instant.parse("2026-11-14T10:00:00Z"), domain.restriction?.restrictedAt)

        val grant = domain.supportGrant
        assertNotNull(grant)
        assertEquals("grant_1", grant.grantId)
        assertEquals(SupportGrantStatus.ACTIVE, grant.status)
        assertEquals(SupportMode.ENGINEERING_SUPPORT, grant.mode)
        assertEquals("TICKET-123", grant.ticketReference)
        assertTrue(grant.allowBillingOverride)
        assertEquals(900.seconds, grant.remainingDurationNow())
    }

    @Test
    fun handlesMalformedAndNullDatesGracefully() {
        val dto =
            CompanyAccessResponseDto(
                companyId = "c2",
                companyName = "Beta Corp",
                serverTime = "NOT_A_DATE",
                revision = "r2",
                restriction =
                    AccessRestrictionDto(
                        type = RestrictionType.BILLING_DEBT,
                        restrictedAt = "INVALID_TIMESTAMP",
                        untilAt = null,
                    ),
                supportGrant =
                    SupportGrantDto(
                        grantId = "g2",
                        grantedAt = null,
                        expiresAt = "INVALID",
                        revokedAt = null,
                    ),
            )

        val domain = mapper.mapCompanyAccess(dto)
        assertEquals(Instant.fromEpochMilliseconds(0), domain.serverTime)
        assertNull(domain.restriction?.restrictedAt)
        assertNull(domain.restriction?.untilAt)
        assertNull(domain.supportGrant?.grantedAt)
        assertNull(domain.supportGrant?.expiresAt)
    }

    @Test
    fun mapsAuthorizedTenantContext() {
        val domain =
            mapper.mapCompanyAccess(
                CompanyAccessResponseDto(
                    companyId = "c1",
                    companyName = "Acme",
                    serverTime = "2026-11-14T12:00:00Z",
                    revision = "rev_99",
                    tenantPermissions = setOf(TenantPermission.EVENTS_READ),
                    supportPermissions = setOf(SupportPermission.DEVICE_DIAGNOSTICS),
                ),
            )

        val authContext = mapper.mapAuthorizedTenantContext("user_42", domain, tenantRevision = 5L)
        assertEquals("user_42", authContext.userId)
        assertEquals("c1", authContext.companyId)
        assertEquals("Acme", authContext.companyName)
        assertEquals(5L, authContext.tenantRevision)
        assertEquals("rev_99", authContext.accessRevision)
        assertEquals(setOf(TenantPermission.EVENTS_READ), authContext.effectiveTenantPermissions)
        assertEquals(setOf(SupportPermission.DEVICE_DIAGNOSTICS), authContext.effectiveSupportPermissions)
    }
}
