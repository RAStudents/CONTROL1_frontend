package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_today
import studio.acmenra.control.core.ui.generated.resources.events_filter_date_yesterday
import studio.acmenra.control.core.ui.generated.resources.events_list_load_more
import studio.acmenra.control.core.ui.generated.resources.events_list_pending_new_events
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.Event
import kotlin.time.Clock

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventsList(
    events: List<Event>,
    selectedEventId: String?,
    pendingAckIds: Set<String>,
    canAcknowledge: Boolean,
    pendingNewerCount: Int,
    hasMore: Boolean,
    isLoadingNextPage: Boolean,
    onSelectEvent: (String) -> Unit,
    onAcknowledgeEvent: (Event) -> Unit,
    onLoadNextPage: () -> Unit,
    onViewportAtTop: () -> Unit,
    modifier: Modifier = Modifier,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Detect when user is at the top of the list
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    LaunchedEffect(isAtTop) {
        if (isAtTop) {
            onViewportAtTop()
        }
    }

    // Detect when scrolling near the end to trigger loading next page
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisible =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            hasMore && !isLoadingNextPage && totalItems > 0 && lastVisible >= totalItems - 5
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadNextPage()
        }
    }

    // Group events by business date
    val groupedEvents =
        remember(events, timeZone) {
            events.groupBy { it.occurredAt.toLocalDateTime(timeZone).date }
        }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = AmberDimens.NavBarHeight),
            verticalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
        ) {
            groupedEvents.forEach { (date, eventsOnDate) ->
                stickyHeader(key = "header_${date.toEpochDays()}") {
                    DateGroupHeader(
                        date = date,
                        timeZone = timeZone,
                    )
                }

                items(
                    items = eventsOnDate,
                    key = { it.id },
                ) { event ->
                    EventCard(
                        event = event,
                        isSelected = event.id == selectedEventId,
                        isPendingAck = event.id in pendingAckIds,
                        canAcknowledge = canAcknowledge,
                        onClick = { onSelectEvent(event.id) },
                        onAcknowledge = { onAcknowledgeEvent(event) },
                        timeZone = timeZone,
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            if (isLoadingNextPage) {
                item(key = "loading_indicator") {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(AmberDimens.CardPadding),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(AmberDimens.ListIcon),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.size(AmberDimens.GapSmall))
                        Text(
                            text = stringResource(Res.string.events_list_load_more),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Floating "+N new events" button at top
        AnimatedVisibility(
            visible = pendingNewerCount > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = AmberDimens.GapSmall),
        ) {
            ElevatedAssistChip(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                        onViewportAtTop()
                    }
                },
                label = {
                    Text(
                        text = stringResource(Res.string.events_list_pending_new_events, pendingNewerCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                shape = RoundedCornerShape(AmberCorners.ExtraLarge),
            )
        }
    }
}

@Composable
private fun DateGroupHeader(
    date: LocalDate,
    timeZone: TimeZone,
    modifier: Modifier = Modifier,
) {
    val today =
        Clock.System
            .now()
            .toLocalDateTime(timeZone)
            .date
    val yesterday = LocalDate.fromEpochDays(today.toEpochDays() - 1)

    val dateTitle =
        when (date) {
            today -> stringResource(Res.string.events_filter_date_today)
            yesterday -> stringResource(Res.string.events_filter_date_yesterday)
            else -> {
                val d = date.day.toString().padStart(2, '0')
                val m =
                    date.month.number
                        .toString()
                        .padStart(2, '0')
                "$d.$m.${date.year}"
            }
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
    ) {
        Box(
            modifier =
                Modifier
                    .padding(vertical = AmberDimens.GapSmall)
                    .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dateTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(AmberCorners.ExtraSmall),
                        ).padding(
                            horizontal = AmberDimens.GapMedium,
                            vertical = AmberDimens.ListGap,
                        ),
            )
        }
    }
}
