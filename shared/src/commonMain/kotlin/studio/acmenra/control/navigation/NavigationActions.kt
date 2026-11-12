package studio.acmenra.control.navigation

import androidx.navigation.NavController

fun NavController.navigateToLogin() {
    navigate(LoginRoute) {
        popUpTo<EventsRoute> {
            inclusive = true
        }
        launchSingleTop = true
    }
}

fun NavController.navigateToEvents() {
    navigate(EventsRoute) {
        popUpTo<LoginRoute> {
            inclusive = true
        }
        launchSingleTop = true
    }
}
