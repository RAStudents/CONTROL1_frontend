package studio.acmenra.control.navigation

import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.bindToBrowserNavigation
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalBrowserHistoryApi::class)
class BrowserNavigationWasmTest {
    private fun createTestNavController(): NavHostController {
        val navController =
            NavHostController().apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
        navController.graph =
            navController.createGraph(startDestination = LoginRoute) {
                composable<LoginRoute> {}
                composable<EventsRoute> {}
            }
        return navController
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 2000,
        message: String = "Condition timed out",
        condition: () -> Boolean,
    ) {
        val start = window.performance.now()
        while (!condition()) {
            if (window.performance.now() - start > timeoutMs) {
                error("$message (waited ${timeoutMs}ms, current hash=${window.location.hash})")
            }
            delay(50)
        }
    }

    @Test
    fun testBrowserHistoryIntegrationAndHashSync() =
        runTest {
            val navController = createTestNavController()

            val bindJob =
                launch {
                    navController.bindToBrowserNavigation()
                }

            // Allow initial bind to establish state
            waitUntil(message = "Initial login state") {
                window.location.hash == "#login" &&
                    navController.currentDestination?.hasRoute<LoginRoute>() == true
            }

            // 1. Navigate to Events
            navController.navigateToEvents()
            waitUntil(message = "Navigate to events") {
                window.location.hash.startsWith("#events") &&
                    navController.currentDestination?.hasRoute<EventsRoute>() == true
            }

            // 2. Browser Back
            window.history.back()
            waitUntil(message = "Browser back to login") {
                window.location.hash == "#login" &&
                    navController.currentDestination?.hasRoute<LoginRoute>() == true
            }

            // 3. Browser Forward
            window.history.forward()
            waitUntil(message = "Browser forward to events") {
                window.location.hash.startsWith("#events") &&
                    navController.currentDestination?.hasRoute<EventsRoute>() == true
            }

            // 4. Logout: navigateToLogin clears EventsRoute from stack
            navController.navigateToLogin()
            waitUntil(message = "Logout navigate to login") {
                window.location.hash == "#login" &&
                    navController.currentDestination?.hasRoute<LoginRoute>() == true
            }

            bindJob.cancel()
        }

    @Test
    fun testDirectUrlHashNavigationToEvents() =
        runTest {
            // Set hash before binding to simulate direct URL open in browser
            window.location.hash = "#events"

            val navController = createTestNavController()

            val bindJob =
                launch {
                    navController.bindToBrowserNavigation()
                }

            waitUntil(message = "Direct URL navigation to #events") {
                window.location.hash.startsWith("#events") &&
                    navController.currentDestination?.hasRoute<EventsRoute>() == true
            }

            bindJob.cancel()
        }
}
