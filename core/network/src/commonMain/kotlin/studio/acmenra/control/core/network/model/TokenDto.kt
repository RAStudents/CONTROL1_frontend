package studio.acmenra.control.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenPair(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
) {
    fun isValid(): Boolean = accessToken.isNotBlank() && refreshToken.isNotBlank()

    override fun toString(): String = "TokenPair(accessToken=REDACTED, refreshToken=REDACTED)"
}

@Serializable
data class RefreshTokenRequest(
    @SerialName("refresh_token")
    val refreshToken: String,
)

@Serializable
data class RefreshTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("token_type")
    val tokenType: String? = "Bearer",
)
