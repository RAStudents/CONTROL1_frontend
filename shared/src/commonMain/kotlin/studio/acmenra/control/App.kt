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
import studio.acmenra.control.feature.events.domain.model.EventsQueryRouteCodec
import studio.acmenra.control.navigation.AppNavHost
import studio.acmenra.control.navigation.AppScaffold
import studio.acmenra.control.navigation.EventsRoute
import studio.acmenra.control.navigation.LoginRoute

@Composable
fun App(
    modifier: Modifier = Modifier,
    urlHash: String? = null,
    onNavHostReady: suspend (NavController) -> Unit = {},
    onUpdateUrlHash: ((companyId: String, queryHash: String?) -> Unit)? = null,
) {
    val sessionManager = koinInject<SessionManager>()
    val sessionState by sessionManager.sessionState.collectAsState()

    AppContent(
        sessionState = sessionState,
        modifier = modifier,
        urlHash = urlHash,
        onNavHostReady = onNavHostReady,
        onUpdateUrlHash = onUpdateUrlHash,
    )
}

@Composable
fun AppContent(
    sessionState: SessionState,
    modifier: Modifier = Modifier,
    urlHash: String? = null,
    onNavHostReady: suspend (NavController) -> Unit = {},
    onUpdateUrlHash: ((companyId: String, queryHash: String?) -> Unit)? = null,
) {
    AmberTheme {
        val navController = rememberNavController()

        val startDestination =
            remember {
                if (sessionState == SessionState.Authenticated) {
                    val isEventsUrl =
                        !urlHash.isNullOrBlank() &&
                            (urlHash.startsWith("#events") || urlHash.startsWith("events"))
                    if (isEventsUrl) {
                        val (company, _) = EventsQueryRouteCodec.decodeHash(urlHash)
                        EventsRoute(companyId = company, queryHash = urlHash)
                    } else {
                        EventsRoute()
                    }
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
                    modifier = modifier.fillMaxSize(),
                ) {
                    AppNavHost(
                        navController = navController,
                        startDestination = startDestination,
                        sessionState = sessionState,
                        modifier = Modifier.fillMaxSize(),
                        urlHash = urlHash,
                        onUpdateUrlHash = onUpdateUrlHash,
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
