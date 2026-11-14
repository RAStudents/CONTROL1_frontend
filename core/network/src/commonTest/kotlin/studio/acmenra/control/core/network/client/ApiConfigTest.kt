package studio.acmenra.control.core.network.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiConfigTest {
    @Test
    fun testValidHttpsUrlNormalizedWithTrailingSlash() {
        val config = ApiConfig(apiBaseUrl = "https://api.example.com")
        assertEquals("https://api.example.com/", config.apiBaseUrl)
    }

    @Test
    fun testValidHttpUrlAcceptedWhenAllowInsecureTransportTrue() {
        val config =
            ApiConfig(
                apiBaseUrl = "http://localhost:8080/api",
                allowInsecureTransport = true,
            )
        assertEquals("http://localhost:8080/api/", config.apiBaseUrl)
    }

    @Test
    fun testHttpUrlRejectedWhenAllowInsecureTransportFalse() {
        assertFailsWith<IllegalArgumentException> {
            ApiConfig(
                apiBaseUrl = "http://api.example.com",
                allowInsecureTransport = false,
            )
        }
    }

    @Test
    fun testBlankUrlRejected() {
        assertFailsWith<IllegalArgumentException> {
            ApiConfig(apiBaseUrl = "   ")
        }
    }

    @Test
    fun testUrlWithoutProtocolRejected() {
        assertFailsWith<IllegalArgumentException> {
            ApiConfig(apiBaseUrl = "api.example.com")
        }
    }

    @Test
    fun testUrlWithoutHostRejected() {
        assertFailsWith<IllegalArgumentException> {
            ApiConfig(apiBaseUrl = "https://")
        }
    }

    @Test
    fun testUrlWithQueryRejected() {
        assertFailsWith<IllegalArgumentException> {
            ApiConfig(apiBaseUrl = "https://api.example.com/api?version=1")
        }
    }

    @Test
    fun testUrlWithFragmentRejected() {
        assertFailsWith<IllegalArgumentException> {
            ApiConfig(apiBaseUrl = "https://api.example.com/api#section")
        }
    }

    @Test
    fun testUrlWithUserCredentialsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ApiConfig(apiBaseUrl = "https://user:password@api.example.com")
        }
    }

    @Test
    fun testCopyValidatesNewArguments() {
        val config = ApiConfig(apiBaseUrl = "https://api.example.com")
        assertFailsWith<IllegalArgumentException> {
            config.copy(apiBaseUrl = "http://insecure.example.com")
        }
    }
}
