package studio.acmenra.control.core.network.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import studio.acmenra.control.NetworkConfig
import studio.acmenra.control.core.network.auth.AccessInvalidationListener
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionSnapshot
import studio.acmenra.control.core.network.engine.createHttpEngineFactory
import studio.acmenra.control.core.network.model.OAuthErrorResponse
import studio.acmenra.control.core.network.model.RefreshTokenRequest
import studio.acmenra.control.core.network.model.RefreshTokenResponse
import studio.acmenra.control.core.network.model.TokenPair

object HttpClientFactory {
    /**
     * Creates an [HttpClient] intended only for API calls to its configured origin.
     * For external resources, third-party APIs, or CDNs, use [createExternalClient].
     */
    fun createApiClient(
        sessionManager: SessionManager,
        accessInvalidationListener: AccessInvalidationListener? = null,
        apiBaseUrl: String = NetworkConfig.BASE_URL,
        enableLogs: Boolean = NetworkConfig.ENABLE_LOGS,
        debugMode: Boolean = NetworkConfig.DEBUG_MODE,
        allowInsecureTransport: Boolean = NetworkConfig.ALLOW_INSECURE_TRANSPORT,
        refreshPath: String = NetworkConfig.REFRESH_PATH,
        customLogger: Logger? = null,
        engine: HttpClientEngine? = null,
    ): HttpClient {
        require(apiBaseUrl.isNotBlank()) { "API base URL must not be blank" }
        val expectedOrigin =
            requireNotNull(runCatching { Url(apiBaseUrl) }.getOrNull()) {
                "Invalid API base URL: $apiBaseUrl"
            }
        if (!allowInsecureTransport) {
            require(expectedOrigin.protocol.name.equals("https", ignoreCase = true)) {
                "HTTPS is required for API base URL in production"
            }
        }

        val originGuardPlugin =
            createClientPlugin("OriginGuard") {
                onRequest { request, _ ->
                    if (!isSameOrigin(request.url.build(), expectedOrigin)) {
                        throw IllegalArgumentException("API client must only be used with $expectedOrigin")
                    }
                }
            }

        val refreshClient = createExternalClient(enableLogs = enableLogs, customLogger = customLogger, engine = engine)
        val client =
            try {
                buildClient(engine) {
                    installNetworkTimeouts()

                    defaultRequest {
                        url(apiBaseUrl.trimEnd('/') + "/")
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    }

                    install(originGuardPlugin)

                    install(ContentNegotiation) {
                        json(
                            Json {
                                prettyPrint = debugMode
                                isLenient = true
                                ignoreUnknownKeys = true
                                encodeDefaults = false
                            },
                        )
                    }

                    install(Auth) {
                        bearer {
                            cacheTokens = false
                            nonCancellableRefresh = true

                            loadTokens {
                                val snapshot = sessionManager.getSnapshot()
                                if (snapshot == null || !snapshot.tokens.isValid()) {
                                    null
                                } else {
                                    BearerTokens(
                                        accessToken = snapshot.tokens.accessToken,
                                        refreshToken = snapshot.tokens.refreshToken,
                                    )
                                }
                            }

                            refreshTokens {
                                if (!isSameOrigin(this.response.request.url, expectedOrigin)) {
                                    return@refreshTokens null
                                }
                                val refreshSnapshot =
                                    sessionManager.refreshSession(
                                        failedAccessToken = oldTokens?.accessToken,
                                    ) { current ->
                                        val refreshUrl =
                                            URLBuilder(expectedOrigin)
                                                .apply {
                                                    val existing = pathSegments.filter { it.isNotEmpty() }
                                                    val newSegments =
                                                        refreshPath
                                                            .trim('/')
                                                            .split('/')
                                                            .filter { it.isNotEmpty() }
                                                    pathSegments = existing + newSegments
                                                }.buildString()

                                        val response =
                                            this@refreshTokens.client.post(refreshUrl) {
                                                with(this@refreshTokens) {
                                                    markAsRefreshTokenRequest()
                                                }
                                                contentType(ContentType.Application.Json)
                                                setBody(RefreshTokenRequest(refreshToken = current.tokens.refreshToken))
                                            }

                                        response.toRefreshedTokens(current.tokens.refreshToken)
                                    }

                                refreshSnapshot?.tokens?.let {
                                    BearerTokens(
                                        accessToken = it.accessToken,
                                        refreshToken = it.refreshToken,
                                    )
                                }
                            }

                            sendWithoutRequest { request ->
                                isSameOrigin(request.url.build(), expectedOrigin)
                            }
                        }
                    }

                    if (enableLogs) {
                        install(Logging) {
                            if (customLogger != null) {
                                logger = customLogger
                            }
                            level = LogLevel.HEADERS
                            sanitizeHeader { header ->
                                header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                                    header.equals(HttpHeaders.Cookie, ignoreCase = true) ||
                                    header.equals(HttpHeaders.SetCookie, ignoreCase = true)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                refreshClient.close()
                throw e
            }

        if (accessInvalidationListener != null) {
            client.attributes.put(AccessInvalidationAttributeKey, accessInvalidationListener)
        }

        val delegate: suspend (String?) -> SessionSnapshot? = { failedToken ->
            val current = sessionManager.getSnapshot()
            val targetToken = failedToken ?: current?.tokens?.accessToken
            sessionManager.refreshSession(failedAccessToken = targetToken) { cur ->
                val refreshUrl =
                    URLBuilder(expectedOrigin)
                        .apply {
                            val existing = pathSegments.filter { it.isNotEmpty() }
                            val newSegments =
                                refreshPath
                                    .trim('/')
                                    .split('/')
                                    .filter { it.isNotEmpty() }
                            pathSegments = existing + newSegments
                        }.buildString()

                val response =
                    refreshClient.post(refreshUrl) {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshTokenRequest(refreshToken = cur.tokens.refreshToken))
                    }

                response.toRefreshedTokens(cur.tokens.refreshToken)
            }
        }

        sessionManager.setRefreshDelegate(delegate)
        client.coroutineContext[Job]?.invokeOnCompletion {
            refreshClient.close()
            sessionManager.clearRefreshDelegate(delegate)
        }

        return client
    }

    /**
     * Creates an [HttpClient] intended for external resources (CDN, S3, third-party APIs).
     * This client does NOT include the Auth plugin, preventing credentials from leaking externally.
     */
    fun createExternalClient(
        enableLogs: Boolean = NetworkConfig.ENABLE_LOGS,
        customLogger: Logger? = null,
        engine: HttpClientEngine? = null,
    ): HttpClient =
        buildClient(engine) {
            installNetworkTimeouts()

            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                        encodeDefaults = false
                    },
                )
            }

            if (enableLogs) {
                install(Logging) {
                    if (customLogger != null) {
                        logger = customLogger
                    }
                    level = LogLevel.HEADERS
                }
            }
        }

