package studio.acmenra.control.core.network.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import studio.acmenra.control.core.network.auth.AccessInvalidationDispatcher
import studio.acmenra.control.core.network.auth.AccessInvalidationListener
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
            AccessInvalidationDispatcher()
        }

        single<AccessInvalidationListener> {
            get<AccessInvalidationDispatcher>()
        }

        single {
            SessionManager(
                tokenStorage = get(),
            )
        }

        single(named("api")) {
            val config = get<ApiConfig>()
            HttpClientFactory.createApiClient(
                sessionManager = get(),
                accessInvalidationListener = get<AccessInvalidationListener>(),
                apiBaseUrl = config.apiBaseUrl,
                enableLogs = config.enableLogs,
                debugMode = config.debugMode,
                allowInsecureTransport = config.allowInsecureTransport,
                refreshPath = config.refreshPath,
            )
        }

        single(named("external")) {
            val config = get<ApiConfig>()
            HttpClientFactory.createExternalClient(
                enableLogs = config.enableLogs,
            )
        }

        single(named("realtime")) {
            val config = get<ApiConfig>()
            HttpClientFactory.createRealtimeClient(
                apiBaseUrl = config.apiBaseUrl,
                enableLogs = config.enableLogs,
                debugMode = config.debugMode,
                allowInsecureTransport = config.allowInsecureTransport,
            )
        }
    }
