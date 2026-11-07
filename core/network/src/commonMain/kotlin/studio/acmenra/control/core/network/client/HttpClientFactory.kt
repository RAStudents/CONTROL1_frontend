package studio.acmenra.control.core.network.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import studio.acmenra.control.NetworkConfig
import studio.acmenra.control.core.network.auth.AuthClientRegistry
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.engine.createHttpEngineFactory
import studio.acmenra.control.core.network.model.RefreshTokenRequest
import studio.acmenra.control.core.network.model.RefreshTokenResponse
import studio.acmenra.control.core.network.model.TokenPair

object HttpClientFactory {
    fun createApiClient(
        sessionManager: SessionManager,
        apiBaseUrl: String = NetworkConfig.BASE_URL,
        enableLogs: Boolean = NetworkConfig.ENABLE_LOGS,
        debugMode: Boolean = NetworkConfig.DEBUG_MODE,
        refreshPath: String = "api/auth/refresh",
        clientRegistry: AuthClientRegistry? = null,
        customLogger: Logger? = null,
        engine: HttpClientEngine? = null,
    ): HttpClient {
        require(apiBaseUrl.isNotBlank()) { "API base URL must not be blank" }
        val expectedOrigin =
            requireNotNull(runCatching { Url(apiBaseUrl) }.getOrNull()) {
                "Invalid API base URL: $apiBaseUrl"
            }
        if (!debugMode) {
            require(expectedOrigin.protocol.name.equals("https", ignoreCase = true)) {
                "HTTPS is required for API base URL in production"
            }
        }

        val baseClient = engine?.let { HttpClient(it) } ?: HttpClient(createHttpEngineFactory())
        val client =
            baseClient.config {
                installNetworkTimeouts()

                defaultRequest {
                    url(apiBaseUrl.trimEnd('/') + "/")
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }

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
                        cacheTokens = true
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
                            val snapshot = sessionManager.getSnapshot()
                            if (snapshot == null || !snapshot.tokens.isValid()) {
                                null
                            } else {
                                val refreshUrl =
                                    URLBuilder(expectedOrigin)
                                        .apply {
                                            val existing = pathSegments.filter { it.isNotEmpty() }
                                            val newSegments =
                                                refreshPath
                                                    .trim(
                                                        '/',
                                                    ).split('/')
                                                    .filter { it.isNotEmpty() }
                                            pathSegments = existing + newSegments
                                        }.buildString()

                                val response =
                                    client.post(refreshUrl) {
                                        with(this@refreshTokens) {
                                            markAsRefreshTokenRequest()
                                        }
                                        contentType(ContentType.Application.Json)
                                        setBody(RefreshTokenRequest(refreshToken = snapshot.tokens.refreshToken))
                                    }

                                when {
                                    response.status == HttpStatusCode.Unauthorized ||
                                        response.status == HttpStatusCode.Forbidden ||
                                        (response.status == HttpStatusCode.BadRequest && isInvalidGrant(response)) -> {
                                        sessionManager.expireSessionIfMatches(snapshot)
                                        null
                                    }
                                    !response.status.isSuccess() -> {
                                        throw IllegalStateException("Token refresh failed: ${response.status}")
                                    }
                                    else -> {
                                        val tokenResponse = response.body<RefreshTokenResponse>()
                                        require(tokenResponse.accessToken.isNotBlank()) {
                                            "Received blank access token from refresh endpoint"
                                        }
                                        val newRefreshToken = tokenResponse.refreshToken ?: snapshot.tokens.refreshToken
                                        require(newRefreshToken.isNotBlank()) {
                                            "Received blank refresh token from refresh endpoint"
                                        }

                                        val newTokens =
                                            TokenPair(
                                                accessToken = tokenResponse.accessToken,
                                                refreshToken = newRefreshToken,
                                            )

                                        val saved =
                                            sessionManager.saveRefreshedSession(
                                                expected = snapshot,
                                                newTokens = newTokens,
                                            )
                                        if (saved) {
                                            BearerTokens(
                                                accessToken = newTokens.accessToken,
                                                refreshToken = newTokens.refreshToken,
                                            )
                                        } else {
                                            null
                                        }
                                    }
                                }
                            }
                        }

                        sendWithoutRequest { request ->
                            val requestPort =
                                if (request.url.port ==
                                    0
                                ) {
                                    request.url.protocol.defaultPort
                                } else {
                                    request.url.port
                                }
                            val expectedPort =
                                if (expectedOrigin.port ==
                                    0
                                ) {
                                    expectedOrigin.protocol.defaultPort
                                } else {
                                    expectedOrigin.port
                                }
                            request.url.protocol == expectedOrigin.protocol &&
                                request.url.host == expectedOrigin.host &&
                                requestPort == expectedPort
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
                            header.equals(HttpHeaders.Authorization, ignoreCase = true)
                        }
                    }
                }
            }

        clientRegistry?.register(client)
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
    ): HttpClient {
        val baseClient = engine?.let { HttpClient(it) } ?: HttpClient(createHttpEngineFactory())
        return baseClient.config {
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
    }

    private suspend fun isInvalidGrant(response: HttpResponse): Boolean {
        val body = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
        return body.contains("invalid_grant", ignoreCase = true) ||
            body.contains("token_expired", ignoreCase = true)
    }
}
