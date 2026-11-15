package studio.acmenra.control.core.access.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import studio.acmenra.control.core.access.data.serializer.SafeRestrictionTypeSerializer
import studio.acmenra.control.core.access.domain.model.RestrictionType

@Serializable
data class AccessRestrictionDto(
    @SerialName("type")
    @Serializable(with = SafeRestrictionTypeSerializer::class)
    val type: RestrictionType = RestrictionType.UNKNOWN,
    @SerialName("reason")
    val reason: String? = null,
    @SerialName("restricted_at")
    val restrictedAt: String? = null,
    @SerialName("until_at")
    val untilAt: String? = null,
    @SerialName("support_contact")
    val supportContact: String? = null,
)
