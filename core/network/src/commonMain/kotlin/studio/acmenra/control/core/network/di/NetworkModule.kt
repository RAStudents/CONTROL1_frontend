package studio.acmenra.control.core.network.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SettingsTokenStorage
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.client.HttpClientFactory
import studio.acmenra.control.core.network.model.TokenPair

private class InMemoryTokenStorage : TokenStorage {
    private var tokenPair: TokenPair? = null

    override fun getTokens(): TokenPair? = tokenPair

    override fun saveTokens(tokens: TokenPair) {
        tokenPair = tokens
    }

    override fun clear() {
        tokenPair = null
    }
}

val networkModule =
    module {
        single<TokenStorage> {
            runCatching { SettingsTokenStorage() }.getOrElse { InMemoryTokenStorage() }
        }

        single {
            SessionManager(
                tokenStorage = get<TokenStorage>(),
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
