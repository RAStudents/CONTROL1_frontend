package studio.acmenra.control.core.access.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import studio.acmenra.control.core.access.data.serializer.SafeSupportGrantStatusSerializer
import studio.acmenra.control.core.access.data.serializer.SafeSupportModeSerializer
import studio.acmenra.control.core.access.domain.model.SupportGrantStatus
import studio.acmenra.control.core.access.domain.model.SupportMode

@Serializable
data class SupportGrantDto(
    @SerialName("grant_id")
    val grantId: String,
    @SerialName("status")
    @Serializable(with = SafeSupportGrantStatusSerializer::class)
    val status: SupportGrantStatus = SupportGrantStatus.UNKNOWN,
    @SerialName("mode")
    @Serializable(with = SafeSupportModeSerializer::class)
    val mode: SupportMode = SupportMode.UNKNOWN,
    @SerialName("ticket_reference")
    val ticketReference: String? = null,
    @SerialName("allow_billing_override")
    val allowBillingOverride: Boolean = false,
    @SerialName("granted_by_user_id")
    val grantedByUserId: String? = null,
    @SerialName("granted_at")
    val grantedAt: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("remaining_ttl_sec")
    val remainingTtlSec: Long = 0L,
    @SerialName("revoked_at")
    val revokedAt: String? = null,
)
