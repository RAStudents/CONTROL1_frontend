package studio.acmenra.control.core.network

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.auth.SettingsTokenStorage
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.TokenPair
import studio.acmenra.control.core.network.model.fold
import studio.acmenra.control.core.network.model.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeTokenStorage : TokenStorage {
    private var tokenPair: TokenPair? = null

    override fun getTokens(): TokenPair? = tokenPair

    override fun saveTokens(tokens: TokenPair) {
        tokenPair = tokens
    }

    override fun clear() {
        tokenPair = null
    }
}

class SessionManagerTest {
    @Test
    fun testInitialStateUnauthenticatedWhenEmpty() {
        val storage = FakeTokenStorage()
        val manager = SessionManager(storage)
        assertEquals(SessionState.Unauthenticated, manager.sessionState.value)
    }

    @Test
    fun testInitialStateAuthenticatedWhenStorageHasTokens() {
        val storage = FakeTokenStorage()
        storage.saveTokens(TokenPair(accessToken = "acc", refreshToken = "ref"))
        val manager = SessionManager(storage)
        assertEquals(SessionState.Authenticated, manager.sessionState.value)
    }

    @Test
    fun testSaveSessionUpdatesStateAndRevision() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)

            val snapshot = manager.saveSession(TokenPair("access_1", "refresh_1"))

            assertEquals(SessionState.Authenticated, manager.sessionState.value)
            assertEquals("access_1", storage.getTokens()?.accessToken)
            assertEquals("refresh_1", storage.getTokens()?.refreshToken)
            assertEquals(1L, snapshot.revision)
        }

    @Test
    fun testSaveSessionRejectsBlankTokens() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)

            assertFailsWith<IllegalArgumentException> {
                manager.saveSession(TokenPair("", "refresh"))
            }
            assertFailsWith<IllegalArgumentException> {
                manager.saveSession(TokenPair("access", ""))
            }
        }

    @Test
    fun testSaveRefreshedSessionSucceedsWithMatchingSnapshot() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)

            val initialSnapshot = manager.saveSession(TokenPair("access_1", "refresh_1"))

            val saved =
                manager.saveRefreshedSession(
                    expected = initialSnapshot,
                    newTokens = TokenPair("access_2", "refresh_2"),
                )

            assertTrue(saved)
            assertEquals("access_2", storage.getTokens()?.accessToken)
            assertEquals("refresh_2", storage.getTokens()?.refreshToken)
            assertEquals(SessionState.Authenticated, manager.sessionState.value)
        }

    @Test
    fun testSaveRefreshedSessionRejectedIfLoggedOut() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)

            val snapshot = manager.saveSession(TokenPair("access_1", "refresh_1"))
            manager.logout()

            val saved =
                manager.saveRefreshedSession(
                    expected = snapshot,
                    newTokens = TokenPair("access_2", "refresh_2"),
                )

            assertFalse(saved)
            assertNull(storage.getTokens())
            assertEquals(SessionState.Unauthenticated, manager.sessionState.value)
        }

    @Test
    fun testSaveRefreshedSessionRejectedIfSwitchedToNewUser() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)

            val userASnapshot = manager.saveSession(TokenPair("access_A", "refresh_A"))
            manager.saveSession(TokenPair("access_B", "refresh_B"))

            val saved =
                manager.saveRefreshedSession(
                    expected = userASnapshot,
                    newTokens = TokenPair("access_A2", "refresh_A2"),
                )

            assertFalse(saved)
            assertEquals("access_B", storage.getTokens()?.accessToken)
            assertEquals("refresh_B", storage.getTokens()?.refreshToken)
            assertEquals(SessionState.Authenticated, manager.sessionState.value)
        }

    @Test
    fun testExpireSessionIfMatchesClearsOnlyMatchingSession() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)

            val snapshot = manager.saveSession(TokenPair("access_1", "refresh_1"))
            val expired = manager.expireSessionIfMatches(snapshot)

            assertTrue(expired)
            assertNull(storage.getTokens())
            assertEquals(SessionState.SessionExpired, manager.sessionState.value)
        }

    @Test
    fun testExpireSessionDoesNotClearNewUserSession() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)

            val userASnapshot = manager.saveSession(TokenPair("access_A", "refresh_A"))
            // User B logs in before User A's failed refresh returns
            manager.saveSession(TokenPair("access_B", "refresh_B"))

            val expired = manager.expireSessionIfMatches(userASnapshot)

            assertFalse(expired)
            assertNotNull(storage.getTokens())
            assertEquals("access_B", storage.getTokens()?.accessToken)
            assertEquals(SessionState.Authenticated, manager.sessionState.value)
        }

    @Test
    fun testSettingsTokenStorageMigration() {
        val settings = MapSettings()
        settings.putString("auth_access_token", "legacy_acc")
        settings.putString("auth_refresh_token", "legacy_ref")

        val storage = SettingsTokenStorage(settings = settings)
        val tokens = storage.getTokens()

        assertNotNull(tokens)
        assertEquals("legacy_acc", tokens.accessToken)
        assertEquals("legacy_ref", tokens.refreshToken)

        // Legacy keys should be removed after migration
        assertEquals(null, settings.getStringOrNull("auth_access_token"))
        assertEquals(null, settings.getStringOrNull("auth_refresh_token"))
        // New single key should exist
        assertNotNull(settings.getStringOrNull("auth_token_pair"))
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
