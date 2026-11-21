package studio.acmenra.control.core.network

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.auth.SettingsTokenStorage
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.RefreshTokenRequest
import studio.acmenra.control.core.network.model.RefreshTokenResponse
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

            assertNotNull(saved)
            assertEquals("access_2", saved.tokens.accessToken)
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

            assertNull(saved)
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

            assertNull(saved)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testRefreshSessionSingleFlightConcurrentSuccess() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)
            manager.saveSession(TokenPair("access_1", "refresh_1"))

            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var refreshCallCount = 0

            val jobs =
                List(10) {
                    async {
                        manager.refreshSession(failedAccessToken = "access_1") {
                            refreshCallCount++
                            entered.complete(Unit)
                            gate.await()
                            TokenPair("access_2", "refresh_2")
                        }
                    }
                }

            entered.await()
            runCurrent()
            gate.complete(Unit)
            val results = jobs.awaitAll()

            assertEquals(1, refreshCallCount)
            assertEquals(10, results.size)
            results.forEach { snapshot ->
                assertNotNull(snapshot)
                assertEquals("access_2", snapshot.tokens.accessToken)
                assertEquals("refresh_2", snapshot.tokens.refreshToken)
            }
            assertEquals("access_2", storage.getTokens()?.accessToken)
            assertEquals("refresh_2", storage.getTokens()?.refreshToken)
            assertEquals(SessionState.Authenticated, manager.sessionState.value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testRefreshSessionReturnsNullExpiresSessionAndWaitingCallsGetNull() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)
            manager.saveSession(TokenPair("access_1", "refresh_1"))

            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var refreshCallCount = 0

            val jobs =
                List(10) {
                    async {
                        manager.refreshSession(failedAccessToken = "access_1") {
                            refreshCallCount++
                            entered.complete(Unit)
                            gate.await()
                            null
                        }
                    }
                }

            entered.await()
            runCurrent()
            gate.complete(Unit)
            val results = jobs.awaitAll()

            assertEquals(1, refreshCallCount)
            assertEquals(10, results.size)
            results.forEach { assertNull(it) }
            assertNull(storage.getTokens())
            assertEquals(SessionState.SessionExpired, manager.sessionState.value)
        }

    @Test
    fun testRefreshSessionOutdatedFailedAccessTokenReturnsCurrentSnapshotWithoutNetwork() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)
            manager.saveSession(TokenPair("access_current", "refresh_current"))

            var refreshCallCount = 0
            val snapshot =
                manager.refreshSession(failedAccessToken = "access_old") {
                    refreshCallCount++
                    TokenPair("access_new", "refresh_new")
                }

            assertEquals(0, refreshCallCount)
            assertNotNull(snapshot)
            assertEquals("access_current", snapshot.tokens.accessToken)
            assertEquals("access_current", storage.getTokens()?.accessToken)
            assertEquals(SessionState.Authenticated, manager.sessionState.value)
        }

    @Test
    fun testRefreshSessionExceptionPreservesStorageAndNextCallCanRetry() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)
            manager.saveSession(TokenPair("access_1", "refresh_1"))

            var refreshCallCount = 0
            assertFailsWith<IllegalStateException> {
                manager.refreshSession(failedAccessToken = "access_1") {
                    refreshCallCount++
                    throw IllegalStateException("Transient network failure")
                }
            }

            assertEquals(1, refreshCallCount)
            assertEquals("access_1", storage.getTokens()?.accessToken)
            assertEquals(SessionState.Authenticated, manager.sessionState.value)

            val nextSnapshot =
                manager.refreshSession(failedAccessToken = "access_1") {
                    refreshCallCount++
                    TokenPair("access_2", "refresh_2")
                }

            assertEquals(2, refreshCallCount)
            assertNotNull(nextSnapshot)
            assertEquals("access_2", nextSnapshot.tokens.accessToken)
            assertEquals("access_2", storage.getTokens()?.accessToken)
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
    fun testCorruptJsonInTokenPairWithoutLegacyKeysReturnsNullAndRemovesCorruptKey() {
        val settings = MapSettings()
        settings.putString("auth_token_pair", "{corrupted_json")

        val storage = SettingsTokenStorage(settings = settings)
        val tokens = storage.getTokens()

        assertNull(tokens)
        assertNull(settings.getStringOrNull("auth_token_pair"))
    }

    @Test
    fun testCorruptJsonInTokenPairWithLegacyKeysMigratesAndReplacesCorruptKey() {
        val settings = MapSettings()
        settings.putString("auth_token_pair", "{corrupted_json")
        settings.putString("auth_access_token", "legacy_acc_1")
        settings.putString("auth_refresh_token", "legacy_ref_1")

        val storage = SettingsTokenStorage(settings = settings)
        val tokens = storage.getTokens()

        assertNotNull(tokens)
        assertEquals("legacy_acc_1", tokens.accessToken)
        assertEquals("legacy_ref_1", tokens.refreshToken)

        assertNull(settings.getStringOrNull("auth_access_token"))
        assertNull(settings.getStringOrNull("auth_refresh_token"))

        val newRaw = settings.getStringOrNull("auth_token_pair")
        assertNotNull(newRaw)
        assertTrue(newRaw.contains("legacy_acc_1"))
        assertTrue(newRaw.contains("legacy_ref_1"))
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

    @Test
    fun testTokenDtoToStringRedactsTokens() {
        val req = RefreshTokenRequest(refreshToken = "super_secret_refresh_123")
        assertFalse(req.toString().contains("super_secret_refresh_123"))
        assertTrue(req.toString().contains("REDACTED"))

        val resp =
            RefreshTokenResponse(
                accessToken = "super_secret_access_456",
                refreshToken = "super_secret_refresh_789",
            )
        assertFalse(resp.toString().contains("super_secret_access_456"))
        assertFalse(resp.toString().contains("super_secret_refresh_789"))
        assertTrue(resp.toString().contains("REDACTED"))
    }

    @Test
    fun testClearRefreshDelegateOnlyClearsExpectedDelegate() =
        runTest {
            val storage = FakeTokenStorage()
            val manager = SessionManager(storage)
            manager.saveSession(TokenPair("access_1", "refresh_1"))

            val delegateA: suspend (String?) -> studio.acmenra.control.core.network.auth.SessionSnapshot? = {
                studio.acmenra.control.core.network.auth
                    .SessionSnapshot(TokenPair("acc_a", "ref_a"), 10L)
            }
            val delegateB: suspend (String?) -> studio.acmenra.control.core.network.auth.SessionSnapshot? = {
                studio.acmenra.control.core.network.auth
                    .SessionSnapshot(TokenPair("acc_b", "ref_b"), 20L)
            }

            manager.setRefreshDelegate(delegateA)
            assertEquals("acc_a", manager.refreshActiveSession()?.tokens?.accessToken)

            // Client B sets delegate B
            manager.setRefreshDelegate(delegateB)
            assertEquals("acc_b", manager.refreshActiveSession()?.tokens?.accessToken)

            // Client A closes and attempts to clear with delegateA
            manager.clearRefreshDelegate(delegateA)
            // delegateB must still be active!
            assertEquals("acc_b", manager.refreshActiveSession()?.tokens?.accessToken)

            // Client B closes and clears with delegateB
            manager.clearRefreshDelegate(delegateB)
            // Now fallback to snapshot from storage
            assertEquals("access_1", manager.refreshActiveSession()?.tokens?.accessToken)
        }
}
