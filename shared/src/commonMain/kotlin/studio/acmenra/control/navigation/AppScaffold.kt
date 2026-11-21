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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.usecase.ObserveGlobalAccessUseCase
import studio.acmenra.control.core.access.domain.usecase.ObserveTenantContextUseCase
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
    content: @Composable () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val globalState by observeGlobalAccessUseCase().collectAsState()
    val tenantState by observeTenantContextUseCase().collectAsState()

    val isLoginDestination = destination?.hasRoute<LoginRoute>() == true
    val showNavigation =
        sessionState == SessionState.Authenticated &&
            destination != null &&
            !isLoginDestination
    val currentContent by rememberUpdatedState(content)
    val movableContent = remember { movableContentOf { currentContent() } }

    val topBarState = remember { TopBarState() }
    var isCompanyDialogOpen by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalTopBarState provides topBarState) {
        if (!showNavigation) {
            Box(modifier = modifier.fillMaxSize()) {
                movableContent()
            }
            return@CompositionLocalProvider
        }

        val globalContext = (globalState as? GlobalAccessState.Ready)?.context
        val routeCompanyId =
            if (destination.hasRoute<EventsRoute>()) {
                runCatching { backStackEntry?.toRoute<EventsRoute>()?.companyId }.getOrNull()
            } else {
                null
            }

        val uiState =
            remember(globalContext, tenantState, destination, items, routeCompanyId) {
                calculateAppScaffoldUiState(
                    globalContext = globalContext,
                    tenantState = tenantState,
                    destination = destination,
                    items = items,
                    routeCompanyId = routeCompanyId,
                )
            }

        val currentDestinationItem = uiState.visibleItems.firstOrNull { it.isSelected(destination) }
        val defaultTitle =
            when {
                uiState.isOnVendorDashboard -> stringResource(Res.string.company_switcher_all_vendor)
                currentDestinationItem != null -> stringResource(currentDestinationItem.labelRes)
                else -> ""
            }
        val title = topBarState.title ?: defaultTitle

        val displayCompanyName =
            if (uiState.isOnVendorDashboard) {
                stringResource(Res.string.company_switcher_all_vendor)
            } else {
                uiState.currentCompanyName.orEmpty()
            }
        val displayIsRestricted = if (uiState.isOnVendorDashboard) false else uiState.isRestricted
        val effectiveSelectedCompanyId = if (uiState.isOnVendorDashboard) null else uiState.selectedCompanyId

        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val isDesktopOrTablet = maxWidth >= AmberDimens.TabletBreakpoint

            if (isDesktopOrTablet) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (uiState.visibleItems.isNotEmpty()) {
                        AmberNavigationRail {
                            uiState.visibleItems.forEach { item ->
                                val selected = item.isSelected(destination)
                                AmberNavigationRailItem(
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            item.navigate(navController, companyId = uiState.effectiveCompanyId)
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
                    }

                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                    ) {
                        AmberTopAppBar(
                            title = title,
                            subtitle = null,
                            leadingAction = topBarState.leadingAction,
                            actions = topBarState.actions,
                            desktopCompanyBox =
                                if (globalContext != null) {
                                    {
                                        DesktopCompanySelector(
                                            currentCompanyName = displayCompanyName,
                                            isRestricted = displayIsRestricted,
                                            canSwitch = uiState.canSwitch,
                                            companies = globalContext.memberCompanies,
                                            selectedCompanyId = effectiveSelectedCompanyId,
                                            canBrowseAllCompanies = TenantAccessResolver.canBrowseAll(globalContext),
                                            onSelectCompany = { companyId ->
                                                if (companyId != effectiveSelectedCompanyId) {
                                                    navController.navigateToEvents(companyId)
                                                }
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

                        if (uiState.activeSupportGrant != null) {
                            SupportOverrideBanner(grant = uiState.activeSupportGrant)
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
                            subtitle = null,
                            leadingAction = topBarState.leadingAction,
                            actions = topBarState.actions,
                            desktopCompanyBox = null,
                            mobileCompanyAction =
                                if (globalContext != null && uiState.canSwitch) {
                                    {
                                        MobileCompanyIconButton(
                                            companyName = displayCompanyName,
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
                        if (uiState.visibleItems.isNotEmpty()) {
                            AmberNavigationBar {
                                uiState.visibleItems.forEach { item ->
                                    val selected = item.isSelected(destination)
                                    AmberNavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            if (!selected) {
                                                item.navigate(navController, companyId = uiState.effectiveCompanyId)
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
                        }
                    },
                ) { innerPadding ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                    ) {
                        if (uiState.activeSupportGrant != null) {
                            SupportOverrideBanner(grant = uiState.activeSupportGrant)
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
                    canBrowseAllCompanies = TenantAccessResolver.canBrowseAll(globalContext),
                    onSelectCompany = { companyId ->
                        isCompanyDialogOpen = false
                        if (companyId != effectiveSelectedCompanyId) {
                            navController.navigateToEvents(companyId)
                        }
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
