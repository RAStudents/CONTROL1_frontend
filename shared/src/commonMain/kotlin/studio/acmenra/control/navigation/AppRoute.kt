package studio.acmenra.control.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

sealed interface AppRoute

@Serializable
@SerialName("login")
data object LoginRoute : AppRoute

@Serializable
@SerialName("companies_dashboard")
data object CompaniesDashboardRoute : AppRoute

sealed interface TenantRoute : AppRoute {
    val companyId: String
}

@Serializable
@SerialName("events")
data class EventsRoute(
    override val companyId: String = "",
) : TenantRoute
