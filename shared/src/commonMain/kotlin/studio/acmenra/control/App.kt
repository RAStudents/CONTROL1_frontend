package studio.acmenra.control

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import org.koin.compose.koinInject
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.navigation.AppNavHost
import studio.acmenra.control.navigation.EventsRoute
import studio.acmenra.control.navigation.LoginRoute

@Composable
fun App(
    modifier: Modifier = Modifier,
    onNavHostReady: suspend (NavController) -> Unit = {},
) {
    val sessionManager = koinInject<SessionManager>()
    val sessionState by sessionManager.sessionState.collectAsState()

    AppContent(
        sessionState = sessionState,
        modifier = modifier,
        onNavHostReady = onNavHostReady,
    )
}

@Composable
fun AppContent(
    sessionState: SessionState,
    modifier: Modifier = Modifier,
    onNavHostReady: suspend (NavController) -> Unit = {},
) {
    MaterialTheme {
        val navController = rememberNavController()

        val startDestination =
            remember {
                if (sessionState == SessionState.Authenticated) {
                    EventsRoute
                } else {
                    LoginRoute
                }
            }

        AppNavHost(
            navController = navController,
            startDestination = startDestination,
            sessionState = sessionState,
            modifier = modifier,
        )

        LaunchedEffect(navController) {
            onNavHostReady(navController)
        }
    }
}
