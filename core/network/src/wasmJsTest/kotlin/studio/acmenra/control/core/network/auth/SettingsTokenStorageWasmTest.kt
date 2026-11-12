package studio.acmenra.control.core.network.auth

import studio.acmenra.control.core.network.model.TokenPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsTokenStorageWasmTest {
    @Test
    fun testSettingsTokenStoragePersistenceInWasmBrowser() {
        val storage1 = SettingsTokenStorage()
        storage1.clear()
        assertNull(storage1.getTokens())

        val testTokens =
            TokenPair(
                accessToken = "test_access_token_wasm_persistence",
                refreshToken = "test_refresh_token_wasm_persistence",
            )
        storage1.saveTokens(testTokens)
        assertEquals(testTokens, storage1.getTokens())

        // Simulating reload / new instance lifecycle
        val storage2 = SettingsTokenStorage()
        assertEquals(testTokens, storage2.getTokens())

        storage2.clear()
        assertNull(storage2.getTokens())

        val storage3 = SettingsTokenStorage()
        assertNull(storage3.getTokens())
    }
}
