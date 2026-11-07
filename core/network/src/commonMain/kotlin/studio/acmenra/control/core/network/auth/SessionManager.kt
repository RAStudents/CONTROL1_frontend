package studio.acmenra.control.core.network.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.clearAuthTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionManager(
    private val tokenStorage: TokenStorage,
) {
    private var httpClient: HttpClient? = null

    private val _sessionState =
        MutableStateFlow<SessionState>(
            if (tokenStorage.hasTokens()) {
                SessionState.Authenticated(tokenStorage.getAccessToken().orEmpty())
            } else {
                SessionState.Unauthenticated
            },
        )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    fun attachClient(client: HttpClient) {
        this.httpClient = client
    }

    fun saveSession(
        accessToken: String,
        refreshToken: String,
    ) {
        tokenStorage.saveTokens(accessToken, refreshToken)
        httpClient?.clearAuthTokens()
        _sessionState.value = SessionState.Authenticated(accessToken)
    }

    fun logout() {
        tokenStorage.clear()
        httpClient?.clearAuthTokens()
        _sessionState.value = SessionState.Unauthenticated
    }

    fun notifySessionExpired() {
        tokenStorage.clear()
        httpClient?.clearAuthTokens()
        _sessionState.value = SessionState.SessionExpired
    }
}
