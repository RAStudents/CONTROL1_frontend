package studio.acmenra.control.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import studio.acmenra.control.core.access.domain.AccessManager
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.usecase.EnsureTenantContextUseCase
import studio.acmenra.control.core.access.domain.usecase.ObserveGlobalAccessUseCase
import studio.acmenra.control.core.access.domain.usecase.ObserveTenantContextUseCase
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.restricted_no_companies_desc
import studio.acmenra.control.core.ui.generated.resources.restricted_no_companies_title
import studio.acmenra.control.navigation.components.CompanySelectionDialog
import studio.acmenra.control.navigation.screen.AccessDeniedScreen
import studio.acmenra.control.navigation.screen.CompanyFrozenScreen
import studio.acmenra.control.navigation.screen.NoPermissionsScreen
import studio.acmenra.control.navigation.screen.TenantErrorScreen

@Composable
fun TenantRouteGuard(
    requestedCompanyId: String,
    modifier: Modifier = Modifier,
    requirement: RoutePermissionRequirement? = null,
    onNavigateToCompany: (companyId: String) -> Unit = {},
    onNavigateToCompaniesDashboard: () -> Unit = {},
    observeGlobalAccessUseCase: ObserveGlobalAccessUseCase = koinInject(),
    observeTenantContextUseCase: ObserveTenantContextUseCase = koinInject(),
    ensureTenantContextUseCase: EnsureTenantContextUseCase = koinInject(),
    accessManager: AccessManager = koinInject(),
    sessionManager: SessionManager = koinInject(),
    content: @Composable (AuthorizedTenantContext) -> Unit,
) {
    val globalState by observeGlobalAccessUseCase().collectAsState()
    val tenantState by observeTenantContextUseCase().collectAsState()
    val scope = rememberCoroutineScope()

    var showCompanyDialog by remember { mutableStateOf(false) }

    val readyContext = (globalState as? GlobalAccessState.Ready)?.context
    val memberCompanies = readyContext?.memberCompanies.orEmpty()
    val canBrowseAllCompanies = readyContext?.let { TenantAccessResolver.canBrowseAll(it) } == true
    val hasMultipleCompanies = readyContext?.hasMultipleCompanies == true
    val canSwitchCompany = hasMultipleCompanies || canBrowseAllCompanies

    if (showCompanyDialog) {
        val currentSelectedId =
            (tenantState as? TenantContextState.Authorized)?.context?.companyId ?: requestedCompanyId
        CompanySelectionDialog(
            companies = memberCompanies,
            selectedCompanyId = currentSelectedId,
            canBrowseAllCompanies = canBrowseAllCompanies,
            onSelectCompany = { companyId ->
                showCompanyDialog = false
                onNavigateToCompany(companyId)
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

    when (val global = globalState) {
        is GlobalAccessState.Idle, is GlobalAccessState.InitialLoading -> {
            LoadingPlaceholder(modifier)
            return
        }

        is GlobalAccessState.Restricted.AccountFrozen -> {
            CompanyFrozenScreen(
                restriction = global.restriction,
                companyName = null,
                canSwitchCompany = false,
                onLogout = onLogoutAction,
                isAccountLevel = true,
                modifier = modifier,
            )
            return
        }

        is GlobalAccessState.Restricted.NoCompaniesAvailable -> {
            AccessDeniedScreen(
                companyName = null,
                canSwitchCompany = false,
                onLogout = onLogoutAction,
                title = stringResource(Res.string.restricted_no_companies_title),
                description = stringResource(Res.string.restricted_no_companies_desc),
                modifier = modifier,
            )
            return
        }

        is GlobalAccessState.Error -> {
            TenantErrorScreen(
                error = global.error,
                canSwitchCompany = false,
                onRetry = {
                    scope.launch {
                        if (requestedCompanyId.isNotBlank()) {
                            ensureTenantContextUseCase(requestedCompanyId)
                        } else {
                            accessManager.refreshGlobalContext(force = true)
                        }
                    }
                },
                onLogout = onLogoutAction,
                modifier = modifier,
            )
            return
        }

        is GlobalAccessState.Ready -> {
            // Handled below
        }
    }

    val userContext = (globalState as GlobalAccessState.Ready).context

    if (requestedCompanyId.isBlank()) {
        val resolvedId = userContext.defaultCompanyId ?: userContext.memberCompanies.firstOrNull()?.id
        if (resolvedId != null) {
            LaunchedEffect(resolvedId) {
                onNavigateToCompany(resolvedId)
            }
            LoadingPlaceholder(modifier)
        } else if (TenantAccessResolver.canBrowseAll(userContext)) {
            LaunchedEffect(Unit) {
                onNavigateToCompaniesDashboard()
            }
            LoadingPlaceholder(modifier)
        } else {
            AccessDeniedScreen(
                companyName = null,
                canSwitchCompany = false,
                onLogout = onLogoutAction,
                modifier = modifier,
            )
        }
        return
    }

    val hasAccessToRequested = TenantAccessResolver.hasAccess(userContext, requestedCompanyId)
    if (!hasAccessToRequested) {
        val previousAuthorizedCompanyId = (tenantState as? TenantContextState.Authorized)?.context?.companyId
        val fallbackCompanyId =
            TenantAccessResolver.resolveFallbackCompanyId(
                userContext = userContext,
                previousAuthorizedCompanyId = previousAuthorizedCompanyId,
                excludedCompanyId = requestedCompanyId,
            )
        if (fallbackCompanyId != null && fallbackCompanyId != requestedCompanyId) {
            LaunchedEffect(requestedCompanyId, fallbackCompanyId) {
                onNavigateToCompany(fallbackCompanyId)
            }
            LoadingPlaceholder(modifier)
        } else if (TenantAccessResolver.canBrowseAll(userContext)) {
            LaunchedEffect(requestedCompanyId) {
                onNavigateToCompaniesDashboard()
            }
            LoadingPlaceholder(modifier)
        } else {
            AccessDeniedScreen(
                companyName = null,
                canSwitchCompany = false,
                onLogout = onLogoutAction,
                modifier = modifier,
            )
        }
        return
    }

    LaunchedEffect(requestedCompanyId) {
        ensureTenantContextUseCase(requestedCompanyId)
    }

    when (val state = tenantState) {
        is TenantContextState.NotSelected, is TenantContextState.Loading -> {
            LoadingPlaceholder(modifier)
        }

        is TenantContextState.Error -> {
            if (state.companyId != requestedCompanyId) {
                LoadingPlaceholder(modifier)
            } else {
                TenantErrorScreen(
                    error = state.error,
                    canSwitchCompany = canSwitchCompany,
                    onRetry = {
                        scope.launch {
                            ensureTenantContextUseCase(requestedCompanyId)
                        }
                    },
                    onSwitchCompany = onSwitchCompanyAction,
                    onLogout = onLogoutAction,
                    modifier = modifier,
                )
            }
        }

        is TenantContextState.Restricted.CompanyFrozen -> {
            if (state.companyId != requestedCompanyId) {
                LoadingPlaceholder(modifier)
            } else {
                CompanyFrozenScreen(
                    restriction = state.restriction,
                    companyName = state.companyName,
                    canSwitchCompany = canSwitchCompany,
                    onSwitchCompany = onSwitchCompanyAction,
                    onLogout = onLogoutAction,
                    modifier = modifier,
                )
            }
        }

        is TenantContextState.Restricted.NoPermissions -> {
            if (state.companyId != requestedCompanyId) {
                LoadingPlaceholder(modifier)
            } else {
                NoPermissionsScreen(
                    companyName = state.companyName,
                    canSwitchCompany = canSwitchCompany,
                    onSwitchCompany = onSwitchCompanyAction,
                    onLogout = onLogoutAction,
                    modifier = modifier,
                )
            }
        }

        is TenantContextState.Restricted.AccessDenied -> {
            if (state.companyId != requestedCompanyId) {
                LoadingPlaceholder(modifier)
            } else {
                AccessDeniedScreen(
                    companyName = state.companyName,
                    canSwitchCompany = canSwitchCompany,
                    onSwitchCompany = onSwitchCompanyAction,
                    onLogout = onLogoutAction,
                    modifier = modifier,
                )
            }
        }

        is TenantContextState.Authorized -> {
            if (state.context.companyId != requestedCompanyId) {
                LoadingPlaceholder(modifier)
            } else if (requirement != null && !requirement.isSatisfiedBy(state.context)) {
                NoPermissionsScreen(
                    companyName = state.context.companyName,
                    canSwitchCompany = canSwitchCompany,
                    onSwitchCompany = onSwitchCompanyAction,
                    onLogout = onLogoutAction,
                    modifier = modifier,
                )
            } else {
                key(state.context.companyId) {
                    content(state.context)
                }
            }
        }
    }
}

@Composable
private fun LoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
