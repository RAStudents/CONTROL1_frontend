package studio.acmenra.control.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.access.domain.usecase.ObserveGlobalAccessUseCase
import studio.acmenra.control.core.access.domain.usecase.RefreshGlobalAccessUseCase
import studio.acmenra.control.core.network.auth.SessionManager
import studio.acmenra.control.navigation.components.LoadingPlaceholder
import studio.acmenra.control.navigation.screen.TenantErrorScreen

@Composable
fun VendorRouteGuard(
    onAccessDenied: () -> Unit,
    modifier: Modifier = Modifier,
    observeGlobalAccessUseCase: ObserveGlobalAccessUseCase = koinInject(),
    refreshGlobalAccessUseCase: RefreshGlobalAccessUseCase = koinInject(),
    sessionManager: SessionManager = koinInject(),
    content: @Composable (GlobalUserContext) -> Unit,
) {
    val globalState by observeGlobalAccessUseCase().collectAsState()
    val scope = rememberCoroutineScope()

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

        is GlobalAccessState.Error -> {
            TenantErrorScreen(
                error = state.error,
                canSwitchCompany = false,
                onRetry = {
                    scope.launch {
                        refreshGlobalAccessUseCase(force = true)
                    }
                },
                onLogout = {
                    scope.launch {
                        sessionManager.logout()
                    }
                },
                modifier = modifier,
            )
        }

        is GlobalAccessState.Restricted -> {
            LaunchedEffect(Unit) {
                onAccessDenied()
            }
            LoadingPlaceholder(modifier)
        }
    }
}
