package studio.acmenra.control.core.access.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import studio.acmenra.control.core.access.data.serializer.SafeCompanyStatusSerializer
import studio.acmenra.control.core.access.domain.model.CompanyStatus

@Serializable
data class CompanyBriefDto(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("status")
    @Serializable(with = SafeCompanyStatusSerializer::class)
    val status: CompanyStatus = CompanyStatus.UNKNOWN,
)
