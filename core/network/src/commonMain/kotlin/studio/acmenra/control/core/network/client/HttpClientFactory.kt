package studio.acmenra.control.core.network.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import studio.acmenra.control.NetworkConfig
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.engine.createHttpEngineFactory
import studio.acmenra.control.core.network.model.RefreshTokenRequest
import studio.acmenra.control.core.network.model.RefreshTokenResponse

object HttpClientFactory {
    fun create(
        sessionManager: SessionManager,
        tokenStorage: TokenStorage,
        apiBaseUrl: String = NetworkConfig.BASE_URL,
        enableLogs: Boolean = NetworkConfig.ENABLE_LOGS,
        refreshPath: String = "api/auth/refresh",
        customLogger: Logger? = null,
    ): HttpClient {
        val parsedHost = runCatching { Url(apiBaseUrl).host }.getOrNull().orEmpty()

        val client =
            HttpClient(createHttpEngineFactory()) {
                installNetworkTimeouts()

                defaultRequest {
                    if (apiBaseUrl.isNotBlank() &&
                        (apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://"))
                    ) {
                        url(apiBaseUrl.trimEnd('/') + "/")
                    }
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }

                install(ContentNegotiation) {
                    json(
                        Json {
                            prettyPrint = true
                            isLenient = true
                            ignoreUnknownKeys = true
                            encodeDefaults = true
                        },
                    )
                }

                install(Auth) {
                    bearer {
                        cacheTokens = true

                        loadTokens {
                            val access = tokenStorage.getAccessToken()
                            val refresh = tokenStorage.getRefreshToken()
                            if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
                                null
                            } else {
                                BearerTokens(accessToken = access, refreshToken = refresh)
                            }
                        }

                        refreshTokens {
                            val refreshToken = oldTokens?.refreshToken ?: tokenStorage.getRefreshToken()
                            if (refreshToken.isNullOrBlank()) {
                                sessionManager.notifySessionExpired()
                                null
                            } else {
                                val normalizedBase = apiBaseUrl.trimEnd('/')
                                val refreshUrl =
                                    if (normalizedBase.startsWith("http://") ||
                                        normalizedBase.startsWith("https://")
                                    ) {
                                        "$normalizedBase/${refreshPath.trimStart('/')}"
                                    } else {
                                        "/${refreshPath.trimStart('/')}"
                                    }

                                val response =
                                    client.post(refreshUrl) {
                                        with(this@refreshTokens) {
                                            markAsRefreshTokenRequest()
                                        }
                                        contentType(ContentType.Application.Json)
                                        setBody(RefreshTokenRequest(refreshToken = refreshToken))
                                    }

                                when {
                                    response.status == HttpStatusCode.Unauthorized ||
                                        response.status == HttpStatusCode.Forbidden ||
                                        response.status == HttpStatusCode.BadRequest -> {
                                        sessionManager.notifySessionExpired()
                                        null
                                    }
                                    !response.status.isSuccess() -> {
                                        throw IllegalStateException("Token refresh failed: ${response.status}")
                                    }
                                    else -> {
                                        val tokens = response.body<RefreshTokenResponse>()
                                        val newRefreshToken = tokens.refreshToken ?: refreshToken
                                        sessionManager.saveSession(
                                            accessToken = tokens.accessToken,
                                            refreshToken = newRefreshToken,
                                        )
                                        BearerTokens(
                                            accessToken = tokens.accessToken,
                                            refreshToken = newRefreshToken,
                                        )
                                    }
                                }
                            }
                        }

                        sendWithoutRequest { request ->
                            parsedHost.isBlank() || request.url.host == parsedHost
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

        sessionManager.attachClient(client)
        return client
    }
}
