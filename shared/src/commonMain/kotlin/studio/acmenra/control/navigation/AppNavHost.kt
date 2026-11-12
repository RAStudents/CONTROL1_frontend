package studio.acmenra.control.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import org.koin.compose.viewmodel.koinViewModel
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.feature.auth.presentation.LoginScreen
import studio.acmenra.control.feature.auth.presentation.LoginViewModel
import studio.acmenra.control.feature.events.presentation.EventsScreen
import studio.acmenra.control.feature.events.presentation.EventsViewModel

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
                navController.navigate(EventsRoute) {
                    popUpTo<LoginRoute> {
                        inclusive = true
                    }
                    launchSingleTop = true
                }
            }

            sessionState != SessionState.Authenticated &&
                destination?.hasRoute<EventsRoute>() == true -> {
                navController.navigate(LoginRoute) {
                    popUpTo<EventsRoute> {
                        inclusive = true
                    }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<LoginRoute> {
            val viewModel = koinViewModel<LoginViewModel>()
            LoginScreen(viewModel = viewModel)
        }

        composable<EventsRoute> {
            if (sessionState == SessionState.Authenticated) {
                val viewModel = koinViewModel<EventsViewModel>()
                EventsScreen(viewModel = viewModel)
            }
        }
    }
}
