package studio.acmenra.control.core.network.auth

import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json
import studio.acmenra.control.core.network.model.TokenPair

class SettingsTokenStorage(
    private val settings: Settings = Settings(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStorage {
    override fun getTokens(): TokenPair? {
        val rawJson = settings.getStringOrNull(KEY_TOKEN_PAIR)
        if (!rawJson.isNullOrBlank()) {
            return runCatching {
                val pair = json.decodeFromString<TokenPair>(rawJson)
                if (pair.isValid()) pair else null
            }.getOrNull()
        }

        // Migration from legacy individual keys
        val legacyAccess = settings.getStringOrNull(LEGACY_KEY_ACCESS_TOKEN)
        val legacyRefresh = settings.getStringOrNull(LEGACY_KEY_REFRESH_TOKEN)
        if (!legacyAccess.isNullOrBlank() && !legacyRefresh.isNullOrBlank()) {
            val migratedPair = TokenPair(accessToken = legacyAccess, refreshToken = legacyRefresh)
            if (migratedPair.isValid()) {
                saveTokens(migratedPair)
                settings.remove(LEGACY_KEY_ACCESS_TOKEN)
                settings.remove(LEGACY_KEY_REFRESH_TOKEN)
                return migratedPair
            }
        }

        return null
    }

    override fun saveTokens(tokens: TokenPair) {
        require(tokens.isValid()) { "Cannot save invalid token pair" }
        val serialized = json.encodeToString(TokenPair.serializer(), tokens)
        settings.putString(KEY_TOKEN_PAIR, serialized)
    }

    override fun clear() {
        settings.remove(KEY_TOKEN_PAIR)
        settings.remove(LEGACY_KEY_ACCESS_TOKEN)
        settings.remove(LEGACY_KEY_REFRESH_TOKEN)
    }

    companion object {
        private const val KEY_TOKEN_PAIR = "auth_token_pair"
        private const val LEGACY_KEY_ACCESS_TOKEN = "auth_access_token"
        private const val LEGACY_KEY_REFRESH_TOKEN = "auth_refresh_token"
    }
}
