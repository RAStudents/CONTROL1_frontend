package studio.acmenra.control.core.network.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SettingsTokenStorage
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.client.HttpClientFactory

val networkModule =
    module {
        single<TokenStorage> {
            SettingsTokenStorage()
        }

        single {
            SessionManager(
                tokenStorage = get(),
            )
        }

        single(named("api")) {
            HttpClientFactory.createApiClient(
                sessionManager = get(),
            )
        }

        single(named("external")) {
            HttpClientFactory.createExternalClient()
        }
    }
