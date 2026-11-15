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

    DisposableEffect(title) {
        if (title != null) {
            topBarState.title = title
        }
        onDispose {
            if (topBarState.title == title) {
                topBarState.title = null
            }
        }
    }

    DisposableEffect(leadingAction) {
        topBarState.leadingAction = leadingAction
        onDispose {
            topBarState.leadingAction = null
        }
    }

    DisposableEffect(actions) {
        topBarState.actions = actions ?: {}
        onDispose {
            topBarState.actions = {}
        }
    }
}
