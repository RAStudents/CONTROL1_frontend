package studio.acmenra.control.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.client.HttpClientFactory
import studio.acmenra.control.core.network.client.safeRequest
import studio.acmenra.control.core.network.model.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class TestData(
    val status: String,
)

class HttpClientAuthTest {
    private val baseUrl = "https://api.example.com"

    private fun unauthorizedHeaders() =
        headersOf(
            HttpHeaders.WWWAuthenticate to listOf("Bearer"),
            HttpHeaders.ContentType to listOf("application/json"),
        )

    @Test
    fun testSuccessfulTokenRefreshOn401() =
        runTest {
            var dataAttempts = 0
            var refreshAttempts = 0

            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/data" -> {
                            dataAttempts++
                            val authHeader = request.headers[HttpHeaders.Authorization]
                            if (authHeader == "Bearer access_new") {
                                respond(
                                    content = """{"status":"ok"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            } else {
                                respond(
                                    content = """{"error":"unauthorized"}""",
                                    status = HttpStatusCode.Unauthorized,
                                    headers = unauthorizedHeaders(),
                                )
                            }
                        }
                        "/api/auth/refresh" -> {
                            refreshAttempts++
                            respond(
                                content = """{"access_token":"access_new","refresh_token":"refresh_new"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        else -> error("Unhandled ${request.url}")
                    }
                }

            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            sessionManager.saveSession("access_old", "refresh_old")

            val client =
                HttpClientFactory.create(
                    sessionManager = sessionManager,
                    tokenStorage = storage,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    engine = engine,
                )

            val result =
                client.safeRequest<TestData> {
                    get("https://api.example.com/api/data")
                }

            assertTrue(result.isSuccess)
            assertEquals("ok", result.getOrNull()?.status)
            assertEquals(2, dataAttempts)
            assertEquals(1, refreshAttempts)
            assertEquals("access_new", storage.getAccessToken())
            assertEquals("refresh_new", storage.getRefreshToken())
            assertEquals(SessionState.Authenticated("access_new"), sessionManager.sessionState.value)
        }

    @Test
    fun testExpiredRefreshTokenClearsSession() =
        runTest {
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/data" -> {
                            respond(
                                content = """{"error":"unauthorized"}""",
                                status = HttpStatusCode.Unauthorized,
                                headers = unauthorizedHeaders(),
                            )
                        }
                        "/api/auth/refresh" -> {
                            respond(
                                content = """{"error":"invalid_grant"}""",
                                status = HttpStatusCode.Unauthorized,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        else -> error("Unhandled ${request.url}")
                    }
                }

            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            sessionManager.saveSession("access_old", "refresh_old")

            val client =
                HttpClientFactory.create(
                    sessionManager = sessionManager,
                    tokenStorage = storage,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    engine = engine,
                )

            val result =
                client.safeRequest<TestData> {
                    get("https://api.example.com/api/data")
                }

            assertTrue(result.isFailure)
            assertNull(storage.getAccessToken())
            assertNull(storage.getRefreshToken())
            assertEquals(SessionState.SessionExpired, sessionManager.sessionState.value)
        }

    @Test
    fun testServerErrorDuringRefreshPreservesTokens() =
        runTest {
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/data" -> {
                            respond(
                                content = """{"error":"unauthorized"}""",
                                status = HttpStatusCode.Unauthorized,
                                headers = unauthorizedHeaders(),
                            )
                        }
                        "/api/auth/refresh" -> {
                            respond(
                                content = """{"error":"internal_error"}""",
                                status = HttpStatusCode.InternalServerError,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        else -> error("Unhandled ${request.url}")
                    }
                }

            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            sessionManager.saveSession("access_old", "refresh_old")

            val client =
                HttpClientFactory.create(
                    sessionManager = sessionManager,
                    tokenStorage = storage,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    engine = engine,
                )

            val result =
                client.safeRequest<TestData> {
                    get("https://api.example.com/api/data")
                }

            assertTrue(result.isFailure)
            assertEquals("access_old", storage.getAccessToken())
            assertEquals("refresh_old", storage.getRefreshToken())
            assertEquals(SessionState.Authenticated("access_old"), sessionManager.sessionState.value)
        }

    @Test
    fun testBearerTokenNotSentToThirdPartyDomains() =
        runTest {
            var receivedAuthHeader: String? = null

            val engine =
                MockEngine { request ->
                    receivedAuthHeader = request.headers[HttpHeaders.Authorization]
                    respond(
                        content = """{"status":"ok"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            sessionManager.saveSession("secret_access", "secret_refresh")

            val client =
                HttpClientFactory.create(
                    sessionManager = sessionManager,
                    tokenStorage = storage,
                    apiBaseUrl = "https://api.example.com",
                    enableLogs = false,
                    engine = engine,
                )

            val result =
                client.safeRequest<TestData> {
                    get("https://external-cdn.com/data")
                }

            println("RESULT IS: $result")
            assertTrue(result.isSuccess)
            assertFalse(
                receivedAuthHeader != null,
                "Bearer token must NOT be sent to external domain",
            )
        }
}
