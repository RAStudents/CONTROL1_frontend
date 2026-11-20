package studio.acmenra.control.core.network.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.acmenra.control.core.network.model.TokenPair

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class SessionManager(
    private val tokenStorage: TokenStorage,
) {
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private var inFlightRefresh: CompletableDeferred<SessionSnapshot?>? = null
    private var inFlightRefreshToken: String? = null

    @kotlin.concurrent.Volatile
    private var currentRevision: Long = 0L

    private val refreshDelegate =
        kotlin.concurrent.atomics.AtomicReference<(suspend (String?) -> SessionSnapshot?)?>(null)

    fun setRefreshDelegate(delegate: (suspend (String?) -> SessionSnapshot?)?) {
        refreshDelegate.store(delegate)
    }

    fun clearRefreshDelegate(expected: suspend (String?) -> SessionSnapshot?) {
        refreshDelegate.compareAndSet(expected, null)
    }

    suspend fun refreshActiveSession(failedAccessToken: String? = null): SessionSnapshot? {
        val delegate = refreshDelegate.load()
        return delegate?.invoke(failedAccessToken) ?: getSnapshot()
    }

    fun currentRevision(): Long = currentRevision

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
            snapshot
        }

    suspend fun saveRefreshedSession(
        expected: SessionSnapshot,
        newTokens: TokenPair,
    ): SessionSnapshot? =
        mutex.withLock {
            require(newTokens.isValid()) { "New refreshed tokens must not be blank" }
            if (_sessionState.value !is SessionState.Authenticated) {
                return null
            }
            if (currentRevision != expected.revision) {
                return null
            }
            val currentTokens = tokenStorage.getTokens()
            if (currentTokens != expected.tokens) {
                return null
            }

            tokenStorage.saveTokens(newTokens)
            currentRevision++
            _sessionState.value = SessionState.Authenticated
            SessionSnapshot(tokens = newTokens, revision = currentRevision)
        }

    suspend fun refreshSession(
        failedAccessToken: String?,
        refresh: suspend (SessionSnapshot) -> TokenPair?,
    ): SessionSnapshot? {
        val (deferred, isLeader) =
            refreshMutex.withLock {
                val current = getSnapshot() ?: return null
                if (failedAccessToken != null && current.tokens.accessToken != failedAccessToken) {
                    return current
                }
                val active = inFlightRefresh
                val activeToken = inFlightRefreshToken
                val targetToken = failedAccessToken ?: current.tokens.accessToken
                if (active != null && activeToken == targetToken) {
                    active to false
                } else {
                    val newDeferred = CompletableDeferred<SessionSnapshot?>()
                    inFlightRefresh = newDeferred
                    inFlightRefreshToken = targetToken
                    newDeferred to true
                }
            }

        if (!isLeader) {
            return deferred.await()
        }

        try {
            val current = getSnapshot()
            if (current == null) {
                deferred.complete(null)
                return null
            }
            if (failedAccessToken != null && current.tokens.accessToken != failedAccessToken) {
                deferred.complete(current)
                return current
            }
            val newTokens = refresh(current)
            val result =
                if (newTokens == null) {
                    expireSessionIfMatches(current)
                    null
                } else {
                    saveRefreshedSession(expected = current, newTokens = newTokens)
                }
            deferred.complete(result)
            return result
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            refreshMutex.withLock {
                if (inFlightRefresh === deferred) {
                    inFlightRefresh = null
                    inFlightRefreshToken = null
                }
            }
        }
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
            true
        }

    suspend fun logout(): Unit =
        mutex.withLock {
            tokenStorage.clear()
            currentRevision++
            _sessionState.value = SessionState.Unauthenticated
        }
}
