package studio.acmenra.control.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.network.auth.SessionState
import studio.acmenra.control.core.ui.components.AmberNavigationBar
import studio.acmenra.control.core.ui.components.AmberNavigationBarItem
import studio.acmenra.control.core.ui.components.AmberNavigationRail
import studio.acmenra.control.core.ui.components.AmberNavigationRailItem
import studio.acmenra.control.core.ui.theme.AmberDimens

@Composable
fun AppScaffold(
    navController: NavHostController,
    sessionState: SessionState,
    modifier: Modifier = Modifier,
    items: List<AppNavigationItem> = AppNavigationItem.DEFAULT_ITEMS,
    content: @Composable () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val isLoginDestination = destination?.hasRoute<LoginRoute>() == true
    val showNavigation = sessionState == SessionState.Authenticated && !isLoginDestination
    val movableContent = remember(content) { movableContentOf { content() } }

    if (!showNavigation) {
        Box(modifier = modifier.fillMaxSize()) {
            movableContent()
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isDesktopOrTablet = maxWidth >= AmberDimens.TabletBreakpoint

        if (isDesktopOrTablet) {
            Row(modifier = Modifier.fillMaxSize()) {
                AmberNavigationRail {
                    items.forEach { item ->
                        val selected = item.isSelected(destination)
                        AmberNavigationRailItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    item.navigate(navController)
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
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                ) {
                    movableContent()
                }
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.surface,
                bottomBar = {
                    AmberNavigationBar {
                        items.forEach { item ->
                            val selected = item.isSelected(destination)
                            AmberNavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        item.navigate(navController)
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
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                ) {
                    movableContent()
                }
            }
        }
    }
}
