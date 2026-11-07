package studio.acmenra.control.core.network.auth

import com.russhwolf.settings.Settings

class SettingsTokenStorage(
    private val settings: Settings = Settings(),
) : TokenStorage {
    override fun getAccessToken(): String? = settings.getStringOrNull(KEY_ACCESS_TOKEN)

    override fun getRefreshToken(): String? = settings.getStringOrNull(KEY_REFRESH_TOKEN)

    override fun saveTokens(
        accessToken: String,
        refreshToken: String,
    ) {
        settings.putString(KEY_ACCESS_TOKEN, accessToken)
        settings.putString(KEY_REFRESH_TOKEN, refreshToken)
    }

    override fun clear() {
        settings.remove(KEY_ACCESS_TOKEN)
        settings.remove(KEY_REFRESH_TOKEN)
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "auth_access_token"
        private const val KEY_REFRESH_TOKEN = "auth_refresh_token"
    }
}
