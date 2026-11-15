package studio.acmenra.control.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import studio.acmenra.control.navigation.components.LoadingPlaceholder
import studio.acmenra.control.navigation.screen.CompaniesDashboardScreen

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
                destination != null &&
                !destination.hasRoute<LoginRoute>() -> {
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
                    requestedCompanyId = route.cleanCompanyId,
                    requirement = RoutePermissionRequirement.Tenant(TenantPermission.EVENTS_READ),
                    onNavigateToCompany = { newCompanyId ->
                        navController.navigateToEvents(newCompanyId)
                    },
                    onNavigateToCompaniesDashboard = {
                        navController.navigateToCompaniesDashboard()
                    },
                ) { tenantContext ->
                    EventsRouteContent(companyId = tenantContext.companyId)
                }
            } else {
                LoadingPlaceholder(modifier = Modifier.fillMaxSize())
            }
        }

        composable<CompaniesDashboardRoute> {
            if (sessionState == SessionState.Authenticated) {
                VendorRouteGuard(
                    onAccessDenied = {
                        navController.navigateToEvents()
                    },
                ) {
                    CompaniesDashboardScreen(
                        onSelectCompany = { companyId ->
                            navController.navigateToEvents(companyId)
                        },
                    )
                }
            } else {
                LoadingPlaceholder(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
