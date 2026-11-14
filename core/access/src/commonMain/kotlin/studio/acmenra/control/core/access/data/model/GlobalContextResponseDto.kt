package studio.acmenra.control.core.access.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import studio.acmenra.control.core.access.data.serializer.SafeGlobalPermissionSetSerializer
import studio.acmenra.control.core.access.domain.model.GlobalPermission

@Serializable
data class GlobalContextResponseDto(
    @SerialName("user_id")
    val userId: String,
    @SerialName("server_time")
    val serverTime: String,
    @SerialName("global_permissions")
    @Serializable(with = SafeGlobalPermissionSetSerializer::class)
    val globalPermissions: Set<GlobalPermission> = emptySet(),
    @SerialName("default_company_id")
    val defaultCompanyId: String? = null,
    @SerialName("can_browse_all_companies")
    val canBrowseAllCompanies: Boolean = false,
    @SerialName("member_companies")
    val memberCompanies: List<CompanyBriefDto> = emptyList(),
)
