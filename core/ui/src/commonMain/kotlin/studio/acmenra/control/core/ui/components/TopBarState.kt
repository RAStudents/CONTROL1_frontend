package studio.acmenra.control.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class TopBarState {
    var title by mutableStateOf<String?>(null)
    var leadingAction by mutableStateOf<(@Composable () -> Unit)?>(null)
    var actions by mutableStateOf<@Composable RowScope.() -> Unit>({})
}

val LocalTopBarState = compositionLocalOf { TopBarState() }

@Composable
fun ProvideTopBar(
    title: String? = null,
    leadingAction: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val topBarState = LocalTopBarState.current

    DisposableEffect(topBarState, title) {
        if (title != null) {
            topBarState.title = title
        }
        onDispose {
            if (topBarState.title == title) {
                topBarState.title = null
            }
        }
    }

    DisposableEffect(topBarState, leadingAction) {
        topBarState.leadingAction = leadingAction
        onDispose {
            if (topBarState.leadingAction === leadingAction) {
                topBarState.leadingAction = null
            }
        }
    }

    DisposableEffect(topBarState, actions) {
        val resolvedActions: @Composable RowScope.() -> Unit = actions ?: {}
        topBarState.actions = resolvedActions
        onDispose {
            if (topBarState.actions === resolvedActions) {
                topBarState.actions = {}
            }
        }
    }
}
