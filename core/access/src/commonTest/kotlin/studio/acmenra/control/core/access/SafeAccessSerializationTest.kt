package studio.acmenra.control.core.access

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.acmenra.control.core.access.data.serializer.PermissionSourcesSerializer
import studio.acmenra.control.core.access.data.serializer.SafeAccessOriginSetSerializer
import studio.acmenra.control.core.access.data.serializer.SafeAccessStatusSerializer
import studio.acmenra.control.core.access.data.serializer.SafeCompanyStatusSerializer
import studio.acmenra.control.core.access.data.serializer.SafeGlobalPermissionSetSerializer
import studio.acmenra.control.core.access.data.serializer.SafeRestrictionTypeSerializer
import studio.acmenra.control.core.access.data.serializer.SafeSupportGrantStatusSerializer
import studio.acmenra.control.core.access.data.serializer.SafeSupportModeSerializer
import studio.acmenra.control.core.access.data.serializer.SafeSupportPermissionSetSerializer
import studio.acmenra.control.core.access.data.serializer.SafeTenantPermissionSetSerializer
import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.PermissionKey
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.SupportGrantInfo
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.SupportMode
import studio.acmenra.control.core.access.domain.model.SupportPermission
import studio.acmenra.control.core.access.domain.model.TenantPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class SafeAccessSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Payload(
        @Serializable(with = SafeGlobalPermissionSetSerializer::class)
        val globalPermissions: Set<GlobalPermission> = emptySet(),
        @Serializable(with = SafeTenantPermissionSetSerializer::class)
        val tenantPermissions: Set<TenantPermission> = emptySet(),
        @Serializable(with = SafeSupportPermissionSetSerializer::class)
        val supportPermissions: Set<SupportPermission> = emptySet(),
        @Serializable(with = SafeCompanyStatusSerializer::class)
        val companyStatus: CompanyStatus = CompanyStatus.UNKNOWN,
        @Serializable(with = SafeAccessStatusSerializer::class)
        val accessStatus: AccessStatus = AccessStatus.UNKNOWN,
        @Serializable(with = SafeRestrictionTypeSerializer::class)
        val restrictionType: RestrictionType = RestrictionType.UNKNOWN,
        @Serializable(with = SafeSupportModeSerializer::class)
        val supportMode: SupportMode = SupportMode.UNKNOWN,
        @Serializable(with = SafeSupportGrantStatusSerializer::class)
        val grantStatus: SupportGrantStatus = SupportGrantStatus.UNKNOWN,
    )

    @Serializable
    private data class SourcesPayload(
        @Serializable(with = PermissionSourcesSerializer::class)
        val permissionSources: Map<PermissionKey, Set<AccessOrigin>>,
    )

    private class TestTimeMark(
        private val elapsed: Duration,
    ) : TimeMark {
        override fun elapsedNow(): Duration = elapsed

        override fun plus(duration: Duration): TimeMark = TestTimeMark(elapsed - duration)

        override fun minus(duration: Duration): TimeMark = TestTimeMark(elapsed + duration)
    }

    @Test
    fun unknownPermissionsAreDroppedAndUnknownStatusesFallback() {
        val value =
            json.decodeFromString<Payload>(
                """{"globalPermissions":["COMPANIES_MONITOR","FUTURE"],"tenantPermissions":["EVENTS_READ","FUTURE"],"supportPermissions":["DEVICE_RESTART","FUTURE"],"companyStatus":"ARCHIVED","accessStatus":"BYPASS","restrictionType":"SANCTIONS","supportMode":"AI","grantStatus":"PENDING"}""",
            )
        assertEquals(setOf(GlobalPermission.COMPANIES_MONITOR), value.globalPermissions)
        assertEquals(setOf(TenantPermission.EVENTS_READ), value.tenantPermissions)
        assertEquals(setOf(SupportPermission.DEVICE_RESTART), value.supportPermissions)
        assertEquals(CompanyStatus.UNKNOWN, value.companyStatus)
        assertEquals(AccessStatus.UNKNOWN, value.accessStatus)
        assertEquals(RestrictionType.UNKNOWN, value.restrictionType)
        assertEquals(SupportMode.UNKNOWN, value.supportMode)
        assertEquals(SupportGrantStatus.UNKNOWN, value.grantStatus)
    }

    @Test
    fun nullAndUnknownArrayElementsAreIgnored() {
        val value =
            json.decodeFromString<Payload>(
                """{"tenantPermissions":["EVENTS_READ",null,""],"supportPermissions":[]}""",
            )
        assertEquals(setOf(TenantPermission.EVENTS_READ), value.tenantPermissions)
        assertTrue(value.supportPermissions.isEmpty())
    }

    @Test
    fun malformedArrayShapeFails() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<Payload>("""{"tenantPermissions":"NOT_AN_ARRAY"}""")
        }
    }

    @Test
    fun permissionSourcesMapsKeysAndDropsUnknownEntries() {
        val value =
            json.decodeFromString<SourcesPayload>(
                """{"permissionSources":{"DEVICES_READ":["DIRECT_MEMBERSHIP","FUTURE"],"DEVICE_RESTART":["SUPPORT_GRANT"],"FUTURE_PERMISSION":["DIRECT_MEMBERSHIP"]}}""",
            )
        assertEquals(
            setOf(AccessOrigin.DIRECT_MEMBERSHIP),
            value.permissionSources[PermissionKey.Tenant(TenantPermission.DEVICES_READ)],
        )
        assertEquals(
            setOf(AccessOrigin.SUPPORT_GRANT),
            value.permissionSources[PermissionKey.Support(SupportPermission.DEVICE_RESTART)],
        )
        assertEquals(2, value.permissionSources.size)
    }

    @Test
    fun permissionSourcesRoundTrip() {
        val value =
            SourcesPayload(
                mapOf(
                    PermissionKey.Tenant(TenantPermission.EVENTS_READ) to setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                ),
            )
        assertEquals(value, json.decodeFromString<SourcesPayload>(json.encodeToString(value)))
    }

    @Test
    fun originSetDropsUnknownAndUnknownSentinel() {
        val value =
            json.decodeFromString(
                SafeAccessOriginSetSerializer,
                """["DIRECT_MEMBERSHIP","UNKNOWN","FUTURE"]""",
            )
        assertEquals(setOf(AccessOrigin.DIRECT_MEMBERSHIP), value)
    }

    @Test
    fun permissionKeyReturnsNullForUnknown() {
        assertEquals(PermissionKey.Tenant(TenantPermission.DEVICES_READ), PermissionKey.fromRawName("DEVICES_READ"))
        assertEquals(
            PermissionKey.Support(SupportPermission.DEVICE_RESTART),
            PermissionKey.fromRawName("DEVICE_RESTART"),
        )
        assertEquals(
            PermissionKey.Global(GlobalPermission.COMPANIES_MONITOR),
            PermissionKey.fromRawName("COMPANIES_MONITOR"),
        )
        assertNull(PermissionKey.fromRawName("NOT_A_PERMISSION"))
    }

    @Test
    fun expiredGrantRetainsOnlyPermissionsWithNonGrantSource() {
        val grant =
            SupportGrantInfo(
                grantId = "g1",
                status = SupportGrantStatus.ACTIVE,
                mode = SupportMode.ENGINEERING_SUPPORT,
                ticketReference = null,
                allowBillingOverride = false,
                grantedByUserId = null,
                grantedAt = null,
                expiresAt = null,
                remainingTtlSec = 3600,
                revokedAt = null,
                fetchedAtMark = TimeSource.Monotonic.markNow(),
            )
        val context =
            AuthorizedTenantContext(
                userId = "u1",
                companyId = "c1",
                companyName = "Test",
                tenantRevision = 1,
                accessRevision = "r1",
                companyStatus = CompanyStatus.ACTIVE,
                accessStatus = AccessStatus.ACTIVE,
                activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP, AccessOrigin.SUPPORT_GRANT),
                rawTenantPermissions =
                    setOf(
                        TenantPermission.EVENTS_READ,
                        TenantPermission.AUDIT_READ,
                        TenantPermission.DEVICES_READ,
                    ),
                rawSupportPermissions = setOf(SupportPermission.DEVICE_RESTART),
                permissionSources =
                    mapOf(
                        PermissionKey.Tenant(TenantPermission.EVENTS_READ) to setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                        PermissionKey.Tenant(TenantPermission.AUDIT_READ) to setOf(AccessOrigin.SUPPORT_GRANT),
                        PermissionKey.Tenant(TenantPermission.DEVICES_READ) to
                            setOf(AccessOrigin.DIRECT_MEMBERSHIP, AccessOrigin.SUPPORT_GRANT),
                    ),
                supportGrant = grant,
            )
        assertEquals(3, context.effectiveTenantPermissions.size)
        val expired = context.withSupportGrantExpiredLocally()
        assertEquals(
            setOf(TenantPermission.EVENTS_READ, TenantPermission.DEVICES_READ),
            expired.effectiveTenantPermissions,
        )
        assertTrue(expired.effectiveSupportPermissions.isEmpty())
    }

    @Test
    fun supportGrantCalculatesRemainingDurationWithTestTimeMark() {
        val mark = TestTimeMark(elapsed = 600.seconds)
        val grant =
            SupportGrantInfo(
                grantId = "g2",
                status = SupportGrantStatus.ACTIVE,
                mode = SupportMode.READ_ONLY_SUPPORT,
                ticketReference = null,
                allowBillingOverride = false,
                grantedByUserId = null,
                grantedAt = null,
                expiresAt = null,
                remainingTtlSec = 1000,
                revokedAt = null,
                fetchedAtMark = mark,
            )
        assertEquals(400.seconds, grant.remainingDurationNow())
        assertFalse(grant.isExpiredNow())

        val expiredMark = TestTimeMark(elapsed = 1001.seconds)
        val expiredGrant = grant.copy(fetchedAtMark = expiredMark)
        assertEquals(Duration.ZERO, expiredGrant.remainingDurationNow())
        assertTrue(expiredGrant.isExpiredNow())
    }

    @Test
    fun allOfAndAnyOfEvaluateAcrossDomains() {
        val context =
            AuthorizedTenantContext(
                userId = "u",
                companyId = "c",
                companyName = "C",
                tenantRevision = 1,
                accessRevision = "r",
                companyStatus = CompanyStatus.ACTIVE,
                accessStatus = AccessStatus.ACTIVE,
                activeOrigins = setOf(AccessOrigin.DIRECT_MEMBERSHIP),
                rawTenantPermissions = setOf(TenantPermission.DEVICES_READ),
                rawSupportPermissions = setOf(SupportPermission.DEVICE_DIAGNOSTICS),
                permissionSources = emptyMap(),
                supportGrant = null,
            )
        assertTrue(
            RoutePermissionRequirement
                .AllOf(
                    tenant = setOf(TenantPermission.DEVICES_READ),
                    support = setOf(SupportPermission.DEVICE_DIAGNOSTICS),
                ).isSatisfiedBy(context),
        )
        assertFalse(
            RoutePermissionRequirement
                .AllOf(
                    tenant = setOf(TenantPermission.DEVICES_READ),
                    support = setOf(SupportPermission.DEVICE_RESTART),
                ).isSatisfiedBy(context),
        )
        assertTrue(
            RoutePermissionRequirement
                .AnyOf(
                    tenant = setOf(TenantPermission.USERS_MANAGE),
                    support = setOf(SupportPermission.DEVICE_DIAGNOSTICS),
                ).isSatisfiedBy(context),
        )
        assertFalse(
            RoutePermissionRequirement
                .AnyOf(
                    tenant = setOf(TenantPermission.USERS_MANAGE),
                    support = setOf(SupportPermission.DEVICE_RESTART),
                ).isSatisfiedBy(context),
        )
    }

    @Test
    fun emptyCompositeRequirementsAreRejected() {
        assertFailsWith<IllegalArgumentException> { RoutePermissionRequirement.AllOf() }
        assertFailsWith<IllegalArgumentException> { RoutePermissionRequirement.AnyOf() }
    }
}
