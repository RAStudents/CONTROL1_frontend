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

fun NavController.navigateToEvents(companyId: String? = null) {
    navigate(EventsRoute(companyId = companyId)) {
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
