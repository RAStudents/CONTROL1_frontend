package studio.acmenra.control

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.bindToBrowserNavigation
import androidx.navigation.toRoute
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event
import studio.acmenra.control.di.initKoin
import studio.acmenra.control.feature.events.domain.model.EventsQueryRouteCodec
import studio.acmenra.control.navigation.CompaniesDashboardRoute
import studio.acmenra.control.navigation.EventsRoute
import studio.acmenra.control.navigation.LoginRoute

@OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalBrowserHistoryApi::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
)
fun main() {
    initKoin()

    val body =
        document.body
            ?: error("Unable to initialize application: document.body is null")

    ComposeViewport(body) {
        var currentHash by remember { mutableStateOf(window.location.hash) }

        DisposableEffect(Unit) {
            val handleHashChange: (Event) -> Unit = {
                currentHash = window.location.hash
            }
            window.addEventListener("popstate", handleHashChange)
            window.addEventListener("hashchange", handleHashChange)
            onDispose {
                window.removeEventListener("popstate", handleHashChange)
                window.removeEventListener("hashchange", handleHashChange)
            }
        }

        fun updateBrowserHash(hash: String) {
            window.history.replaceState(null, "", hash)
        }

        App(
            urlHash = currentHash,
            onNavHostReady = { navController ->
                navController.bindToBrowserNavigation { entry ->
                    val targetHash =
                        if (entry.destination.hasRoute<EventsRoute>()) {
                            val route = entry.toRoute<EventsRoute>()
                            val cleanCompany = route.cleanCompanyId
                            val browserHash = window.location.hash
                            val decodedCompany =
                                if (browserHash.isNotBlank()) {
                                    EventsQueryRouteCodec.decodeHash(browserHash).first
                                } else {
                                    null
                                }
                            val isSameCompany =
                                cleanCompany == null || decodedCompany == null || cleanCompany == decodedCompany

                            val isEventsHash =
                                browserHash.startsWith("#events") || browserHash.startsWith("events")
                            if (isSameCompany && isEventsHash) {
                                if (browserHash.startsWith("#")) browserHash else "#$browserHash"
                            } else {
                                val validRouteHash =
                                    route.queryHash?.takeIf { q ->
                                        val qCompany = EventsQueryRouteCodec.decodeHash(q).first
                                        cleanCompany == null || qCompany == cleanCompany
                                    }
                                if (!validRouteHash.isNullOrBlank()) {
                                    if (validRouteHash.startsWith("#")) validRouteHash else "#$validRouteHash"
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
                    targetHash
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
                updateBrowserHash(hash)
            },
        )
    }
}
