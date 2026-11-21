package studio.acmenra.control.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class TopBarState {
    var title by mutableStateOf<String?>(null)
    internal var titleOwner: Any? = null
    var leadingAction by mutableStateOf<(@Composable () -> Unit)?>(null)
    var actions by mutableStateOf<@Composable RowScope.() -> Unit>({})

    fun updateTitle(
        newTitle: String?,
        owner: Any,
    ) {
        if (newTitle != null) {
            title = newTitle
            titleOwner = owner
        }
    }

    fun clearTitle(owner: Any) {
        if (titleOwner === owner) {
            title = null
            titleOwner = null
        }
    }

    fun updateLeadingAction(action: (@Composable () -> Unit)?) {
        leadingAction = action
    }

    fun clearLeadingAction(action: (@Composable () -> Unit)?) {
        if (leadingAction === action) {
            leadingAction = null
        }
    }

    fun updateActions(newActions: @Composable RowScope.() -> Unit) {
        actions = newActions
    }

    fun clearActions(expectedActions: @Composable RowScope.() -> Unit) {
        if (actions === expectedActions) {
            actions = {}
        }
    }
}

val LocalTopBarState = compositionLocalOf { TopBarState() }

@Composable
fun ProvideTopBar(
    title: String? = null,
    leadingAction: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val topBarState = LocalTopBarState.current
    val owner = remember { Any() }

    DisposableEffect(topBarState, title, owner) {
        topBarState.updateTitle(title, owner)
        onDispose {
            topBarState.clearTitle(owner)
        }
    }

    DisposableEffect(topBarState, leadingAction) {
        topBarState.updateLeadingAction(leadingAction)
        onDispose {
            topBarState.clearLeadingAction(leadingAction)
        }
    }

    DisposableEffect(topBarState, actions) {
        val resolvedActions: @Composable RowScope.() -> Unit = actions ?: {}
        topBarState.updateActions(resolvedActions)
        onDispose {
            topBarState.clearActions(resolvedActions)
        }
    }
}
