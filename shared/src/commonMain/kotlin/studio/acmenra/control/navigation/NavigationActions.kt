package studio.acmenra.control.navigation

import androidx.navigation.NavController

fun NavController.navigateToLogin() {
    navigate(LoginRoute) {
        popUpTo(graph.id) {
            inclusive = true
        }
        launchSingleTop = true
    }
}

fun NavController.navigateToEvents(
    companyId: String? = null,
    queryHash: String? = null,
) {
    navigate(EventsRoute(companyId = companyId, queryHash = queryHash)) {
        popUpTo(graph.id) {
            inclusive = true
        }
        launchSingleTop = true
    }
}

fun NavController.navigateToCompaniesDashboard() {
    navigate(CompaniesDashboardRoute) {
        popUpTo(graph.id) {
            inclusive = true
        }
        launchSingleTop = true
    }
}
