package studio.acmenra.control.feature.events.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import studio.acmenra.control.core.ui.components.ProvideTopBar
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_export_csv
import studio.acmenra.control.core.ui.generated.resources.events_filter_tune
import studio.acmenra.control.core.ui.generated.resources.events_show_statistics
import studio.acmenra.control.core.ui.generated.resources.ic_bar_chart
import studio.acmenra.control.core.ui.generated.resources.ic_download
import studio.acmenra.control.core.ui.generated.resources.ic_tune
import studio.acmenra.control.core.ui.generated.resources.nav_item_events
import studio.acmenra.control.core.ui.theme.AmberDimens

@Composable
fun EventsRouteContent(
    companyId: String = "",
    modifier: Modifier = Modifier,
    viewModel: EventsViewModel = koinViewModel(key = companyId),
) {
    val state by viewModel.state.collectAsState()
    EventsScreen(
        state = state,
        onToggleExportMode = viewModel::toggleExportMode,
        onFilterClick = {},
        modifier = modifier,
    )
}

@Composable
fun EventsScreen(
    state: EventsState,
    onToggleExportMode: () -> Unit,
    modifier: Modifier = Modifier,
    onFilterClick: () -> Unit = {},
) {
    ProvideTopBar(
        title = stringResource(Res.string.nav_item_events),
        leadingAction = {
            IconButton(onClick = onFilterClick) {
                Icon(
                    painter = painterResource(Res.drawable.ic_tune),
                    contentDescription = stringResource(Res.string.events_filter_tune),
                    modifier = Modifier.size(AmberDimens.ListIcon),
                )
            }
        },
        actions = {
            IconButton(onClick = onToggleExportMode) {
                Icon(
                    painter =
                        painterResource(
                            if (state.isExportModeActive) {
                                Res.drawable.ic_bar_chart
                            } else {
                                Res.drawable.ic_download
                            },
                        ),
                    contentDescription =
                        stringResource(
                            if (state.isExportModeActive) {
                                Res.string.events_show_statistics
                            } else {
                                Res.string.events_export_csv
                            },
                        ),
                    modifier = Modifier.size(AmberDimens.ListIcon),
                )
            }
        },
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.nav_item_events),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
