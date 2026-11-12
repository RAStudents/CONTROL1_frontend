package studio.acmenra.control.core.network.auth

import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json
import studio.acmenra.control.core.network.model.TokenPair

/**
 * Insecure token storage implementation backed by [Settings].
 *
 * Warning: Tokens are stored in plaintext (e.g. SharedPreferences on Android, localStorage on Web
 * where it is vulnerable to XSS). Do NOT use this as a secure token storage in production.
 *
 * TODO: Implement secure platform-specific [TokenStorage] implementations:
 *   - Android: Android Keystore / EncryptedSharedPreferences
 *   - iOS: Keychain Services
 *   - Web: Secure HttpOnly cookie session or a dedicated in-memory / WebCrypto mechanism
 */
class SettingsTokenStorage(
    private val settings: Settings = Settings(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStorage {
    override fun getTokens(): TokenPair? {
        val rawJson = settings.getStringOrNull(KEY_TOKEN_PAIR)
        if (!rawJson.isNullOrBlank()) {
            val parsedPair =
                runCatching {
                    val pair = json.decodeFromString<TokenPair>(rawJson)
                    if (pair.isValid()) pair else null
                }.getOrNull()
            if (parsedPair != null) {
                return parsedPair
            }
            settings.remove(KEY_TOKEN_PAIR)
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
