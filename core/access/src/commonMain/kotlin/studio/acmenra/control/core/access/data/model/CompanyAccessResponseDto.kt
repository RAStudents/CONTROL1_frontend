package studio.acmenra.control.core.access.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import studio.acmenra.control.core.access.data.serializer.PermissionSourcesSerializer
import studio.acmenra.control.core.access.data.serializer.SafeAccessOriginSetSerializer
import studio.acmenra.control.core.access.data.serializer.SafeAccessStatusSerializer
import studio.acmenra.control.core.access.data.serializer.SafeCompanyStatusSerializer
import studio.acmenra.control.core.access.data.serializer.SafeSupportPermissionSetSerializer
import studio.acmenra.control.core.access.data.serializer.SafeTenantPermissionSetSerializer
import studio.acmenra.control.core.access.domain.model.AccessOrigin
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.PermissionKey
import studio.acmenra.control.core.access.domain.model.SupportPermission
import studio.acmenra.control.core.access.domain.model.TenantPermission

@Serializable
data class CompanyAccessResponseDto(
    @SerialName("company_id")
    val companyId: String,
    @SerialName("company_name")
    val companyName: String,
    @SerialName("server_time")
    val serverTime: String,
    @SerialName("revision")
    val revision: String,
    @SerialName("company_status")
    @Serializable(with = SafeCompanyStatusSerializer::class)
    val companyStatus: CompanyStatus = CompanyStatus.UNKNOWN,
    @SerialName("access_status")
    @Serializable(with = SafeAccessStatusSerializer::class)
    val accessStatus: AccessStatus = AccessStatus.UNKNOWN,
    @SerialName("active_origins")
    @Serializable(with = SafeAccessOriginSetSerializer::class)
    val activeOrigins: Set<AccessOrigin> = emptySet(),
    @SerialName("restriction")
    val restriction: AccessRestrictionDto? = null,
    @SerialName("tenant_permissions")
    @Serializable(with = SafeTenantPermissionSetSerializer::class)
    val tenantPermissions: Set<TenantPermission> = emptySet(),
    @SerialName("support_permissions")
    @Serializable(with = SafeSupportPermissionSetSerializer::class)
    val supportPermissions: Set<SupportPermission> = emptySet(),
    @SerialName("permission_sources")
    @Serializable(with = PermissionSourcesSerializer::class)
    val permissionSources: Map<PermissionKey, Set<AccessOrigin>> = emptyMap(),
    @SerialName("support_grant")
    val supportGrant: SupportGrantDto? = null,
)
