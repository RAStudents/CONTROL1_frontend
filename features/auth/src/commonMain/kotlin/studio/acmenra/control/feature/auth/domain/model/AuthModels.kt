package studio.acmenra.control.feature.auth.domain.model

data class LoginCredentials(
    val email: String,
    val password: String,
)

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
)
