package studio.acmenra.control.di

import io.ktor.client.HttpClient
import org.koin.core.KoinApplication
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.core.network.client.ApiConfig
import studio.acmenra.control.core.network.model.TokenPair
import studio.acmenra.control.feature.auth.presentation.LoginViewModel
import studio.acmenra.control.feature.events.presentation.EventsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

private class FakeTokenStorage : TokenStorage {
    private var tokenPair: TokenPair? = null

    override fun getTokens(): TokenPair? = tokenPair

    override fun saveTokens(tokens: TokenPair) {
        tokenPair = tokens
    }

    override fun clear() {
        tokenPair = null
    }
}

class DiTest {
    private lateinit var koinApp: KoinApplication

    @BeforeTest
    fun setUp() {
        koinApp =
            koinApplication {
                modules(
                    appModule,
                    module {
                        single<TokenStorage> { FakeTokenStorage() }
                        single {
                            ApiConfig(
                                apiBaseUrl = "https://api.example.com",
                            )
                        }
                    },
                )
            }
    }

    @AfterTest
    fun tearDown() {
        koinApp.close()
    }

    @Test
    fun testResolveDependencies() {
        val koin = koinApp.koin

        val tokenStorage: TokenStorage = koin.get()
        assertNotNull(tokenStorage)

        val sessionManager: SessionManager = koin.get()
        assertNotNull(sessionManager)

        val loginViewModel: LoginViewModel = koin.get()
        assertNotNull(loginViewModel)

        val eventsViewModel: EventsViewModel = koin.get()
        assertNotNull(eventsViewModel)

        val apiClient: HttpClient = koin.get(named("api"))
        assertNotNull(apiClient)

        val externalClient: HttpClient = koin.get(named("external"))
        assertNotNull(externalClient)
        assertNotSame(apiClient, externalClient)
    }

    @Test
    fun testSingletons() {
        val koin = koinApp.koin

        val session1: SessionManager = koin.get()
        val session2: SessionManager = koin.get()
        assertSame(session1, session2)

        val storage1: TokenStorage = koin.get()
        val storage2: TokenStorage = koin.get()
        assertSame(storage1, storage2)
    }

    @Test
    fun testViewModelFactory() {
        val koin = koinApp.koin

        val login1: LoginViewModel = koin.get()
        val login2: LoginViewModel = koin.get()
        assertNotSame(login1, login2)

        val events1: EventsViewModel = koin.get()
        val events2: EventsViewModel = koin.get()
        assertNotSame(events1, events2)
    }
}
