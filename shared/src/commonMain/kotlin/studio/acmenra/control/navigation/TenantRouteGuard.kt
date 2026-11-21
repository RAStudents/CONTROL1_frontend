package studio.acmenra.control.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.usecase.EnsureTenantContextUseCase
import studio.acmenra.control.core.access.domain.usecase.ObserveGlobalAccessUseCase
import studio.acmenra.control.core.access.domain.usecase.ObserveTenantContextUseCase
import studio.acmenra.control.core.access.domain.usecase.RefreshGlobalAccessUseCase
import studio.acmenra.control.core.access.domain.usecase.RefreshTenantUseCase
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.restricted_no_companies_desc
import studio.acmenra.control.core.ui.generated.resources.restricted_no_companies_title
import studio.acmenra.control.navigation.components.CompanySelectionDialog
import studio.acmenra.control.navigation.components.LoadingPlaceholder
import studio.acmenra.control.navigation.screen.AccessDeniedScreen
import studio.acmenra.control.navigation.screen.CompanyFrozenScreen
import studio.acmenra.control.navigation.screen.NoPermissionsScreen
import studio.acmenra.control.navigation.screen.TenantErrorScreen

@Composable
fun TenantRouteGuard(
    requestedCompanyId: String?,
    modifier: Modifier = Modifier,
    requirement: RoutePermissionRequirement? = null,
    onNavigateToCompany: (companyId: String) -> Unit = {},
    onNavigateToCompaniesDashboard: () -> Unit = {},
    observeGlobalAccessUseCase: ObserveGlobalAccessUseCase = koinInject(),
    observeTenantContextUseCase: ObserveTenantContextUseCase = koinInject(),
    ensureTenantContextUseCase: EnsureTenantContextUseCase = koinInject(),
    refreshTenantUseCase: RefreshTenantUseCase = koinInject(),
    refreshGlobalAccessUseCase: RefreshGlobalAccessUseCase = koinInject(),
    sessionManager: SessionManager = koinInject(),
    content: @Composable (AuthorizedTenantContext) -> Unit,
) {
    val globalState by observeGlobalAccessUseCase().collectAsState()
    val tenantState by observeTenantContextUseCase().collectAsState()
    val scope = rememberCoroutineScope()

    var showCompanyDialog by remember { mutableStateOf(false) }

    val targetCompanyId = requestedCompanyId?.takeUnless { it.isBlank() || it == "null" }
    val readyContext = (globalState as? GlobalAccessState.Ready)?.context
    val memberCompanies = readyContext?.memberCompanies.orEmpty()
    val canBrowseAllCompanies = readyContext?.let { TenantAccessResolver.canBrowseAll(it) } == true
    val hasMultipleCompanies = readyContext?.hasMultipleCompanies == true
    val canSwitchCompany = hasMultipleCompanies || canBrowseAllCompanies

    if (showCompanyDialog) {
        val currentSelectedId =
            (tenantState as? TenantContextState.Authorized)?.context?.companyId ?: targetCompanyId.orEmpty()
        CompanySelectionDialog(
            companies = memberCompanies,
            selectedCompanyId = currentSelectedId,
            canBrowseAllCompanies = canBrowseAllCompanies,
            onSelectCompany = { companyId ->
                showCompanyDialog = false
                if (companyId != currentSelectedId) {
                    onNavigateToCompany(companyId)
                }
            },
            onSelectAllCompanies = {
                showCompanyDialog = false
                onNavigateToCompaniesDashboard()
            },
            onDismiss = { showCompanyDialog = false },
        )
    }

    val onSwitchCompanyAction: (() -> Unit)? =
        if (canSwitchCompany && (memberCompanies.isNotEmpty() || canBrowseAllCompanies)) {
            { showCompanyDialog = true }
        } else {
            null
        }

    val onLogoutAction: () -> Unit = {
        scope.launch {
            sessionManager.logout()
        }
    }

    val onRetryTenantAction: () -> Unit = {
        scope.launch {
            if (!targetCompanyId.isNullOrBlank()) {
                ensureTenantContextUseCase(targetCompanyId)
                refreshTenantUseCase(force = true)
            } else {
                refreshGlobalAccessUseCase(force = true)
            }
        }
    }

    val decision =
        resolveTenantGuardDecision(
            globalState = globalState,
            tenantState = tenantState,
            requestedCompanyId = targetCompanyId,
            requirement = requirement,
        )

    val hasAccess = readyContext?.let { TenantAccessResolver.hasAccess(it, targetCompanyId) } == true
    val isTenantNotSelected = tenantState is TenantContextState.NotSelected

    LaunchedEffect(targetCompanyId, hasAccess, isTenantNotSelected) {
        if (hasAccess && !targetCompanyId.isNullOrBlank()) {
            ensureTenantContextUseCase(targetCompanyId)
        }
    }

    when (decision) {
        is TenantGuardDecision.ShowLoading -> {
            LoadingPlaceholder(modifier)
        }

        is TenantGuardDecision.AccountFrozen -> {
            CompanyFrozenScreen(
                restriction = decision.restriction,
                companyName = null,
                canSwitchCompany = false,
                onLogout = onLogoutAction,
                isAccountLevel = true,
                modifier = modifier,
            )
        }

        is TenantGuardDecision.NoCompaniesAvailable -> {
            AccessDeniedScreen(
                companyName = null,
                canSwitchCompany = false,
                onLogout = onLogoutAction,
                title = stringResource(Res.string.restricted_no_companies_title),
                description = stringResource(Res.string.restricted_no_companies_desc),
                modifier = modifier,
            )
        }

        is TenantGuardDecision.GlobalError -> {
            TenantErrorScreen(
                error = decision.error,
                canSwitchCompany = false,
                onRetry = {
                    scope.launch {
                        refreshGlobalAccessUseCase(force = true)
                    }
                },
                onLogout = onLogoutAction,
                modifier = modifier,
            )
        }

        is TenantGuardDecision.RedirectToCompany -> {
            LaunchedEffect(decision.targetCompanyId) {
                onNavigateToCompany(decision.targetCompanyId)
            }
            LoadingPlaceholder(modifier)
        }

        is TenantGuardDecision.RedirectToVendorDashboard -> {
            LaunchedEffect(Unit) {
                onNavigateToCompaniesDashboard()
            }
            LoadingPlaceholder(modifier)
        }

        is TenantGuardDecision.AccessDenied -> {
            AccessDeniedScreen(
                companyName = null,
                canSwitchCompany = false,
                onLogout = onLogoutAction,
                modifier = modifier,
            )
        }

        is TenantGuardDecision.TenantError -> {
            TenantErrorScreen(
                error = decision.error,
                canSwitchCompany = canSwitchCompany,
                onRetry = onRetryTenantAction,
                onSwitchCompany = onSwitchCompanyAction,
                onLogout = onLogoutAction,
                modifier = modifier,
            )
        }

        is TenantGuardDecision.CompanyFrozen -> {
            CompanyFrozenScreen(
                restriction = decision.restriction,
                companyName = decision.companyName,
                canSwitchCompany = canSwitchCompany,
                onRetry = onRetryTenantAction,
                onSwitchCompany = onSwitchCompanyAction,
                onLogout = onLogoutAction,
                modifier = modifier,
            )
        }

        is TenantGuardDecision.NoPermissions -> {
            NoPermissionsScreen(
                companyName = decision.companyName,
                canSwitchCompany = canSwitchCompany,
                onRetry = onRetryTenantAction,
                onSwitchCompany = onSwitchCompanyAction,
                onLogout = onLogoutAction,
                modifier = modifier,
            )
        }

        is TenantGuardDecision.TenantAccessDenied -> {
            AccessDeniedScreen(
                companyName = decision.companyName,
                canSwitchCompany = canSwitchCompany,
                onRetry = onRetryTenantAction,
                onSwitchCompany = onSwitchCompanyAction,
                onLogout = onLogoutAction,
                modifier = modifier,
            )
        }

        is TenantGuardDecision.RenderContent -> {
            Box(modifier = modifier) {
                key(decision.context.companyId) {
                    content(decision.context)
                }
            }
        }
    }
}
