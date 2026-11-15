package studio.acmenra.control.navigation

import androidx.navigation.NavController

fun NavController.navigateToLogin() {
    navigate(LoginRoute) {
        popUpTo<LoginRoute> {
            inclusive = true
        }
        launchSingleTop = true
    }
}

fun NavController.navigateToEvents(companyId: String = "") {
    navigate(EventsRoute(companyId = companyId)) {
        popUpTo<LoginRoute> {
            inclusive = true
        }
        launchSingleTop = true
    }
}

fun NavController.navigateToCompaniesDashboard() {
    navigate(CompaniesDashboardRoute) {
        popUpTo<LoginRoute> {
            inclusive = true
        }
        launchSingleTop = true
    }
}
