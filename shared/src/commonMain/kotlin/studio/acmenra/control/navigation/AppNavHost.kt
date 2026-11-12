package studio.acmenra.control.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.feature.auth.presentation.LoginRoute
import studio.acmenra.control.feature.events.presentation.EventsRoute

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: Any,
    sessionState: SessionState,
    modifier: Modifier = Modifier,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    LaunchedEffect(sessionState, destination) {
        when {
            sessionState == SessionState.Authenticated &&
                destination?.hasRoute<LoginRoute>() == true -> {
                navController.navigateToEvents()
            }

            sessionState != SessionState.Authenticated &&
                destination?.hasRoute<EventsRoute>() == true -> {
                navController.navigateToLogin()
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<LoginRoute> {
            LoginRoute()
        }

        composable<EventsRoute> {
            if (sessionState == SessionState.Authenticated) {
                EventsRoute()
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
