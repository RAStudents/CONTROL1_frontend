package studio.acmenra.control.core.network.auth

interface TokenStorage {
    fun getAccessToken(): String?

    fun getRefreshToken(): String?

    fun saveTokens(
        accessToken: String,
        refreshToken: String,
    )

    fun clear()

    fun hasTokens(): Boolean = !getAccessToken().isNullOrBlank() && !getRefreshToken().isNullOrBlank()
}
