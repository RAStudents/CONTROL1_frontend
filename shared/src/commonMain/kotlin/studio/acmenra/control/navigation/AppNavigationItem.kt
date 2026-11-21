package studio.acmenra.control.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.TenantPermission
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.ic_nav_events
import studio.acmenra.control.core.ui.generated.resources.nav_item_events

sealed interface AppNavigationItem {
    val labelRes: StringResource
    val iconRes: DrawableResource
    val requiredPermission: RoutePermissionRequirement? get() = null

    fun isSelected(destination: NavDestination?): Boolean

    fun navigate(
        navController: NavController,
        companyId: String? = null,
    )

    data object Events : AppNavigationItem {
        override val labelRes: StringResource = Res.string.nav_item_events
        override val iconRes: DrawableResource = Res.drawable.ic_nav_events
        override val requiredPermission: RoutePermissionRequirement =
            RoutePermissionRequirement.Tenant(TenantPermission.EVENTS_READ)

        override fun isSelected(destination: NavDestination?): Boolean = destination?.hasRoute<EventsRoute>() == true

        override fun navigate(
            navController: NavController,
            companyId: String?,
        ) {
            navController.navigateToEvents(companyId = companyId)
        }
    }

    companion object {
        val DEFAULT_ITEMS: List<AppNavigationItem> by lazy { listOf(Events) }

        fun filterVisibleNavigationItems(
            items: List<AppNavigationItem>,
            authorizedContext: AuthorizedTenantContext?,
            globalContext: GlobalUserContext? = null,
            effectiveCompanyId: String? =
                authorizedContext?.companyId
                    ?: globalContext?.let(TenantAccessResolver::resolveFallbackCompanyId),
        ): List<AppNavigationItem> =
            items.filter { item ->
                val perm = item.requiredPermission
                when {
                    perm == null -> true
                    authorizedContext != null -> perm.isSatisfiedBy(authorizedContext)
                    globalContext != null && TenantAccessResolver.canBrowseAll(globalContext) ->
                        !effectiveCompanyId.isNullOrBlank()
                    else -> false
                }
            }
    }
}
