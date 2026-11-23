package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.events_date_header_current_year
import studio.acmenra.control.core.ui.generated.resources.events_date_header_other_year
import studio.acmenra.control.core.ui.generated.resources.events_date_header_today
import studio.acmenra.control.core.ui.generated.resources.events_date_header_yesterday
import studio.acmenra.control.core.ui.generated.resources.events_list_end_reached
import studio.acmenra.control.core.ui.generated.resources.events_list_load_more
import studio.acmenra.control.core.ui.generated.resources.events_list_pagination_error
import studio.acmenra.control.core.ui.generated.resources.events_list_pagination_retry
import studio.acmenra.control.core.ui.generated.resources.events_offscreen_pill_accessibility_down
import studio.acmenra.control.core.ui.generated.resources.events_offscreen_pill_accessibility_up
import studio.acmenra.control.core.ui.generated.resources.events_offscreen_pill_count
import studio.acmenra.control.core.ui.generated.resources.events_offscreen_pill_plus_99
import studio.acmenra.control.core.ui.generated.resources.ic_arrow_downward
import studio.acmenra.control.core.ui.notice.NoticeDefaults
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventsPaginationError
import studio.acmenra.control.feature.events.presentation.util.EventDateDisplayFormatter
import studio.acmenra.control.feature.events.presentation.util.EventsKeyboardNavigationHelper
import kotlin.time.Clock

