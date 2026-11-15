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
import androidx.navigation.toRoute
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.TenantPermission
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.feature.auth.presentation.LoginRouteContent
import studio.acmenra.control.feature.events.presentation.EventsRouteContent

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
                destination?.hasRoute<LoginRoute>() != true -> {
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
            LoginRouteContent()
        }

        composable<EventsRoute> { entry ->
            val route = entry.toRoute<EventsRoute>()
            if (sessionState == SessionState.Authenticated) {
                TenantRouteGuard(
                    requestedCompanyId = route.companyId,
                    requirement = RoutePermissionRequirement.Tenant(TenantPermission.EVENTS_READ),
                    onNavigateToCompany = { newCompanyId ->
                        navController.navigate(EventsRoute(companyId = newCompanyId)) {
                            popUpTo<EventsRoute> {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToCompaniesDashboard = {
                        navController.navigate(CompaniesDashboardRoute) {
                            popUpTo<EventsRoute> {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                ) { tenantContext ->
                    EventsRouteContent(companyId = tenantContext.companyId)
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        composable<CompaniesDashboardRoute> {
            if (sessionState == SessionState.Authenticated) {
                VendorRouteGuard(
                    onAccessDenied = {
                        navController.navigate(EventsRoute()) {
                            popUpTo<CompaniesDashboardRoute> {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
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
