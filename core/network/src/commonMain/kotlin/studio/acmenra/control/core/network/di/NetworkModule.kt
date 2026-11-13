package studio.acmenra.control.core.network.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SettingsTokenStorage
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.client.ApiConfig
import studio.acmenra.control.core.network.client.HttpClientFactory

val networkModule =
    module {
        single<TokenStorage> {
            SettingsTokenStorage()
        }

        single {
            ApiConfig()
        }

        single {
            SessionManager(
                tokenStorage = get(),
            )
        }

        single(named("api")) {
            val config = get<ApiConfig>()
            val rawUrl = config.apiBaseUrl.trim()
            val safeUrl =
                if (rawUrl.startsWith("https://", ignoreCase = true) ||
                    rawUrl.startsWith("http://", ignoreCase = true)
                ) {
                    rawUrl
                } else {
                    "https://api.example.com"
                }
            HttpClientFactory.createApiClient(
                sessionManager = get(),
                apiBaseUrl = safeUrl,
                enableLogs = config.enableLogs,
                debugMode = config.debugMode,
                allowInsecureTransport = config.allowInsecureTransport,
            )
        }

        single(named("external")) {
            val config = get<ApiConfig>()
            HttpClientFactory.createExternalClient(
                enableLogs = config.enableLogs,
            )
        }
    }
