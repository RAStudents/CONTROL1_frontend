package studio.acmenra.control.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.model.SupportGrantInfo
import studio.acmenra.control.core.access.domain.model.TenantContextState

data class AppScaffoldUiState(
    val visibleItems: List<AppNavigationItem>,
    val currentCompanyId: String?,
    val currentCompanyName: String?,
    val currentCompanyStatus: CompanyStatus?,
    val selectedCompanyId: String?,
    val isRestricted: Boolean,
    val canSwitch: Boolean,
    val activeSupportGrant: SupportGrantInfo?,
    val isOnVendorDashboard: Boolean,
    val effectiveCompanyId: String?,
)

fun calculateAppScaffoldUiState(
    globalContext: GlobalUserContext?,
    tenantState: TenantContextState,
    destination: NavDestination?,
    items: List<AppNavigationItem>,
    routeCompanyId: String? = null,
): AppScaffoldUiState {
    val authorizedContext = (tenantState as? TenantContextState.Authorized)?.context
    val currentCompanyId = authorizedContext?.companyId
    val effectiveCompanyId =
        currentCompanyId?.ifBlank { null }
            ?: globalContext?.let { TenantAccessResolver.resolveFallbackCompanyId(it) }

    val visibleItems =
        if (tenantState is TenantContextState.Restricted.NoPermissions) {
            emptyList()
        } else if (authorizedContext != null) {
            AppNavigationItem.filterVisibleNavigationItems(
                items = items,
                authorizedContext = authorizedContext,
                globalContext = globalContext,
                effectiveCompanyId = effectiveCompanyId,
            )
        } else if (tenantState is TenantContextState.Loading && globalContext != null) {
            items
        } else {
            AppNavigationItem.filterVisibleNavigationItems(
                items = items,
                authorizedContext = authorizedContext,
                globalContext = globalContext,
                effectiveCompanyId = effectiveCompanyId,
            )
        }

    val pendingCompanyId =
        (tenantState as? TenantContextState.Loading)?.requestedCompanyId
            ?: (tenantState as? TenantContextState.Error)?.companyId
            ?: routeCompanyId

    val selectedCompanyId =
        authorizedContext?.companyId
            ?: (tenantState as? TenantContextState.Restricted)?.companyId
            ?: pendingCompanyId

    val currentCompanyName =
        authorizedContext?.companyName
            ?: (tenantState as? TenantContextState.Restricted)?.companyName
            ?: selectedCompanyId?.let { id ->
                globalContext?.memberCompanies?.firstOrNull { it.id == id }?.name
            }
    val currentCompanyStatus = authorizedContext?.companyStatus
    val isRestricted =
        tenantState is TenantContextState.Restricted ||
            currentCompanyStatus == CompanyStatus.RESTRICTED
    val canSwitch =
        globalContext != null &&
            (globalContext.hasMultipleCompanies || TenantAccessResolver.canBrowseAll(globalContext))

    val isSupportOverrideActive =
        authorizedContext?.accessStatus == AccessStatus.ACTIVE_SUPPORT_OVERRIDE &&
            authorizedContext.supportGrant != null &&
            !authorizedContext.isSupportGrantLocallyExpired
    val activeSupportGrant = if (isSupportOverrideActive) authorizedContext.supportGrant else null

    val isOnVendorDashboard =
        destination?.hasRoute<CompaniesDashboardRoute>() == true &&
            globalContext?.let(TenantAccessResolver::canBrowseAll) == true

    return AppScaffoldUiState(
        visibleItems = visibleItems,
        currentCompanyId = currentCompanyId,
        currentCompanyName = currentCompanyName,
        currentCompanyStatus = currentCompanyStatus,
        selectedCompanyId = selectedCompanyId,
        isRestricted = isRestricted,
        canSwitch = canSwitch,
        activeSupportGrant = activeSupportGrant,
        isOnVendorDashboard = isOnVendorDashboard,
        effectiveCompanyId = effectiveCompanyId,
    )
}
