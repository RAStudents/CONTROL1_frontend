package studio.acmenra.control.core.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import studio.acmenra.control.core.ui.theme.AmberDimens

@Composable
fun AmberNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    NavigationBar(
        modifier = modifier.defaultMinSize(minHeight = AmberDimens.NavBarHeight),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        content = content,
    )
}

@Composable
fun RowScope.AmberNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    alwaysShowLabel: Boolean = true,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = label,
        modifier = modifier,
        enabled = enabled,
        alwaysShowLabel = alwaysShowLabel,
        colors =
            NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}

@Composable
fun AmberNavigationRail(
    modifier: Modifier = Modifier,
    header: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    NavigationRail(
        modifier = modifier.width(AmberDimens.RailCollapsed).fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        header = {
            Spacer(modifier = Modifier.height(AmberDimens.RailTopPadding))
            header?.invoke(this)
        },
        content = content,
    )
}

@Composable
fun ColumnScope.AmberNavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    alwaysShowLabel: Boolean = true,
) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = label,
        modifier = modifier,
        enabled = enabled,
        alwaysShowLabel = alwaysShowLabel,
        colors =
            NavigationRailItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}