    /**
     * Creates an [HttpClient] intended for WebSocket realtime connections.
     * WebSockets plugin is installed. Auth plugin is NOT installed on handshake
     * to prevent Bearer token or cookie leakage during HTTP upgrade.
     * Authentication is performed via first-frame AUTH message.
     */
    fun createRealtimeClient(
        apiBaseUrl: String = NetworkConfig.BASE_URL,
        enableLogs: Boolean = NetworkConfig.ENABLE_LOGS,
        debugMode: Boolean = NetworkConfig.DEBUG_MODE,
        allowInsecureTransport: Boolean = NetworkConfig.ALLOW_INSECURE_TRANSPORT,
        customLogger: Logger? = null,
        engine: HttpClientEngine? = null,
    ): HttpClient {
        require(apiBaseUrl.isNotBlank()) { "API base URL must not be blank" }
        val expectedOrigin =
            requireNotNull(runCatching { Url(apiBaseUrl) }.getOrNull()) {
                "Invalid API base URL: $apiBaseUrl"
            }
        if (!allowInsecureTransport) {
            val proto = expectedOrigin.protocol.name
            require(proto.equals("https", ignoreCase = true) || proto.equals("wss", ignoreCase = true)) {
                "HTTPS/WSS is required for Realtime client in production"
            }
        }

        val originGuardPlugin =
            createClientPlugin("RealtimeOriginGuard") {
                onRequest { request, _ ->
                    val requestUrl = request.url.build()
                    val reqPort =
                        if (requestUrl.port == 0) {
                            requestUrl.protocol.defaultPort
                        } else {
                            requestUrl.port
                        }
                    val expPort =
                        if (expectedOrigin.port == 0) {
                            expectedOrigin.protocol.defaultPort
                        } else {
                            expectedOrigin.port
                        }
                    val sameHost = requestUrl.host.equals(expectedOrigin.host, ignoreCase = true)
                    val sameHostAndPort = sameHost && reqPort == expPort
                    val reqProto = requestUrl.protocol.name.lowercase()
                    val validProto =
                        if (!allowInsecureTransport) {
                            reqProto == "wss" || reqProto == "https"
                        } else {
                            reqProto in setOf("wss", "ws", "https", "http")
                        }
                    if (!sameHostAndPort || !validProto) {
                        throw IllegalArgumentException(
                            "Realtime client must only connect to $expectedOrigin, got $requestUrl",
                        )
                    }
                }
            }

        return buildClient(engine) {
            installNetworkTimeouts()

            install(WebSockets) {
                pingIntervalMillis = 0
            }

            install(originGuardPlugin)

            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = debugMode
                        isLenient = true
                        ignoreUnknownKeys = true
                        encodeDefaults = false
                    },
                )
            }

            if (enableLogs) {
                install(Logging) {
                    if (customLogger != null) {
                        logger = customLogger
                    }
                    level = LogLevel.HEADERS
                    sanitizeHeader { header ->
                        header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                            header.equals(HttpHeaders.Cookie, ignoreCase = true) ||
                            header.equals(HttpHeaders.SetCookie, ignoreCase = true)
                    }
                }
            }
        }
    }

    private fun buildClient(
        engine: HttpClientEngine?,
        block: HttpClientConfig<*>.() -> Unit,
    ): HttpClient =
        if (engine != null) {
            HttpClient(engine, block)
        } else {
            HttpClient(createHttpEngineFactory(), block)
        }

    private val INVALID_REFRESH_ERRORS = setOf("invalid_grant", "invalid_token", "token_expired")
    private val oAuthJson = Json { ignoreUnknownKeys = true }

    // TokenPair = успех, null = сервер подтвердил, что refresh token мёртв, исключение = временная ошибка
    private suspend fun HttpResponse.toRefreshedTokens(currentRefreshToken: String): TokenPair? {
        if (status.isSuccess()) {
            val body = body<RefreshTokenResponse>()
            require(body.accessToken.isNotBlank()) { "Received blank access token from refresh endpoint" }
            val newRefresh = body.refreshToken ?: currentRefreshToken
            require(newRefresh.isNotBlank()) { "Received blank refresh token from refresh endpoint" }
            return TokenPair(accessToken = body.accessToken, refreshToken = newRefresh)
        }
        val code = readErrorBodySafely()?.let(::parseOAuthErrorCode)
        val knownInvalid = code != null && code in INVALID_REFRESH_ERRORS
        val invalid =
            when (status) {
                HttpStatusCode.BadRequest -> knownInvalid
                HttpStatusCode.Unauthorized -> code == null || knownInvalid
                else -> false
            }
        if (invalid) return null
        throw IllegalStateException("Token refresh failed: $status")
    }

    private fun parseOAuthErrorCode(raw: String): String? =
        runCatching {
            oAuthJson.decodeFromString<OAuthErrorResponse>(raw).error?.lowercase()
        }.getOrNull()

    private fun isSameOrigin(
        url: Url,
        expected: Url,
    ): Boolean {
        val requestPort = if (url.port == 0) url.protocol.defaultPort else url.port
        val expectedPort = if (expected.port == 0) expected.protocol.defaultPort else expected.port
        return url.protocol == expected.protocol &&
            url.host.equals(expected.host, ignoreCase = true) &&
            requestPort == expectedPort
    }
}
