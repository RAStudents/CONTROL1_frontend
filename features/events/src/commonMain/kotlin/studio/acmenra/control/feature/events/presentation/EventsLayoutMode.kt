package studio.acmenra.control.feature.events.presentation

import androidx.compose.ui.unit.Dp
import studio.acmenra.control.core.ui.theme.AmberBreakpoints
import studio.acmenra.control.core.ui.theme.AmberDimens

enum class EventsLayoutMode {
    Wide,
    Medium,
    Compact,
}

fun calculateEventsLayoutMode(
    windowWidth: Dp,
    usableContentWidth: Dp,
    minWideUsableWidth: Dp = AmberDimens.EventsListWide + AmberDimens.GapMedium + AmberDimens.EventsDetailMin,
    minMediumUsableWidth: Dp = AmberDimens.EventsMediumWorkspaceMin,
): EventsLayoutMode =
    when {
        windowWidth >= AmberBreakpoints.EventsWide && usableContentWidth >= minWideUsableWidth -> {
            EventsLayoutMode.Wide
        }
        windowWidth >= AmberBreakpoints.EventsCompact && usableContentWidth >= minMediumUsableWidth -> {
            EventsLayoutMode.Medium
        }
        else -> {
            EventsLayoutMode.Compact
        }
    }
