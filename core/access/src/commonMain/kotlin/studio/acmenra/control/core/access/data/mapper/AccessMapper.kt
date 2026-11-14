package studio.acmenra.control.core.access.data.mapper

import studio.acmenra.control.core.access.data.model.AccessRestrictionDto
import studio.acmenra.control.core.access.data.model.CompanyAccessResponseDto
import studio.acmenra.control.core.access.data.model.CompanyBriefDto
import studio.acmenra.control.core.access.data.model.GlobalContextResponseDto
import studio.acmenra.control.core.access.data.model.SupportGrantDto
import studio.acmenra.control.core.access.domain.model.AccessRestriction
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.SupportGrantInfo
import studio.acmenra.control.core.access.domain.model.SupportGrantRuntimeState
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class AccessMapper(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    fun mapGlobalContext(dto: GlobalContextResponseDto): GlobalUserContext =
        GlobalUserContext(
            userId = dto.userId,
            serverTime = parseServerTime(dto.serverTime),
            globalPermissions = dto.globalPermissions,
            defaultCompanyId = dto.defaultCompanyId,
            canBrowseAllCompanies = dto.canBrowseAllCompanies,
            memberCompanies = dto.memberCompanies.map(::mapCompanyBrief),
        )

    fun mapCompanyAccess(
        dto: CompanyAccessResponseDto,
        fetchedAtMark: TimeMark = timeSource.markNow(),
    ): CompanyAccessDomain =
        CompanyAccessDomain(
            companyId = dto.companyId,
            companyName = dto.companyName,
            serverTime = parseServerTime(dto.serverTime),
            revision = dto.revision,
            companyStatus = dto.companyStatus,
            accessStatus = dto.accessStatus,
            activeOrigins = dto.activeOrigins,
            restriction = dto.restriction?.let(::mapRestriction),
            tenantPermissions = dto.tenantPermissions,
            supportPermissions = dto.supportPermissions,
            permissionSources = dto.permissionSources,
            supportGrant = dto.supportGrant?.let { mapGrant(it, fetchedAtMark) },
        )

    fun mapAuthorizedTenantContext(
        userId: String,
        domain: CompanyAccessDomain,
        tenantRevision: Long = 1L,
    ): AuthorizedTenantContext =
        AuthorizedTenantContext(
            userId = userId,
            companyId = domain.companyId,
            companyName = domain.companyName,
            tenantRevision = tenantRevision,
            accessRevision = domain.revision,
            companyStatus = domain.companyStatus,
            accessStatus = domain.accessStatus,
            activeOrigins = domain.activeOrigins,
            rawTenantPermissions = domain.tenantPermissions,
            rawSupportPermissions = domain.supportPermissions,
            permissionSources = domain.permissionSources,
            supportGrant = domain.supportGrant,
        )

    private fun mapCompanyBrief(dto: CompanyBriefDto): CompanyBrief =
        CompanyBrief(
            id = dto.id,
            name = dto.name,
            status = dto.status,
        )

    private fun mapRestriction(dto: AccessRestrictionDto): AccessRestriction =
        AccessRestriction(
            type = dto.type,
            reason = dto.reason,
            restrictedAt = parseInstantSafely(dto.restrictedAt),
            untilAt = parseInstantSafely(dto.untilAt),
            supportContact = dto.supportContact,
        )

    private fun mapGrant(
        dto: SupportGrantDto,
        fetchedAtMark: TimeMark,
    ): SupportGrantInfo =
        SupportGrantInfo(
            grantId = dto.grantId,
            status = dto.status,
            mode = dto.mode,
            ticketReference = dto.ticketReference,
            allowBillingOverride = dto.allowBillingOverride,
            grantedByUserId = dto.grantedByUserId,
            grantedAt = parseInstantSafely(dto.grantedAt),
            expiresAt = parseInstantSafely(dto.expiresAt),
            remainingTtlSec = dto.remainingTtlSec,
            revokedAt = parseInstantSafely(dto.revokedAt),
            fetchedAtMark = fetchedAtMark,
            runtimeState = SupportGrantRuntimeState.Active,
        )

    private fun parseInstantSafely(raw: String?): Instant? =
        if (raw.isNullOrBlank()) null else runCatching { Instant.parse(raw) }.getOrNull()

    private fun parseServerTime(raw: String): Instant =
        runCatching { Instant.parse(raw) }.getOrElse { Instant.fromEpochMilliseconds(0) }
}