private const val TOP_OFFSET_TOLERANCE_PX = 2

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EventsList(
    events: List<Event>,
    selectedEventId: String?,
    pendingAckIds: Set<String>,
    canAcknowledge: Boolean,
    pendingNewerCount: Int,
    hasMore: Boolean,
    isLoadingNextPage: Boolean,
    onSelectEvent: (String?) -> Unit,
    onLoadNextPage: () -> Unit,
    onViewportAtTop: () -> Unit,
    modifier: Modifier = Modifier,
    businessTimeZone: TimeZone? = null,
    isCompact: Boolean = false,
    pendingHistoryCount: Int = 0,
    requiresRefresh: Boolean = false,
    paginationError: EventsPaginationError? = null,
    onRefreshHistory: (() -> Unit)? = null,
    onOffscreenLiveEventsDetected: ((count: Int, key: String) -> Unit)? = null,
    onAcknowledgeEvent: ((Event) -> Unit)? = null,
    timeZone: TimeZone? = businessTimeZone,
    listState: LazyListState = rememberLazyListState(),
) {
    val effectiveTimeZone = businessTimeZone ?: timeZone
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val motion = MaterialTheme.motionScheme

    // Presentation state for offscreen queue & flush context (§1.6, §8)
    var queueState by remember { mutableStateOf(OffscreenQueueState()) }
    var activeFlushContext by remember { mutableStateOf<PendingNewerFlushContext?>(null) }
    var isJumpInFlight by remember { mutableStateOf(false) }
    var isLiveInserting by remember { mutableStateOf(false) }
    var liveInsertJob by remember { mutableStateOf<Job?>(null) }

    // Synchronize PendingHistory with feed state (§8)
    LaunchedEffect(pendingHistoryCount, requiresRefresh, hasMore) {
        queueState =
            queueState.upsertPendingHistory(
                count = pendingHistoryCount,
                requiresRefresh = requiresRefresh,
                hasMore = hasMore,
            )
    }

    // Top position detection with tolerance (§1.6, §4.3, §8.2)
    val isAtTopState =
        remember {
            derivedStateOf {
                listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset in 0..TOP_OFFSET_TOLERANCE_PX
            }
        }

    // Edge-triggered top observer (§1.6, §8.2)
    LaunchedEffect(listState) {
        snapshotFlow { isAtTopState.value }
            .distinctUntilChanged()
            .collect { atTop ->
                if (atTop) {
                    if (queueState.hasPendingNewerMarker && activeFlushContext == null) {
                        activeFlushContext =
                            PendingNewerFlushContext(
                                reason = PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY,
                                capturedCount = queueState.pendingNewerMarkerCount,
                                feedGeneration = 0L,
                            )
                    }
                    onViewportAtTop()
                }
            }
    }

    // Handle incoming pendingNewerCount (§1.6, §8)
    LaunchedEffect(pendingNewerCount, isAtTopState.value) {
        val isCurrentlyAtTop = isAtTopState.value
        if (pendingNewerCount > 0) {
            if (isCurrentlyAtTop && !queueState.hasPendingNewerMarker && activeFlushContext == null) {
                // TRANSIENT_AT_TOP: already at top, no prior marker
                activeFlushContext =
                    PendingNewerFlushContext(
                        reason = PendingNewerFlushReason.TRANSIENT_AT_TOP,
                        capturedCount = pendingNewerCount,
                        feedGeneration = 0L,
                    )
                onViewportAtTop()
            } else if (!isCurrentlyAtTop) {
                // ACCUMULATED_WHILE_AWAY: update pill marker while user is away from top
                queueState = queueState.upsertPendingNewer(pendingNewerCount)
            }
        } else if (pendingNewerCount == 0) {
            // Keep marker if active ACCUMULATED_WHILE_AWAY flush is in progress (§1.6, §8)
            val flush = activeFlushContext
            if (flush == null || flush.reason == PendingNewerFlushReason.TRANSIENT_AT_TOP) {
                queueState = queueState.upsertPendingNewer(0)
                activeFlushContext = null
            }
        }
    }

    // Live Event Classification & offscreen queue detection (§1.5, §1.6, §7.1, §8)
    var previousEventsSnapshot by remember { mutableStateOf<List<Event>>(emptyList()) }
    var wasAtTopBeforeBatch by remember { mutableStateOf(true) }

    LaunchedEffect(events) {
        val oldEvents = previousEventsSnapshot
        previousEventsSnapshot = events

        if (oldEvents.isEmpty()) {
            // Initial bootstrap load: no animations, no queue
            return@LaunchedEffect
        }

        val oldIds = oldEvents.map { it.id }.toSet()
        val newEvents = events.filter { it.id !in oldIds }

        if (newEvents.isNotEmpty()) {
            val isAtTop = wasAtTopBeforeBatch
            val flush = activeFlushContext
            val isLeadingEdge = events.firstOrNull()?.id in newEvents.map { it.id }.toSet()

            if (isLeadingEdge &&
                isAtTop &&
                (flush == null || flush.reason == PendingNewerFlushReason.TRANSIENT_AT_TOP)
            ) {
                // Leading edge LIVE insertion at top (§1.6):
                // Ensure viewport displays the true top of list and animate placement
                listState.scrollToItem(0, 0)
                activeFlushContext = null
                isLiveInserting = true
                liveInsertJob?.cancel()
                liveInsertJob =
                    coroutineScope.launch {
                        delay(450)
                        isLiveInserting = false
                    }
            } else if (flush != null && flush.reason == PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY) {
                // Reconcile accumulated flush with materialized events
                val visibleItemKeys =
                    listState.layoutInfo.visibleItemsInfo
                        .mapNotNull { it.key as? String }
                        .toSet()
                queueState =
                    queueState.reconcilePendingNewerWithMaterializedIds(
                        materializedIds = newEvents.map { it.id },
                        currentlyVisibleIds = visibleItemKeys,
                    )
                activeFlushContext = null
            } else if (!isLoadingNextPage && isLeadingEdge) {
                // User is scrolled down: add offscreen events to queue and notify
                val visibleKeys =
                    listState.layoutInfo.visibleItemsInfo
                        .mapNotNull { it.key as? String }
                        .toSet()
                val offscreenIds = newEvents.map { it.id }.filter { it !in visibleKeys }
                if (offscreenIds.isNotEmpty()) {
                    queueState = queueState.enqueue(offscreenIds)
                    val batchKey = "live_batch_${Clock.System.now().toEpochMilliseconds()}"
                    onOffscreenLiveEventsDetected?.invoke(offscreenIds.size, batchKey)
                }
            }
        } else if (activeFlushContext != null && events.size >= oldEvents.size) {
            // Flush completed without new items
            queueState = queueState.upsertPendingNewer(0)
            activeFlushContext = null
        }
    }

    // Update layout snapshot state before next mutation
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        wasAtTopBeforeBatch = isAtTopState.value
    }

    // Prune loaded queue targets that become visible in layout snapshot (§1.6, §8.2)
    val visibleEventKeys =
        remember {
            derivedStateOf {
                listState.layoutInfo.visibleItemsInfo
                    .mapNotNull { it.key as? String }
                    .filterNot { it.startsWith("header_") || it.startsWith("footer_") }
                    .toSet()
            }
        }

    LaunchedEffect(visibleEventKeys.value) {
        if (visibleEventKeys.value.isNotEmpty()) {
            queueState = queueState.removeLoadedAll(visibleEventKeys.value)
        }
    }

    // Infinite scroll trigger: <= 5 events remaining (§10)
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val lastVisible = visibleItems.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            val remaining = totalItems - 1 - lastVisible
            hasMore && !isLoadingNextPage && paginationError == null && totalItems > 0 && remaining <= 5
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadNextPage()
        }
    }

    // Group events by business date (§1.1, §5)
    val groupedEvents =
        remember(events, effectiveTimeZone) {
            if (effectiveTimeZone != null) {
                events.groupBy { it.occurredAt.toLocalDateTime(effectiveTimeZone).date }
            } else {
                emptyMap()
            }
        }

    // Map each event ID to its current index in LazyColumn
    val eventIdToLazyIndex =
        remember(groupedEvents) {
            val map = mutableMapOf<String, Int>()
            var currentIndex = 0
            groupedEvents.forEach { (_, dayEvents) ->
                currentIndex++ // for sticky date header
                dayEvents.forEach { event ->
                    map[event.id] = currentIndex
                    currentIndex++
                }
            }
            map
        }

    // Visible event lazy item index range
    val minMaxVisibleEventLazyIndices =
        remember {
            derivedStateOf {
                val eventItems =
                    listState.layoutInfo.visibleItemsInfo.filter { item ->
                        (item.key as? String)?.startsWith("header_") == false &&
                            (item.key as? String)?.startsWith("footer_") == false
                    }
                val min = eventItems.minOfOrNull { it.index }
                val max = eventItems.maxOfOrNull { it.index }
                min to max
            }
        }

    // Compute head target and direction (§8.1)
    val headTargetState =
        remember {
            derivedStateOf {
                val (minIdx, maxIdx) = minMaxVisibleEventLazyIndices.value
                queueState.computeHeadTarget(
                    minVisibleEventLazyIndex = minIdx,
                    maxVisibleEventLazyIndex = maxIdx,
                    eventIdToLazyIndex = eventIdToLazyIndex,
                )
            }
        }

    val currentHeadTarget = headTargetState.value.second

    // Keyboard navigation handler (§9)
    val handleKeyEvent: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { keyEvent ->
        EventsKeyboardNavigationHelper.handleNavigationKey(
            key = keyEvent.key,
            isCtrlPressed = keyEvent.isCtrlPressed,
            isAltPressed = keyEvent.isAltPressed,
            isMetaPressed = keyEvent.isMetaPressed,
            isKeyDown = keyEvent.type == KeyEventType.KeyDown,
            events = events,
            selectedEventId = selectedEventId,
            isCompact = isCompact,
            onSelectEvent = { newId ->
                onSelectEvent(newId)
                if (newId != null) {
                    eventIdToLazyIndex[newId]?.let { idx ->
                        coroutineScope.launch { listState.animateScrollToItem(idx) }
                    }
                }
            },
        )
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent(handleKeyEvent),
    ) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            contentPadding =
                PaddingValues(
                    bottom = if (isCompact) AmberDimens.NavBarHeight else AmberDimens.EventsListFooterPadding,
                ),
        ) {
            groupedEvents.forEach { (date, eventsOnDate) ->
                stickyHeader(key = "header_${date.toEpochDays()}") {
                    DateGroupHeader(
                        date = date,
                        timeZone = effectiveTimeZone,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .zIndex(2f),
                    )
                }

                items(
                    items = eventsOnDate,
                    key = { it.id },
                ) { event ->
                    val i = eventsOnDate.indexOfFirst { it.id == event.id }
                    val isVisuallySelected = event.id == selectedEventId
                    val isPrevSelected = i > 0 && eventsOnDate[i - 1].id == selectedEventId
                    val isNextSelected =
                        i < eventsOnDate.lastIndex && eventsOnDate[i + 1].id == selectedEventId

                    val hasGluedAbove = i > 0 && !isVisuallySelected && !isPrevSelected
                    val hasGluedBelow = i < eventsOnDate.lastIndex && !isVisuallySelected && !isNextSelected

                    val targetTopGap =
                        if (i == 0) {
                            AmberDimens.EventsDaySectionSpacing
                        } else if (isVisuallySelected || isPrevSelected) {
                            AmberDimens.EventsSelectedListGap
                        } else {
                            AmberDimens.ListGap
                        }

                    val placementSpec: FiniteAnimationSpec<IntOffset>? =
                        if (isLiveInserting && !listState.isScrollInProgress) {
                            motion.fastSpatialSpec<IntOffset>()
                        } else {
                            null
                        }

                    Box(
                        modifier =
                            Modifier
                                .animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    placementSpec = placementSpec,
                                ).fillMaxWidth()
                                .zIndex(if (isVisuallySelected) 1f else 0f)
                                .padding(top = targetTopGap),
                    ) {
                        EventCard(
                            event = event,
                            isVisuallySelected = isVisuallySelected,
                            isPendingAck = event.id in pendingAckIds,
                            businessTimeZone = effectiveTimeZone,
                            hasGluedAbove = hasGluedAbove,
                            hasGluedBelow = hasGluedBelow,
                            onClick = {
                                if (!isCompact && isVisuallySelected) {
                                    onSelectEvent(null)
                                } else {
                                    onSelectEvent(event.id)
                                    onAcknowledgeEvent?.invoke(event)
                                }
                            },
                        )
                    }
                }
            }

            // Infinite Scroll Footer (§10)
            if (isLoadingNextPage) {
                item(key = "footer_loading") {
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
                        Spacer(modifier = Modifier.width(AmberDimens.GapSmall))
                        Text(
                            text = stringResource(Res.string.events_list_load_more),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (paginationError != null) {
                item(key = "footer_error") {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(AmberDimens.CardPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(Res.string.events_list_pagination_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(AmberDimens.GapSmall))
                        TextButton(
                            onClick = onLoadNextPage,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text(text = stringResource(Res.string.events_list_pagination_retry))
                        }
                    }
                }
            } else if (!hasMore && events.isNotEmpty()) {
                item(key = "footer_end") {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = AmberDimens.EventsListFooterPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.events_list_end_reached),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Overlay Indicator Pill (§1.6, §8)
        // Positioned as a stable overlay in top band above LazyColumn
        val isPillVisible = queueState.isVisible && currentHeadTarget != null
        val totalCount = queueState.totalCount

        AnimatedVisibility(
            visible = isPillVisible,
            enter =
                fadeIn(motion.fastEffectsSpec()) +
                    scaleIn(
                        animationSpec = motion.fastSpatialSpec(),
                        initialScale = NoticeDefaults.EnterScale,
                    ),
            exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(3f)
                    .padding(
                        top = (AmberDimens.EventsStickyDateHeight - AmberDimens.EventsOffscreenPillHeight) / 2,
                        end = AmberDimens.ScreenMargin,
                    ),
        ) {
            val direction =
                when (currentHeadTarget) {
                    is OffscreenJumpTarget.LoadedEvent -> currentHeadTarget.direction
                    is OffscreenJumpTarget.PendingNewerAtTop -> OffscreenDirection.UP
                    is OffscreenJumpTarget.PendingHistoryRefresh -> OffscreenDirection.DOWN
                    null -> OffscreenDirection.UP
                }

            val arrowRotation by animateFloatAsState(
                targetValue = if (direction == OffscreenDirection.UP) 180f else 0f,
                animationSpec = motion.fastSpatialSpec(),
                label = "pill-arrow-rotation",
            )

            val countText =
                if (totalCount > 99) {
                    stringResource(Res.string.events_offscreen_pill_plus_99)
                } else {
                    stringResource(Res.string.events_offscreen_pill_count, totalCount)
                }

            val accessibilityLabel =
                if (direction == OffscreenDirection.UP) {
                    stringResource(Res.string.events_offscreen_pill_accessibility_up, totalCount)
                } else {
                    stringResource(Res.string.events_offscreen_pill_accessibility_down, totalCount)
                }

            Surface(
                shape = RoundedCornerShape(AmberCorners.ExtraLarge),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 2.dp,
                modifier =
                    Modifier
                        .height(AmberDimens.EventsOffscreenPillHeight)
                        .clip(RoundedCornerShape(AmberCorners.ExtraLarge))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (isJumpInFlight) return@clickable
                                when (currentHeadTarget) {
                                    is OffscreenJumpTarget.LoadedEvent -> {
                                        isJumpInFlight = true
                                        coroutineScope.launch {
                                            try {
                                                listState.animateScrollToItem(currentHeadTarget.lazyItemIndex)
                                            } finally {
                                                isJumpInFlight = false
                                            }
                                        }
                                    }
                                    is OffscreenJumpTarget.PendingNewerAtTop -> {
                                        isJumpInFlight = true
                                        coroutineScope.launch {
                                            try {
                                                listState.animateScrollToItem(0)
                                            } finally {
                                                isJumpInFlight = false
                                            }
                                        }
                                    }
                                    is OffscreenJumpTarget.PendingHistoryRefresh -> {
                                        onRefreshHistory?.invoke()
                                    }
                                    null -> Unit
                                }
                            },
                        ).semantics { contentDescription = accessibilityLabel },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AmberDimens.GapSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = countText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.width(AmberDimens.EventsGapTiny))
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_downward),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(AmberDimens.EventsSmallIcon)
                                .graphicsLayer { rotationZ = arrowRotation },
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun DateGroupHeader(
    date: LocalDate,
    timeZone: TimeZone?,
    modifier: Modifier = Modifier,
) {
    val dateText: String =
        if (timeZone != null) {
            val today =
                Clock.System
                    .now()
                    .toLocalDateTime(timeZone)
                    .date
            val monthRes = EventDateDisplayFormatter.monthGenitiveStringResource(date.month.number)
            val monthName = stringResource(monthRes)

            val todayPattern = stringResource(Res.string.events_date_header_today)
            val yesterdayPattern = stringResource(Res.string.events_date_header_yesterday)
            val currentYearPattern = stringResource(Res.string.events_date_header_current_year)
            val otherYearPattern = stringResource(Res.string.events_date_header_other_year)

            EventDateDisplayFormatter.formatEventDayHeader(
                date = date,
                today = today,
                monthName = monthName,
                todayPattern = todayPattern,
                yesterdayPattern = yesterdayPattern,
                currentYearPattern = currentYearPattern,
                otherYearPattern = otherYearPattern,
            )
        } else {
            val d = date.day.toString().padStart(2, '0')
            val m =
                date.month.number
                    .toString()
                    .padStart(2, '0')
            "$d.$m.${date.year}"
        }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(AmberDimens.EventsStickyDateHeight),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = AmberDimens.ScreenMargin),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Text reserves space on the right for overlay pill (§1.1, §1.6)
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(end = AmberDimens.EventsStickyDatePillReservedWidth),
            )
        }
    }
}
