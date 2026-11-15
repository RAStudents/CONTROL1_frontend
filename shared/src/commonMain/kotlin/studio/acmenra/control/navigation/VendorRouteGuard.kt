package studio.acmenra.control.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.koin.compose.koinInject
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.usecase.ObserveGlobalAccessUseCase

@Composable
fun VendorRouteGuard(
    onAccessDenied: () -> Unit,
    modifier: Modifier = Modifier,
    observeGlobalAccessUseCase: ObserveGlobalAccessUseCase = koinInject(),
    content: @Composable (GlobalUserContext) -> Unit,
) {
    val globalState by observeGlobalAccessUseCase().collectAsState()

    when (val state = globalState) {
        is GlobalAccessState.Idle, is GlobalAccessState.InitialLoading -> {
            LoadingPlaceholder(modifier)
        }

        is GlobalAccessState.Ready -> {
            if (TenantAccessResolver.canBrowseAll(state.context)) {
                content(state.context)
            } else {
                LaunchedEffect(Unit) {
                    onAccessDenied()
                }
                LoadingPlaceholder(modifier)
            }
        }

        else -> {
            LaunchedEffect(Unit) {
                onAccessDenied()
            }
            LoadingPlaceholder(modifier)
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
