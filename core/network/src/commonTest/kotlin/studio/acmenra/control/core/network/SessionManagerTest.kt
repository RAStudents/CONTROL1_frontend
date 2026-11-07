package studio.acmenra.control.core.network

import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.fold
import studio.acmenra.control.core.network.model.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeTokenStorage : TokenStorage {
    private var access: String? = null
    private var refresh: String? = null

    override fun getAccessToken(): String? = access

    override fun getRefreshToken(): String? = refresh

    override fun saveTokens(
        accessToken: String,
        refreshToken: String,
    ) {
        access = accessToken
        refresh = refreshToken
    }

    override fun clear() {
        access = null
        refresh = null
    }
}

class SessionManagerTest {
    @Test
    fun testInitialStateUnauthenticated() {
        val storage = FakeTokenStorage()
        val manager = SessionManager(storage)
        assertEquals(SessionState.Unauthenticated, manager.sessionState.value)
    }

    @Test
    fun testSaveSessionUpdatesState() {
        val storage = FakeTokenStorage()
        val manager = SessionManager(storage)

        manager.saveSession("access_123", "refresh_456")

        assertEquals("access_123", storage.getAccessToken())
        assertEquals("refresh_456", storage.getRefreshToken())
        assertEquals(SessionState.Authenticated("access_123"), manager.sessionState.value)
    }

    @Test
    fun testNotifySessionExpiredClearsTokensAndEmitsExpired() {
        val storage = FakeTokenStorage()
        val manager = SessionManager(storage)

        manager.saveSession("access_123", "refresh_456")
        manager.notifySessionExpired()

        assertEquals(null, storage.getAccessToken())
        assertEquals(null, storage.getRefreshToken())
        assertEquals(SessionState.SessionExpired, manager.sessionState.value)
    }

    @Test
    fun testLogoutClearsTokensAndEmitsUnauthenticated() {
        val storage = FakeTokenStorage()
        val manager = SessionManager(storage)

        manager.saveSession("access_123", "refresh_456")
        manager.logout()

        assertEquals(null, storage.getAccessToken())
        assertEquals(SessionState.Unauthenticated, manager.sessionState.value)
    }

    @Test
    fun testNetworkResultMapAndFold() {
        val success: NetworkResult<Int> = NetworkResult.Success(42)
        val mapped = success.map { it * 2 }
        assertTrue(mapped.isSuccess)
        val value =
            mapped.fold(
                onSuccess = { data, _ -> data },
                onFailure = { -1 },
            )
        assertEquals(84, value)

        val failure: NetworkResult<Int> = NetworkResult.Failure(NetworkError.Http(404, "Not Found"))
        assertFalse(failure.isSuccess)
        val failValue =
            failure.fold(
                onSuccess = { data, _ -> data },
                onFailure = { (it as NetworkError.Http).statusCode },
            )
        assertEquals(404, failValue)
    }
}
