package studio.acmenra.control.core.access.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class CompanyStatus { ACTIVE, RESTRICTED, UNKNOWN }

enum class AccessStatus { ACTIVE, ACTIVE_SUPPORT_OVERRIDE, RESTRICTED, UNKNOWN }

enum class AccessOrigin { DIRECT_MEMBERSHIP, VENDOR_GLOBAL, SUPPORT_GRANT, UNKNOWN }

enum class RestrictionType { BILLING_DEBT, MAINTENANCE, ADMIN_BLOCKED, TERMS_VIOLATION, UNKNOWN }

enum class SupportMode { READ_ONLY_SUPPORT, ENGINEERING_SUPPORT, UNKNOWN }

enum class SupportGrantStatus { ACTIVE, EXPIRED, REVOKED, UNKNOWN }

sealed interface SupportGrantRuntimeState {
    data object Active : SupportGrantRuntimeState

    data object ExpiredLocally : SupportGrantRuntimeState
}

data class AccessRestriction(
    val type: RestrictionType,
    val reason: String?,
    val restrictedAt: Instant?,
    val untilAt: Instant?,
    val supportContact: String?,
)

data class CompanyBrief(
    val id: String,
    val name: String,
    val status: CompanyStatus,
)

data class GlobalUserContext(
    val userId: String,
    val serverTime: Instant,
    val globalPermissions: Set<GlobalPermission>,
    val defaultCompanyId: String?,
    val canBrowseAllCompanies: Boolean,
    val memberCompanies: List<CompanyBrief>,
) {
    val hasMultipleCompanies: Boolean
        get() = canBrowseAllCompanies || memberCompanies.size > 1
}

data class SupportGrantInfo(
    val grantId: String,
    val status: SupportGrantStatus,
    val mode: SupportMode,
    val ticketReference: String?,
    val allowBillingOverride: Boolean,
    val grantedByUserId: String?,
    val grantedAt: Instant?,
    val expiresAt: Instant?,
    val remainingTtlSec: Long,
    val revokedAt: Instant?,
    val fetchedAtMark: TimeMark = TimeSource.Monotonic.markNow(),
    val runtimeState: SupportGrantRuntimeState = SupportGrantRuntimeState.Active,
) {
    fun remainingDurationNow(): Duration {
        if (runtimeState == SupportGrantRuntimeState.ExpiredLocally ||
            status != SupportGrantStatus.ACTIVE ||
            remainingTtlSec <= 0L
        ) {
            return Duration.ZERO
        }
        return (remainingTtlSec.seconds - fetchedAtMark.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    fun isExpiredNow(): Boolean = remainingDurationNow() == Duration.ZERO
}

data class CompanyAccessDomain(
    val companyId: String,
    val companyName: String,
    val serverTime: Instant,
    val revision: String,
    val companyStatus: CompanyStatus,
    val accessStatus: AccessStatus,
    val activeOrigins: Set<AccessOrigin>,
    val restriction: AccessRestriction?,
    val tenantPermissions: Set<TenantPermission>,
    val supportPermissions: Set<SupportPermission>,
    val permissionSources: Map<PermissionKey, Set<AccessOrigin>>,
    val supportGrant: SupportGrantInfo?,
)

data class AuthorizedTenantContext(
    val userId: String,
    val companyId: String,
    val companyName: String,
    val tenantRevision: Long,
    val accessRevision: String,
    val companyStatus: CompanyStatus,
    val accessStatus: AccessStatus,
    val activeOrigins: Set<AccessOrigin>,
    private val rawTenantPermissions: Set<TenantPermission>,
    private val rawSupportPermissions: Set<SupportPermission>,
    val permissionSources: Map<PermissionKey, Set<AccessOrigin>>,
    val supportGrant: SupportGrantInfo?,
    val restriction: AccessRestriction? = null,
) {
    val isSupportGrantLocallyExpired: Boolean
        get() =
            supportGrant?.let {
                it.runtimeState == SupportGrantRuntimeState.ExpiredLocally || it.isExpiredNow()
            } ?: false

    val requiresImmediateRevalidation: Boolean
        get() =
            isSupportGrantLocallyExpired &&
                (
                    accessStatus == AccessStatus.ACTIVE_SUPPORT_OVERRIDE ||
                        activeOrigins.none { it != AccessOrigin.SUPPORT_GRANT && it != AccessOrigin.UNKNOWN }
                )

    val effectiveSupportPermissions: Set<SupportPermission>
        get() = if (isSupportGrantLocallyExpired) emptySet() else rawSupportPermissions

    val effectiveTenantPermissions: Set<TenantPermission>
        get() {
            if (!isSupportGrantLocallyExpired) return rawTenantPermissions
            return rawTenantPermissions.filterTo(LinkedHashSet()) { permission ->
                permissionSources[PermissionKey.Tenant(permission)]?.any {
                    it != AccessOrigin.SUPPORT_GRANT && it != AccessOrigin.UNKNOWN
                } == true
            }
        }

    fun withSupportGrantExpiredLocally(): AuthorizedTenantContext {
        val grant = supportGrant ?: return this
        if (grant.runtimeState == SupportGrantRuntimeState.ExpiredLocally) return this
        return copy(supportGrant = grant.copy(runtimeState = SupportGrantRuntimeState.ExpiredLocally))
    }
}
