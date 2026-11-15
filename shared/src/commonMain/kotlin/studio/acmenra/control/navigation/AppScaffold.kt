package studio.acmenra.control.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import studio.acmenra.control.core.access.domain.model.AccessStatus
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.usecase.ObserveGlobalAccessUseCase
import studio.acmenra.control.core.access.domain.usecase.ObserveTenantContextUseCase
import studio.acmenra.control.core.access.domain.usecase.SelectTenantUseCase
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.ui.components.AmberNavigationBar
import studio.acmenra.control.core.ui.components.AmberNavigationBarItem
import studio.acmenra.control.core.ui.components.AmberNavigationRail
import studio.acmenra.control.core.ui.components.AmberNavigationRailItem
import studio.acmenra.control.core.ui.components.AmberTopAppBar
import studio.acmenra.control.core.ui.components.LocalTopBarState
import studio.acmenra.control.core.ui.components.TopBarState
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.company_switcher_all_vendor
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.navigation.components.CompanySelectionDialog
import studio.acmenra.control.navigation.components.DesktopCompanySelector
import studio.acmenra.control.navigation.components.MobileCompanyIconButton
import studio.acmenra.control.navigation.components.SupportOverrideBanner

@Composable
fun AppScaffold(
    navController: NavHostController,
    sessionState: SessionState,
    modifier: Modifier = Modifier,
    items: List<AppNavigationItem> = AppNavigationItem.DEFAULT_ITEMS,
    observeTenantContextUseCase: ObserveTenantContextUseCase = koinInject(),
    observeGlobalAccessUseCase: ObserveGlobalAccessUseCase = koinInject(),
    selectTenantUseCase: SelectTenantUseCase = koinInject(),
    content: @Composable () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val isLoginDestination = destination?.hasRoute<LoginRoute>() == true
    val showNavigation = sessionState == SessionState.Authenticated && destination != null && !isLoginDestination
    val currentContent by rememberUpdatedState(content)
    val movableContent = remember { movableContentOf { currentContent() } }

    val globalState by observeGlobalAccessUseCase().collectAsState()
    val tenantState by observeTenantContextUseCase().collectAsState()
    val scope = rememberCoroutineScope()

    val topBarState = remember { TopBarState() }
    var isCompanyDialogOpen by remember { mutableStateOf(false) }

    if (!showNavigation) {
        Box(modifier = modifier.fillMaxSize()) {
            movableContent()
        }
        return
    }

    val globalContext = (globalState as? GlobalAccessState.Ready)?.context
    val authorizedContext = (tenantState as? TenantContextState.Authorized)?.context

    val visibleItems =
        remember(authorizedContext, items) {
            items.filter { item ->
                val perm = item.requiredPermission
                perm == null || (authorizedContext != null && perm.isSatisfiedBy(authorizedContext))
            }
        }

    val currentCompanyId = authorizedContext?.companyId.orEmpty()
    val currentCompanyName =
        authorizedContext?.companyName
            ?: (tenantState as? TenantContextState.Restricted)?.companyName
    val currentCompanyStatus = authorizedContext?.companyStatus
    val selectedCompanyId = authorizedContext?.companyId ?: (tenantState as? TenantContextState.Restricted)?.companyId
    val isRestricted =
        tenantState is TenantContextState.Restricted ||
            currentCompanyStatus == CompanyStatus.RESTRICTED
    val canSwitch = globalContext != null && globalContext.hasMultipleCompanies

    val isSupportOverrideActive =
        authorizedContext?.accessStatus == AccessStatus.ACTIVE_SUPPORT_OVERRIDE &&
            authorizedContext.supportGrant != null &&
            !authorizedContext.isSupportGrantLocallyExpired
    val activeSupportGrant = if (isSupportOverrideActive) authorizedContext.supportGrant else null

    val isOnVendorDashboard =
        destination.hasRoute<CompaniesDashboardRoute>() &&
            globalContext?.canBrowseAllCompanies == true
    val currentDestinationItem = visibleItems.firstOrNull { it.isSelected(destination) }
    val defaultTitle =
        when {
            isOnVendorDashboard -> stringResource(Res.string.company_switcher_all_vendor)
            currentDestinationItem != null -> stringResource(currentDestinationItem.labelRes)
            else -> ""
        }
    val title = topBarState.title ?: defaultTitle

    val displayCompanyName =
        if (isOnVendorDashboard) {
            stringResource(Res.string.company_switcher_all_vendor)
        } else {
            currentCompanyName.orEmpty()
        }
    val displayIsRestricted = if (isOnVendorDashboard) false else isRestricted
    val effectiveSelectedCompanyId = if (isOnVendorDashboard) null else selectedCompanyId
    val effectiveCompanyId =
        currentCompanyId.ifBlank {
            globalContext?.defaultCompanyId ?: globalContext
                ?.memberCompanies
                ?.firstOrNull()
                ?.id
                .orEmpty()
        }

    CompositionLocalProvider(LocalTopBarState provides topBarState) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val isDesktopOrTablet = maxWidth >= AmberDimens.TabletBreakpoint

            if (isDesktopOrTablet) {
                Row(modifier = Modifier.fillMaxSize()) {
                    AmberNavigationRail {
                        visibleItems.forEach { item ->
                            val selected = item.isSelected(destination)
                            AmberNavigationRailItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        item.navigate(navController, companyId = effectiveCompanyId)
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(item.iconRes),
                                        contentDescription = null,
                                        modifier = Modifier.size(AmberDimens.ListIcon),
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                            )
                        }
                    }

                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                    ) {
                        AmberTopAppBar(
                            title = title,
                            leadingAction = topBarState.leadingAction,
                            actions = topBarState.actions,
                            desktopCompanyBox =
                                if (globalContext != null) {
                                    {
                                        DesktopCompanySelector(
                                            currentCompanyName = displayCompanyName,
                                            isRestricted = displayIsRestricted,
                                            canSwitch = canSwitch,
                                            companies = globalContext.memberCompanies,
                                            selectedCompanyId = effectiveSelectedCompanyId,
                                            canBrowseAllCompanies = globalContext.canBrowseAllCompanies,
                                            onSelectCompany = { companyId ->
                                                scope.launch { selectTenantUseCase(companyId) }
                                                navController.navigateToEvents(companyId)
                                            },
                                            onSelectAllCompanies = {
                                                navController.navigateToCompaniesDashboard()
                                            },
                                            onOpenFullDialog = {
                                                isCompanyDialogOpen = true
                                            },
                                        )
                                    }
                                } else {
                                    null
                                },
                            mobileCompanyAction = null,
                        )

                        if (activeSupportGrant != null) {
                            SupportOverrideBanner(grant = activeSupportGrant)
                        }

                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                        ) {
                            movableContent()
                        }
                    }
                }
            } else {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    topBar = {
                        AmberTopAppBar(
                            title = title,
                            leadingAction = topBarState.leadingAction,
                            actions = topBarState.actions,
                            desktopCompanyBox = null,
                            mobileCompanyAction =
                                if (globalContext != null) {
                                    {
                                        MobileCompanyIconButton(
                                            isRestricted = displayIsRestricted,
                                            onClick = { isCompanyDialogOpen = true },
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    },
                    bottomBar = {
                        AmberNavigationBar {
                            visibleItems.forEach { item ->
                                val selected = item.isSelected(destination)
                                AmberNavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            item.navigate(navController, companyId = effectiveCompanyId)
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(item.iconRes),
                                            contentDescription = null,
                                            modifier = Modifier.size(AmberDimens.ListIcon),
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(item.labelRes),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                    ) {
                        if (activeSupportGrant != null) {
                            SupportOverrideBanner(grant = activeSupportGrant)
                        }

                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                        ) {
                            movableContent()
                        }
                    }
                }
            }

            if (isCompanyDialogOpen && globalContext != null) {
                CompanySelectionDialog(
                    companies = globalContext.memberCompanies,
                    selectedCompanyId = effectiveSelectedCompanyId,
                    canBrowseAllCompanies = globalContext.canBrowseAllCompanies,
                    onSelectCompany = { companyId ->
                        isCompanyDialogOpen = false
                        scope.launch { selectTenantUseCase(companyId) }
                        navController.navigateToEvents(companyId)
                    },
                    onSelectAllCompanies = {
                        isCompanyDialogOpen = false
                        navController.navigateToCompaniesDashboard()
                    },
                    onDismiss = { isCompanyDialogOpen = false },
                )
            }
        }
    }
}
