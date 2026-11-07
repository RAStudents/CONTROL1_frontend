package studio.acmenra.control.core.network.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.core.network.model.TokenPair

class SessionManager(
    private val tokenStorage: TokenStorage,
    private val clientRegistry: AuthClientRegistry? = null,
) {
    private val mutex = Mutex()
    private var currentRevision: Long = 0L

    private val _sessionState =
        MutableStateFlow<SessionState>(
            if (tokenStorage.hasTokens()) {
                SessionState.Authenticated
            } else {
                SessionState.Unauthenticated
            },
        )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    suspend fun getSnapshot(): SessionSnapshot? =
        mutex.withLock {
            val tokens = tokenStorage.getTokens() ?: return null
            if (!tokens.isValid()) return null
            SessionSnapshot(tokens = tokens, revision = currentRevision)
        }

    suspend fun saveSession(tokens: TokenPair): SessionSnapshot =
        mutex.withLock {
            require(tokens.isValid()) { "Tokens must not be blank" }
            tokenStorage.saveTokens(tokens)
            currentRevision++
            val snapshot = SessionSnapshot(tokens = tokens, revision = currentRevision)
            _sessionState.value = SessionState.Authenticated
            clientRegistry?.clearAllAuthTokens()
            snapshot
        }

    suspend fun saveRefreshedSession(
        expected: SessionSnapshot,
        newTokens: TokenPair,
    ): Boolean =
        mutex.withLock {
            require(newTokens.isValid()) { "New refreshed tokens must not be blank" }
            if (_sessionState.value !is SessionState.Authenticated) {
                return false
            }
            if (currentRevision != expected.revision) {
                return false
            }
            val currentTokens = tokenStorage.getTokens()
            if (currentTokens != expected.tokens) {
                return false
            }

            tokenStorage.saveTokens(newTokens)
            currentRevision++
            _sessionState.value = SessionState.Authenticated
            true
        }

    suspend fun expireSessionIfMatches(expected: SessionSnapshot): Boolean =
        mutex.withLock {
            if (currentRevision != expected.revision) {
                return false
            }
            val currentTokens = tokenStorage.getTokens()
            if (currentTokens != expected.tokens) {
                return false
            }

            tokenStorage.clear()
            currentRevision++
            _sessionState.value = SessionState.SessionExpired
            clientRegistry?.clearAllAuthTokens()
            true
        }

    suspend fun logout(): Unit =
        mutex.withLock {
            tokenStorage.clear()
            currentRevision++
            _sessionState.value = SessionState.Unauthenticated
            clientRegistry?.clearAllAuthTokens()
        }
}
