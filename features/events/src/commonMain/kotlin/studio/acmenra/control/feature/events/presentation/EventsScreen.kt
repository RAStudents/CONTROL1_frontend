package studio.acmenra.control.feature.events.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun EventsRoute(
    modifier: Modifier = Modifier,
    viewModel: EventsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    EventsScreen(
        state = state,
        modifier = modifier,
    )
}

@Composable
fun EventsScreen(
    state: EventsState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text("Events Screen")
    }
}
