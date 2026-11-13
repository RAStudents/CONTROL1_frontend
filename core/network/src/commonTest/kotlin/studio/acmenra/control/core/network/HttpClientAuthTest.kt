package studio.acmenra.control.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.network.client.HttpClientFactory
import studio.acmenra.control.core.network.client.safeRequest
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.network.model.NetworkResult
import studio.acmenra.control.core.network.model.TokenPair
import studio.acmenra.control.core.network.model.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            sessionManager.saveSession(TokenPair("access_old", "refresh_old"))

            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = engine,
                )

            try {
                val result =
                    client.safeRequest<TestData> {
                        get("https://api.example.com/api/data")
                    }

                assertTrue(result.isSuccess)
                assertEquals("ok", result.getOrNull()?.status)
                assertEquals(2, dataAttempts)
                assertEquals(1, refreshAttempts)
                assertEquals("access_new", storage.getTokens()?.accessToken)
                assertEquals("refresh_new", storage.getTokens()?.refreshToken)
                assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)
            } finally {
                client.close()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testConcurrentTokenRefreshAcrossMultipleClientsExecutesRefreshOnlyOnce() =
        runTest {
            var refreshAttempts = 0
            val refreshGate = CompletableDeferred<Unit>()
            val refreshStarted = CompletableDeferred<Unit>()

            val handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
                io.ktor.client.request.HttpRequestData,
            ) -> io.ktor.client.request.HttpResponseData = { request ->
                when (request.url.encodedPath) {
                    "/api/data" -> {
                        val authHeader = request.headers[HttpHeaders.Authorization]
                        if (authHeader == "Bearer access_2") {
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
                        val bodyText = request.body.toByteArray().decodeToString()
                        val isRefresh1 = bodyText.contains("refresh_1")
                        refreshAttempts++
                        if (refreshAttempts == 1 && isRefresh1) {
                            refreshStarted.complete(Unit)
                            refreshGate.await()
                            respond(
                                content = """{"access_token":"access_2","refresh_token":"refresh_2"}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond(
                                content = """{"error":"invalid_grant"}""",
                                status = HttpStatusCode.BadRequest,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                    else -> error("Unhandled ${request.url}")
                }
            }

            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            sessionManager.saveSession(TokenPair("access_1", "refresh_1"))

            val clientA =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = MockEngine(handler),
                )
            val clientB =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = MockEngine(handler),
                )

            try {
                val callA =
                    async {
                        clientA.safeRequest<TestData> {
                            get("https://api.example.com/api/data")
                        }
                    }
                val callB =
                    async {
                        clientB.safeRequest<TestData> {
                            get("https://api.example.com/api/data")
                        }
                    }

                refreshStarted.await()
                runCurrent()
                refreshGate.complete(Unit)

                val results = awaitAll(callA, callB)

                assertEquals(1, refreshAttempts)
                assertTrue(results[0].isSuccess)
                assertTrue(results[1].isSuccess)
                assertEquals("ok", results[0].getOrNull()?.status)
                assertEquals("ok", results[1].getOrNull()?.status)
                assertEquals("access_2", storage.getTokens()?.accessToken)
                assertEquals("refresh_2", storage.getTokens()?.refreshToken)
                assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)
            } finally {
                clientA.close()
                clientB.close()
            }
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
            sessionManager.saveSession(TokenPair("access_old", "refresh_old"))

            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = engine,
                )

            try {
                val result =
                    client.safeRequest<TestData> {
                        get("https://api.example.com/api/data")
                    }

                assertTrue(result.isFailure)
                assertNull(storage.getTokens())
                assertEquals(SessionState.SessionExpired, sessionManager.sessionState.value)
            } finally {
                client.close()
            }
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
            sessionManager.saveSession(TokenPair("access_old", "refresh_old"))

            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = engine,
                )

            try {
                val result =
                    client.safeRequest<TestData> {
                        get("https://api.example.com/api/data")
                    }

                assertTrue(result.isFailure)
                assertEquals("access_old", storage.getTokens()?.accessToken)
                assertEquals("refresh_old", storage.getTokens()?.refreshToken)
                assertEquals(SessionState.Authenticated, sessionManager.sessionState.value)
            } finally {
                client.close()
            }
        }

    private fun testRefreshResponseScenario(
        status: HttpStatusCode,
        body: String,
        expectTokensCleared: Boolean,
        expectedState: SessionState,
    ) = runTest {
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
                            content = body,
                            status = status,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                    else -> error("Unhandled ${request.url}")
                }
            }

        val storage = FakeTokenStorage()
        val sessionManager = SessionManager(storage)
        sessionManager.saveSession(TokenPair("access_old", "refresh_old"))

        val client =
            HttpClientFactory.createApiClient(
                sessionManager = sessionManager,
                apiBaseUrl = baseUrl,
                enableLogs = false,
                debugMode = true,
                engine = engine,
            )

        try {
            val result =
                client.safeRequest<TestData> {
                    get("https://api.example.com/api/data")
                }

            assertTrue(result.isFailure)
            if (expectTokensCleared) {
                assertNull(storage.getTokens())
            } else {
                assertEquals("access_old", storage.getTokens()?.accessToken)
                assertEquals("refresh_old", storage.getTokens()?.refreshToken)
            }
            assertEquals(expectedState, sessionManager.sessionState.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun testRefresh400InvalidGrantExpiresSession() =
        testRefreshResponseScenario(
            status = HttpStatusCode.BadRequest,
            body = """{"error":"invalid_grant"}""",
            expectTokensCleared = true,
            expectedState = SessionState.SessionExpired,
        )

    @Test
    fun testRefresh401InvalidGrantExpiresSession() =
        testRefreshResponseScenario(
            status = HttpStatusCode.Unauthorized,
            body = """{"error":"invalid_grant"}""",
            expectTokensCleared = true,
            expectedState = SessionState.SessionExpired,
        )

    @Test
    fun testRefresh401EmptyBodyExpiresSession() =
        testRefreshResponseScenario(
            status = HttpStatusCode.Unauthorized,
            body = "",
            expectTokensCleared = true,
            expectedState = SessionState.SessionExpired,
        )

    @Test
    fun testRefresh401ServerErrorPreservesTokens() =
        testRefreshResponseScenario(
            status = HttpStatusCode.Unauthorized,
            body = """{"error":"server_error"}""",
            expectTokensCleared = false,
            expectedState = SessionState.Authenticated,
        )

    @Test
    fun testRefresh403PreservesTokens() =
        testRefreshResponseScenario(
            status = HttpStatusCode.Forbidden,
            body = """{"error":"forbidden"}""",
            expectTokensCleared = false,
            expectedState = SessionState.Authenticated,
        )

    @Test
    fun testRefresh500PreservesTokens() =
        testRefreshResponseScenario(
            status = HttpStatusCode.InternalServerError,
            body = """{"error":"internal_error"}""",
            expectTokensCleared = false,
            expectedState = SessionState.Authenticated,
        )

    @Test
    fun testExternalClientDoesNotSendAuthTokensEvenOn401Challenge() =
        runTest {
            var receivedAuthHeader: String? = null

            val engine =
                MockEngine { request ->
                    receivedAuthHeader = request.headers[HttpHeaders.Authorization]
                    respond(
                        content = """{"status":"unauthorized_cdn"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = unauthorizedHeaders(),
                    )
                }

            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            sessionManager.saveSession(TokenPair("secret_access", "secret_refresh"))

            val externalClient =
                HttpClientFactory.createExternalClient(
                    enableLogs = false,
                    engine = engine,
                )

            try {
                val result =
                    externalClient.safeRequest<TestData> {
                        get("https://external-cdn.com/asset.json")
                    }

                assertTrue(result.isFailure)
                assertNull(receivedAuthHeader, "External client must never send Authorization header")
            } finally {
                externalClient.close()
            }
        }

    @Test
    fun testLogoutClearsTokensInAllRegisteredClients() =
        runTest {
            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)

            var lastAuthHeaderA: String? = null
            var lastAuthHeaderB: String? = null

            val engineA =
                MockEngine { request ->
                    lastAuthHeaderA = request.headers[HttpHeaders.Authorization]
                    respond(
                        content = """{"status":"ok"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val engineB =
                MockEngine { request ->
                    lastAuthHeaderB = request.headers[HttpHeaders.Authorization]
                    respond(
                        content = """{"status":"ok"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val clientA =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = engineA,
                )

            val clientB =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = engineB,
                )

            try {
                // Scenario for clientA
                sessionManager.saveSession(TokenPair("access_1", "refresh_1"))
                val resA1 = clientA.safeRequest<TestData> { get("https://api.example.com/api/data") }
                assertTrue(resA1.isSuccess)
                assertEquals("Bearer access_1", lastAuthHeaderA)

                sessionManager.logout()
                val resA2 = clientA.safeRequest<TestData> { get("https://api.example.com/api/data") }
                assertTrue(resA2.isSuccess)
                assertNull(lastAuthHeaderA)

                sessionManager.saveSession(TokenPair("access_2", "refresh_2"))
                val resA3 = clientA.safeRequest<TestData> { get("https://api.example.com/api/data") }
                assertTrue(resA3.isSuccess)
                assertEquals("Bearer access_2", lastAuthHeaderA)

                // Scenario for clientB
                sessionManager.saveSession(TokenPair("access_1", "refresh_1"))
                val resB1 = clientB.safeRequest<TestData> { get("https://api.example.com/api/data") }
                assertTrue(resB1.isSuccess)
                assertEquals("Bearer access_1", lastAuthHeaderB)

                sessionManager.logout()
                val resB2 = clientB.safeRequest<TestData> { get("https://api.example.com/api/data") }
                assertTrue(resB2.isSuccess)
                assertNull(lastAuthHeaderB)

                sessionManager.saveSession(TokenPair("access_2", "refresh_2"))
                val resB3 = clientB.safeRequest<TestData> { get("https://api.example.com/api/data") }
                assertTrue(resB3.isSuccess)
                assertEquals("Bearer access_2", lastAuthHeaderB)
            } finally {
                clientA.close()
                clientB.close()
            }
        }

    @Test
    fun apiClientMustNotSendBearerTokenToExternalHostAfter401() =
        runTest {
            val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
            val engine =
                MockEngine { request ->
                    requests.add(request)
                    respond(
                        content = """{"error":"unauthorized"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = unauthorizedHeaders(),
                    )
                }

            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            sessionManager.saveSession(TokenPair("secret_access", "secret_refresh"))

            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = engine,
                )

            try {
                val result =
                    client.safeRequest<TestData> {
                        get("https://external.example.com/resource")
                    }

                assertTrue(result.isFailure)
                assertTrue(
                    requests.none { it.headers.contains(HttpHeaders.Authorization) },
                    "No request to engine must contain Authorization header",
                )
                assertTrue(
                    requests.none { it.url.encodedPath.contains("refresh") },
                    "No refresh endpoint request should be made",
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun testOriginGuardThrowsIllegalArgumentExceptionOnDirectExternalCall() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"status":"ok"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)

            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = engine,
                )

            try {
                assertFailsWith<IllegalArgumentException> {
                    client.get("https://external.example.com/resource")
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun testRelativeUrlContinuesToWorkWithOriginGuard() =
        runTest {
            var receivedPath: String? = null
            var receivedAuth: String? = null

            val engine =
                MockEngine { request ->
                    receivedPath = request.url.encodedPath
                    receivedAuth = request.headers[HttpHeaders.Authorization]
                    respond(
                        content = """{"status":"ok"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            sessionManager.saveSession(TokenPair("access_token_123", "refresh_token_123"))

            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = engine,
                )

            try {
                val response = client.get("api/data")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("/api/data", receivedPath)
                assertEquals("Bearer access_token_123", receivedAuth)
            } finally {
                client.close()
            }
        }

    @Test
    fun testSafeRequestRethrowsCancellationExceptionWhenErrorBodyReadCancelled() =
        runTest {
            val engine =
                MockEngine {
                    val channel = ByteChannel()
                    channel.cancel(CancellationException("Body read cancelled"))
                    respond(
                        content = channel,
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine = engine,
                )

            try {
                assertFailsWith<CancellationException> {
                    client.safeRequest<TestData> {
                        get("https://api.example.com/api/data")
                    }
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun testSafeRequestCatchesIOExceptionAsConnectivityError() =
        runTest {
            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine =
                        MockEngine {
                            throw IOException("Connection reset by peer")
                        },
                )

            try {
                val result =
                    client.safeRequest<TestData> {
                        get("https://api.example.com/api/data")
                    }
                assertTrue(result is NetworkResult.Failure)
                assertTrue(result.error is NetworkError.Connectivity)
            } finally {
                client.close()
            }
        }

    @Test
    fun testSafeRequestCatchesSocketTimeoutExceptionAsConnectivityError() =
        runTest {
            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine =
                        MockEngine {
                            throw SocketTimeoutException("Read timed out")
                        },
                )

            try {
                val result =
                    client.safeRequest<TestData> {
                        get("https://api.example.com/api/data")
                    }
                assertTrue(result is NetworkResult.Failure)
                assertTrue(result.error is NetworkError.Connectivity)
            } finally {
                client.close()
            }
        }

    @Test
    fun testSafeRequestRecognizesDeeplyNestedCauseChainConnectivityError() =
        runTest {
            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine =
                        MockEngine {
                            throw RuntimeException(
                                "KtorException",
                                IllegalStateException("WrapperException", Exception("Failed to fetch")),
                            )
                        },
                )

            try {
                val result =
                    client.safeRequest<TestData> {
                        get("https://api.example.com/api/data")
                    }
                assertTrue(result is NetworkResult.Failure)
                assertTrue(result.error is NetworkError.Connectivity)
            } finally {
                client.close()
            }
        }

    @Test
    fun testSafeRequestRethrowsFatalErrorsWithoutMaskingThem() =
        runTest {
            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine =
                        MockEngine {
                            throw OutOfMemoryError("Fake OutOfMemoryError")
                        },
                )

            try {
                assertFailsWith<OutOfMemoryError> {
                    client.safeRequest<TestData> {
                        get("https://api.example.com/api/data")
                    }
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun testSafeRequestRethrowsNonConnectivityErrorsWithoutConvertingToUnknown() =
        runTest {
            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine =
                        MockEngine {
                            throw Error("Fatal linkage failure")
                        },
                )

            try {
                assertFailsWith<Error> {
                    client.safeRequest<TestData> {
                        get("https://api.example.com/api/data")
                    }
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun testSafeRequestCatchesUnknownExceptionAsUnknownError() =
        runTest {
            val storage = FakeTokenStorage()
            val sessionManager = SessionManager(storage)
            val client =
                HttpClientFactory.createApiClient(
                    sessionManager = sessionManager,
                    apiBaseUrl = baseUrl,
                    enableLogs = false,
                    debugMode = true,
                    engine =
                        MockEngine {
                            throw RuntimeException("Unexpected runtime failure")
                        },
                )

            try {
                val result =
                    client.safeRequest<TestData> {
                        get("https://api.example.com/api/data")
                    }
                assertTrue(result is NetworkResult.Failure)
                assertTrue(result.error is NetworkError.Unknown)
            } finally {
                client.close()
            }
        }

    @Test
    fun testInsecureHttpRejectedWhenAllowInsecureTransportFalseEvenWithDebugMode() {
        val storage = FakeTokenStorage()
        val sessionManager = SessionManager(storage)
        assertFailsWith<IllegalArgumentException> {
            HttpClientFactory.createApiClient(
                sessionManager = sessionManager,
                apiBaseUrl = "http://api.example.com",
                debugMode = true,
                engine =
                    MockEngine {
                        respond(
                            content = """{"status":"ok"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
            )
        }
    }

    @Test
    fun testInsecureHttpAllowedWhenAllowInsecureTransportTrue() {
        val storage = FakeTokenStorage()
        val sessionManager = SessionManager(storage)
        val client =
            HttpClientFactory.createApiClient(
                sessionManager = sessionManager,
                apiBaseUrl = "http://api.example.com",
                allowInsecureTransport = true,
                engine =
                    MockEngine {
                        respond(
                            content = """{"status":"ok"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
            )
        client.close()
    }
}
