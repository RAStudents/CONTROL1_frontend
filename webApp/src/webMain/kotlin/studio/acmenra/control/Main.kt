package studio.acmenra.control

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.bindToBrowserNavigation
import androidx.navigation.toRoute
import kotlinx.browser.document
import kotlinx.browser.window
import studio.acmenra.control.di.initKoin
import studio.acmenra.control.navigation.CompaniesDashboardRoute
import studio.acmenra.control.navigation.EventsRoute
import studio.acmenra.control.navigation.LoginRoute

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    initKoin()

    val body =
        document.body
            ?: error("Unable to initialize application: document.body is null")

    ComposeViewport(body) {
        val initialHash = remember { window.location.hash }

        App(
            urlHash = initialHash,
            onNavHostReady = { navController ->
                navController.bindToBrowserNavigation { entry ->
                    if (entry.destination.hasRoute<EventsRoute>()) {
                        val hash = window.location.hash
                        if (hash.startsWith("#events") || hash.startsWith("events")) {
                            if (hash.startsWith("#")) hash else "#$hash"
                        } else {
                            val route = entry.toRoute<EventsRoute>()
                            val cleanCompany = route.cleanCompanyId
                            val q = route.queryHash
                            if (!q.isNullOrBlank()) {
                                if (q.startsWith("#")) q else "#$q"
                            } else {
                                "#events?companyId=${cleanCompany ?: "null"}"
                            }
                        }
                    } else if (entry.destination.hasRoute<LoginRoute>()) {
                        "#login"
                    } else if (entry.destination.hasRoute<CompaniesDashboardRoute>()) {
                        "#companies_dashboard"
                    } else {
                        val route = entry.destination.route?.substringBefore("?") ?: ""
                        "#$route"
                    }
                }
            },
            onUpdateUrlHash = { companyId, queryHash ->
                val hash =
                    if (queryHash.isNullOrBlank()) {
                        val cleanCompany = companyId.takeUnless { it.isBlank() || it == "null" }
                        "#events?companyId=${cleanCompany ?: "null"}"
                    } else if (queryHash.startsWith("#")) {
                        queryHash
                    } else if (queryHash.startsWith("events?")) {
                        "#$queryHash"
                    } else {
                        val cleanCompany = companyId.takeUnless { it.isBlank() || it == "null" }
                        "#events?companyId=${cleanCompany ?: "null"}&$queryHash"
                    }
                window.history.replaceState(null, "", hash)
            },
        )
    }
}
