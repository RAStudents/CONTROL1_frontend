package studio.acmenra.control.di

import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.get
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.TokenStorage
import studio.acmenra.control.feature.auth.presentation.LoginViewModel
import studio.acmenra.control.feature.events.presentation.EventsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class DiTest : KoinTest {
    @BeforeTest
    fun setUp() {
        stopKoin()
        initKoin()
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun testResolveDependencies() {
        val tokenStorage: TokenStorage = get()
        assertNotNull(tokenStorage)

        val sessionManager: SessionManager = get()
        assertNotNull(sessionManager)

        val loginViewModel: LoginViewModel = get()
        assertNotNull(loginViewModel)

        val eventsViewModel: EventsViewModel = get()
        assertNotNull(eventsViewModel)
    }
}
