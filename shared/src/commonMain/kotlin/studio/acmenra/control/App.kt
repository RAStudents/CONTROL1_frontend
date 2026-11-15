package studio.acmenra.control

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import org.koin.compose.koinInject
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.ui.notice.ConnectivityNotices
import studio.acmenra.control.core.ui.notice.NoticeHost
import studio.acmenra.control.core.ui.theme.AmberTheme
import studio.acmenra.control.navigation.AppNavHost
import studio.acmenra.control.navigation.AppScaffold
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
    AmberTheme {
        val navController = rememberNavController()

        val startDestination =
            remember {
                if (sessionState == SessionState.Authenticated) {
                    EventsRoute()
                } else {
                    LoginRoute
                }
            }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppScaffold(
                    navController = navController,
                    sessionState = sessionState,
                    modifier = modifier,
                ) {
                    AppNavHost(
                        navController = navController,
                        startDestination = startDestination,
                        sessionState = sessionState,
                    )
                }
                NoticeHost(modifier = Modifier.align(Alignment.TopCenter))
            }
        }

        ConnectivityNotices()

        LaunchedEffect(navController) {
            onNavHostReady(navController)
        }
    }
}
