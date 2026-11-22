package studio.acmenra.control.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.TenantPermission
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.feature.auth.presentation.LoginRouteContent
import studio.acmenra.control.feature.events.domain.model.EventsAccessCapabilities
import studio.acmenra.control.feature.events.domain.model.EventsQueryRouteCodec
import studio.acmenra.control.feature.events.presentation.EventsRouteContent
import studio.acmenra.control.navigation.components.LoadingPlaceholder
import studio.acmenra.control.navigation.screen.CompaniesDashboardScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: Any,
    sessionState: SessionState,
    modifier: Modifier = Modifier,
    urlHash: String? = null,
    onUpdateUrlHash: ((companyId: String, queryHash: String?) -> Unit)? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    LaunchedEffect(sessionState, destination) {
        when {
            sessionState == SessionState.Authenticated &&
                destination?.hasRoute<LoginRoute>() == true -> {
                val isEventsUrl =
                    !urlHash.isNullOrBlank() &&
                        (urlHash.startsWith("#events") || urlHash.startsWith("events"))
                if (isEventsUrl) {
                    val (company, _) = EventsQueryRouteCodec.decodeHash(urlHash)
                    navController.navigateToEvents(companyId = company, queryHash = urlHash)
                } else {
                    navController.navigateToEvents()
                }
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
        modifier = modifier.fillMaxSize(),
    ) {
        composable<LoginRoute> {
            LoginRouteContent()
        }

        composable<EventsRoute>(
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) { entry ->
            val route = entry.toRoute<EventsRoute>()
            val rawUrlHash =
                if (!urlHash.isNullOrBlank() && (urlHash.startsWith("#events") || urlHash.startsWith("events"))) {
                    urlHash
                } else {
                    null
                }
            val decodedFromUrlHash =
                rawUrlHash?.let {
                    EventsQueryRouteCodec.decodeHash(it).first
                }

            val isCompanyMatching =
                route.cleanCompanyId == null || route.cleanCompanyId == decodedFromUrlHash

            val validRouteHash =
                route.queryHash?.takeIf { hash ->
                    val decodedCompany = EventsQueryRouteCodec.decodeHash(hash).first
                    route.cleanCompanyId == null || decodedCompany == route.cleanCompanyId
                }

            val effectiveQueryHash =
                when {
                    rawUrlHash != null && isCompanyMatching -> rawUrlHash
                    validRouteHash != null -> validRouteHash
                    else -> null
                }

            val decodedCompany =
                if (!effectiveQueryHash.isNullOrBlank()) {
                    EventsQueryRouteCodec.decodeHash(effectiveQueryHash).first
                } else {
                    null
                }
            val effectiveCompanyId = route.cleanCompanyId ?: decodedCompany

            if (sessionState == SessionState.Authenticated) {
                TenantRouteGuard(
                    requestedCompanyId = effectiveCompanyId,
                    requirement = RoutePermissionRequirement.Tenant(TenantPermission.EVENTS_READ),
                    onNavigateToCompany = { newCompanyId ->
                        navController.navigateToEvents(newCompanyId, null)
                    },
                    onNavigateToCompaniesDashboard = {
                        navController.navigateToCompaniesDashboard()
                    },
                ) { tenantContext ->
                    val perms = tenantContext.effectiveTenantPermissions
                    val isSupportOverride = tenantContext.accessStatus == AccessStatus.ACTIVE_SUPPORT_OVERRIDE
                    val accessCapabilities =
                        EventsAccessCapabilities(
                            eventsRead = perms.contains(TenantPermission.EVENTS_READ),
                            eventsReview =
                                !isSupportOverride &&
                                    perms.contains(TenantPermission.EVENTS_READ) &&
                                    perms.contains(TenantPermission.EVENTS_REVIEW),
                            reportsExport =
                                !isSupportOverride &&
                                    perms.contains(TenantPermission.EVENTS_READ) &&
                                    perms.contains(TenantPermission.REPORTS_EXPORT),
                        )
                    LaunchedEffect(tenantContext.companyId, effectiveQueryHash, isCompanyMatching) {
                        if (onUpdateUrlHash != null && !isCompanyMatching) {
                            onUpdateUrlHash(tenantContext.companyId, effectiveQueryHash)
                        }
                    }

                    EventsRouteContent(
                        companyId = tenantContext.companyId,
                        accessCapabilities = accessCapabilities,
                        queryHash = effectiveQueryHash,
                        onQueryHashChanged = { newHash ->
                            if (onUpdateUrlHash != null) {
                                onUpdateUrlHash(tenantContext.companyId, newHash)
                            } else if (newHash != route.queryHash) {
                                navController.navigate(
                                    EventsRoute(
                                        companyId = tenantContext.companyId,
                                        queryHash = newHash,
                                    ),
                                ) {
                                    launchSingleTop = true
                                    popUpTo<EventsRoute> {
                                        inclusive = true
                                    }
                                }
                            }
                        },
                    )
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
