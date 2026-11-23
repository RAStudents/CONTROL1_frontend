package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import studio.acmenra.control.feature.events.domain.model.EventsInitialLoadError
import studio.acmenra.control.feature.events.domain.model.EventsPaginationError
import studio.acmenra.control.feature.events.domain.repository.PendingNewerFlushResult
import studio.acmenra.control.feature.events.presentation.util.EventDateDisplayFormatter
import studio.acmenra.control.feature.events.presentation.util.EventsKeyboardNavigationHelper
import studio.acmenra.control.feature.events.presentation.util.EventsScrollAnchorHelper
import kotlin.time.Clock

private const val TOP_OFFSET_TOLERANCE_PX = 2

internal class EventsListPresentationState(
    initialQueueState: OffscreenQueueState = OffscreenQueueState(),
) {
    var queueState by mutableStateOf(initialQueueState)
    var activeFlushContext by mutableStateOf<PendingNewerFlushContext?>(null)
    var isJumpInFlight by mutableStateOf(false)
    var isHistoryRefreshInFlight by mutableStateOf(false)
    var preRefreshSnapshotId by mutableStateOf<String?>(null)
    var exitingIds by mutableStateOf<Set<String>>(emptySet())
    val exitingEvents = mutableMapOf<String, ExitingEventRecord>()
    val exitingGenerations = mutableMapOf<String, Long>()
    var exitGenerationCounter by mutableStateOf(0L)
    var anchorEventId by mutableStateOf<String?>(null)
    var anchorOffsetPx by mutableStateOf(0)
    var priorSnapshotIds by mutableStateOf<List<String>>(emptyList())
    val eventCache = mutableMapOf<String, Event>()
    val animatedEventIds = mutableSetOf<String>()
    val completedEnterIds = mutableSetOf<String>()
    var liveEnterIds by mutableStateOf<Set<String>>(emptySet())
    var liveEnterCascadeIndices by mutableStateOf<Map<String, Int>>(emptyMap())
    var liveEnterBatchSize by mutableStateOf(0)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EventsList(
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
    isViewerOpenInCompact: Boolean = false,
    pendingHistoryCount: Int = 0,
    requiresRefresh: Boolean = false,
    paginationError: EventsPaginationError? = null,
    onRefreshHistory: (() -> Unit)? = null,
    onOffscreenLiveEventsDetected: ((count: Int, key: String) -> Unit)? = null,
    onAcknowledgeEvent: ((Event) -> Unit)? = null,
    timeZone: TimeZone? = businessTimeZone,
    listState: LazyListState = rememberLazyListState(),
    feedScopeKey: String = "",
    snapshotId: String? = null,
    liveCreatedIds: List<String> = emptyList(),
    liveRemovedIds: Set<String> = emptySet(),
    liveBatchId: Long = 0L,
    asOfSequence: Long = 0L,
    isInitialLoading: Boolean = false,
    initialLoadError: EventsInitialLoadError? = null,
    onFlushPendingNewer: (suspend (List<String>) -> PendingNewerFlushResult)? = null,
    onLiveDeltaConsumed: ((Long) -> Unit)? = null,
    presentationState: EventsListPresentationState = remember(feedScopeKey) { EventsListPresentationState() },
) {
    val effectiveTimeZone = businessTimeZone ?: timeZone ?: TimeZone.UTC
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val motion = MaterialTheme.motionScheme

    // Delegated presentation state
    var queueState by presentationState::queueState
    var activeFlushContext by presentationState::activeFlushContext
    var isJumpInFlight by presentationState::isJumpInFlight
    var isHistoryRefreshInFlight by presentationState::isHistoryRefreshInFlight
    var preRefreshSnapshotId by presentationState::preRefreshSnapshotId
    var exitingIds by presentationState::exitingIds
    var anchorEventId by presentationState::anchorEventId
    var anchorOffsetPx by presentationState::anchorOffsetPx
    var priorSnapshotIds by presentationState::priorSnapshotIds
    val eventCache = presentationState.eventCache
    val animatedEventIds = presentationState.animatedEventIds

    // Up-to-date state callbacks
    val currentEvents by rememberUpdatedState(events)
    val currentFeedScopeKey by rememberUpdatedState(feedScopeKey)
    val currentSnapshotId by rememberUpdatedState(snapshotId)
    val currentAsOfSequence by rememberUpdatedState(asOfSequence)
    val currentLiveBatchId by rememberUpdatedState(liveBatchId)
    val currentOnViewportAtTop by rememberUpdatedState(onViewportAtTop)
    val currentOnRefreshHistory by rememberUpdatedState(onRefreshHistory)
    val currentOnOffscreenLiveEventsDetected by rememberUpdatedState(onOffscreenLiveEventsDetected)

    var currentObservedFeedScopeKey by remember { mutableStateOf(feedScopeKey) }
    var currentObservedSnapshotId by remember { mutableStateOf(snapshotId) }

    // Reset when switching feed scope (filter/company)
    LaunchedEffect(feedScopeKey) {
        if (currentObservedFeedScopeKey != feedScopeKey) {
            currentObservedFeedScopeKey = feedScopeKey
            queueState = OffscreenQueueState()
            activeFlushContext = null
            isJumpInFlight = false
            isHistoryRefreshInFlight = false
            preRefreshSnapshotId = null
            exitingIds = emptySet()
            presentationState.exitingEvents.clear()
            presentationState.exitGenerationCounter = 0L
            animatedEventIds.clear()
            anchorEventId = null
            anchorOffsetPx = 0
            priorSnapshotIds = emptyList()
            coroutineScope.launch { listState.scrollToItem(0, 0) }
        }
    }

    // Reset on snapshotId refresh if not actively flushing accumulated items
    LaunchedEffect(snapshotId) {
        if (currentObservedSnapshotId != snapshotId) {
            currentObservedSnapshotId = snapshotId
            if (activeFlushContext == null) {
                queueState = OffscreenQueueState()
                priorSnapshotIds = currentEvents.map { it.id }
            }
        }
    }

    // Synchronize PendingHistory with feed state
    LaunchedEffect(pendingHistoryCount, requiresRefresh, hasMore, snapshotId, initialLoadError) {
        if (!isHistoryRefreshInFlight ||
            (snapshotId != null && snapshotId != preRefreshSnapshotId) ||
            initialLoadError != null
        ) {
            isHistoryRefreshInFlight = false
            queueState =
                queueState.upsertPendingHistory(
                    count = pendingHistoryCount,
                    requiresRefresh = requiresRefresh,
                    hasMore = hasMore,
                )
        }
    }

    // Top position detection
    val isAtTopState =
        remember {
            derivedStateOf {
                listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset in 0..TOP_OFFSET_TOLERANCE_PX
            }
        }

    // Viewport items inspection
    val minMaxVisibleEventLazyIndices =
        remember {
            derivedStateOf {
                val eventItems =
                    listState.layoutInfo.visibleItemsInfo.filter { item ->
                        val k = item.key as? String
                        k != null && !k.startsWith("header_") && !k.startsWith("footer_")
                    }
                val min = eventItems.minOfOrNull { it.index }
                val max = eventItems.maxOfOrNull { it.index }
                min to max
            }
        }

    val visibleEventKeys =
        remember {
            derivedStateOf {
                listState.layoutInfo.visibleItemsInfo
                    .mapNotNull { it.key as? String }
                    .filterNot { it.startsWith("header_") || it.startsWith("footer_") }
                    .toSet()
            }
        }

    val (minIdx, maxIdx) = minMaxVisibleEventLazyIndices.value

    val viewportSnapshot =
        remember(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset,
            visibleEventKeys.value,
            minIdx,
            maxIdx,
            isAtTopState.value,
        ) {
            EventsViewportSnapshot(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                visibleItemIds = visibleEventKeys.value,
                minVisibleItemIndex = minIdx,
                maxVisibleItemIndex = maxIdx,
                isAtTop = isAtTopState.value,
            )
        }

    // Pure presentation plan calculated synchronously before LazyColumn composables render
    val currentPlan =
        remember(feedScopeKey, liveBatchId, events, viewportSnapshot) {
            buildLivePresentationPlan(
                batchId = liveBatchId,
                createdEventIds = liveCreatedIds,
                removedEventIds = liveRemovedIds,
                events = events,
                viewportSnapshot = viewportSnapshot,
                animatedEventIds = animatedEventIds,
            )
        }

    // Side-effects of live delta executed asynchronously via LaunchedEffect
    LaunchedEffect(currentPlan.batchId) {
        if (currentPlan.batchId > 0L) {
            if (currentPlan.offscreenEventIds.isNotEmpty()) {
                queueState = queueState.enqueue(currentPlan.offscreenEventIds)
                val joined = currentPlan.offscreenEventIds.joinToString("_")
                val key = "live_offscreen_${currentFeedScopeKey}_${currentAsOfSequence}_${currentPlan.batchId}_$joined"
                currentOnOffscreenLiveEventsDetected?.invoke(currentPlan.offscreenEventIds.size, key)
            }
            onLiveDeltaConsumed?.invoke(currentPlan.batchId)
        }
    }

    // Cache event instances
    LaunchedEffect(events) {
        events.forEach { eventCache[it.id] = it }
    }

    // Manage exit retention for removed events
    LaunchedEffect(events, liveRemovedIds) {
        val currentEventIds = events.map { it.id }.toSet()
        val currentVisibleKeys = visibleEventKeys.value
        val newExiting = mutableSetOf<String>()

        for (id in liveRemovedIds) {
            if (id !in currentEventIds && eventCache.containsKey(id) && id in currentVisibleKeys) {
                newExiting.add(id)
                val gen = ++presentationState.exitGenerationCounter
                val ev = eventCache[id]
                if (ev != null) {
                    presentationState.exitingEvents[id] = ExitingEventRecord(ev, gen)
                }
            } else if (id !in currentEventIds && id !in currentVisibleKeys) {
                eventCache.remove(id)
            }
        }

        // Cancel exit for any event that reappears in currentEventIds
        val reappeared = exitingIds.filter { it in currentEventIds }
        if (reappeared.isNotEmpty()) {
            for (id in reappeared) {
                presentationState.exitingEvents.remove(id)
            }
        }

        val updatedExiting = (exitingIds - reappeared.toSet()) + newExiting
        if (updatedExiting != exitingIds) {
            exitingIds = updatedExiting
        }
        eventCache.keys.retainAll(currentEventIds + exitingIds)
    }

    val onExitAnimationFinished: (String, Long) -> Unit = { finishedId, finishedGen ->
        val record = presentationState.exitingEvents[finishedId]
        if (record != null && record.generation == finishedGen) {
            presentationState.exitingEvents.remove(finishedId)
            exitingIds = exitingIds - finishedId
            if (currentEvents.none { it.id == finishedId }) {
                eventCache.remove(finishedId)
            }
        }
    }

    // Displayed events: merges active feed events with retained exiting events
    val displayedEvents =
        remember(events, exitingIds) {
            mergeExitingEvents(events, presentationState.exitingEvents)
        }

    // Group events by date
    val groupedEvents =
        remember(displayedEvents, effectiveTimeZone) {
            displayedEvents
                .distinctBy { it.id }
                .groupBy { it.occurredAt.toLocalDateTime(effectiveTimeZone).date }
        }

    // Event ID to LazyColumn item index mapping
    val eventIdToLazyIndex =
        remember(groupedEvents) {
            val map = mutableMapOf<String, Int>()
            var currentIndex = 0
            groupedEvents.forEach { (_, dayEvents) ->
                currentIndex++ // sticky date header
                dayEvents.forEach { event ->
                    map[event.id] = currentIndex
                    currentIndex++
                }
            }
            map
        }

    val allFeedEventIds = remember(events) { events.map { it.id }.toSet() }

    // Scroll anchoring
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collect { (_, _, isScrolling) ->
            if (!isScrolling) {
                val firstVisible =
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                        val k = item.key as? String
                        k != null && !k.startsWith("header_") && !k.startsWith("footer_")
                    }
                if (firstVisible != null) {
                    anchorEventId = firstVisible.key as? String
                    anchorOffsetPx = firstVisible.offset
                    priorSnapshotIds = currentEvents.map { it.id }
                }
            }
        }
    }

    LaunchedEffect(events) {
        val anchorId = anchorEventId
        if (!isAtTopState.value &&
            !listState.isScrollInProgress &&
            anchorId != null &&
            !eventIdToLazyIndex.containsKey(anchorId)
        ) {
            val resolvedIdx =
                EventsScrollAnchorHelper.resolveFallbackIndex(
                    anchorId = anchorId,
                    priorSnapshotIds = priorSnapshotIds,
                    currentIdToLazyIndex = eventIdToLazyIndex,
                )
            val scrollOffset = EventsScrollAnchorHelper.calculateScrollOffset(anchorOffsetPx)
            if (resolvedIdx >= 0) {
                listState.requestScrollToItem(
                    index = resolvedIdx,
                    scrollOffset = if (resolvedIdx == 0) 0 else scrollOffset,
                )
            }
        }
    }

    // Prune loaded queue targets that become visible in layout snapshot
    LaunchedEffect(visibleEventKeys.value) {
        if (visibleEventKeys.value.isNotEmpty()) {
            queueState = queueState.removeLoadedAll(visibleEventKeys.value)
        }
    }

    // Head target and direction resolution
    val headResolutionState =
        remember(queueState, eventIdToLazyIndex, minIdx, maxIdx, allFeedEventIds) {
            derivedStateOf {
                queueState.resolveHeadTarget(
                    minVisibleEventLazyIndex = minIdx,
                    maxVisibleEventLazyIndex = maxIdx,
                    eventIdToLazyIndex = eventIdToLazyIndex,
                    allFeedEventIds = allFeedEventIds,
                )
            }
        }

    val (cleanedQueue, headResolution) = headResolutionState.value
    LaunchedEffect(cleanedQueue) {
        if (cleanedQueue != queueState) {
            queueState = cleanedQueue
        }
    }

    // Flush State Machine
    val startPendingNewerFlush: (Boolean) -> Unit = { scrollToTop ->
        if (activeFlushContext == null) {
            coroutineScope.launch {
                try {
                    isJumpInFlight = true
                    activeFlushContext =
                        PendingNewerFlushContext(
                            phase =
                                if (scrollToTop) {
                                    PendingNewerFlushPhase.SCROLLING_TO_TOP
                                } else {
                                    PendingNewerFlushPhase.DISPATCHING_FLUSH
                                },
                            capturedCount = queueState.pendingNewerMarkerCount,
                            feedScopeKey = currentFeedScopeKey,
                            preFlushEventIds = currentEvents.map { it.id }.toSet(),
                        )

                    // 1. SCROLL_TO_TOP
                    if (scrollToTop) {
                        listState.animateScrollToItem(0, 0)
                        snapshotFlow {
                            listState.firstVisibleItemIndex == 0 &&
                                listState.firstVisibleItemScrollOffset <= TOP_OFFSET_TOLERANCE_PX &&
                                !listState.isScrollInProgress
                        }.filter { it }.first()
                    }

                    // 2. DISPATCH_FLUSH
                    activeFlushContext = activeFlushContext?.copy(phase = PendingNewerFlushPhase.DISPATCHING_FLUSH)
                    val marker =
                        queueState.entries
                            .filterIsInstance<OffscreenQueueEntry.PendingNewerAtTop>()
                            .firstOrNull()
                    val expectedIds = marker?.eventIds ?: emptyList()

                    val flushResult =
                        if (onFlushPendingNewer != null) {
                            onFlushPendingNewer(expectedIds)
                        } else {
                            currentOnViewportAtTop()
                            PendingNewerFlushResult(
                                batchId = 0L,
                                snapshotId = currentSnapshotId,
                                materializedEventIds = expectedIds,
                                createdEventIds = expectedIds,
                            )
                        }

                    // 3. AWAIT_MATERIALIZATION
                    activeFlushContext =
                        activeFlushContext?.copy(
                            phase = PendingNewerFlushPhase.AWAITING_MATERIALIZATION,
                            expectedBatchId = flushResult.batchId,
                            expectedSnapshotId = flushResult.snapshotId,
                        )

                    // 4. AWAIT_LAYOUT
                    activeFlushContext = activeFlushContext?.copy(phase = PendingNewerFlushPhase.AWAITING_LAYOUT)
                    val materializedIds = flushResult.materializedEventIds
                    if (materializedIds.isNotEmpty()) {
                        snapshotFlow {
                            !listState.isScrollInProgress &&
                                (
                                    listState.firstVisibleItemIndex == 0 ||
                                        listState.layoutInfo.visibleItemsInfo.any {
                                            (it.key as? String) in materializedIds
                                        }
                                )
                        }.filter { it }.first()
                    }

                    // 5. RECONCILING
                    activeFlushContext = activeFlushContext?.copy(phase = PendingNewerFlushPhase.RECONCILING)
                    val currentVisibleKeys =
                        listState.layoutInfo.visibleItemsInfo
                            .mapNotNull { it.key as? String }
                            .filterNot { it.startsWith("header_") || it.startsWith("footer_") }
                            .toSet()

                    queueState =
                        queueState.reconcilePendingNewer(
                            materializedIds = materializedIds,
                            currentlyVisibleIds = currentVisibleKeys,
                        )
                } finally {
                    activeFlushContext = null
                    isJumpInFlight = false
                }
            }
        }
    }

    // Top observer: when user scrolls to top, trigger flush
    LaunchedEffect(listState) {
        snapshotFlow { isAtTopState.value }
            .distinctUntilChanged()
            .collect { atTop ->
                if (atTop) {
                    if (queueState.hasPendingNewerMarker && activeFlushContext == null) {
                        startPendingNewerFlush(false)
                    } else {
                        currentOnViewportAtTop()
                    }
                }
            }
    }

    // Handle incoming pendingNewerCount updates
    LaunchedEffect(pendingNewerCount, isAtTopState.value) {
        val isCurrentlyAtTop = isAtTopState.value
        if (pendingNewerCount > 0) {
            if (isCurrentlyAtTop && !queueState.hasPendingNewerMarker && activeFlushContext == null) {
                startPendingNewerFlush(false)
            } else if (!isCurrentlyAtTop) {
                val wasAlreadyMarker = queueState.hasPendingNewerMarker
                queueState = queueState.upsertPendingNewer(count = pendingNewerCount)
                if (!wasAlreadyMarker) {
                    val batchKey = "pending_newer_${currentFeedScopeKey}_${currentAsOfSequence}_$pendingNewerCount"
                    currentOnOffscreenLiveEventsDetected?.invoke(pendingNewerCount, batchKey)
                }
            }
        } else if (pendingNewerCount == 0 && activeFlushContext == null) {
            queueState = queueState.upsertPendingNewer(count = 0)
        }
    }

    // Selection action: selection only!
    val openEvent: (Event) -> Unit = { event ->
        if (!isCompact && event.id == selectedEventId) {
            onSelectEvent(null)
        } else {
            onSelectEvent(event.id)
        }
    }

    // Automatic pagination
    val shouldLoadMore by remember(hasMore, isLoadingNextPage, paginationError, isInitialLoading, events) {
        derivedStateOf {
            if (!hasMore || isLoadingNextPage || paginationError != null || isInitialLoading || events.isEmpty()) {
                false
            } else {
                val lastVisibleEventId =
                    listState.layoutInfo.visibleItemsInfo
                        .mapNotNull { it.key as? String }
                        .lastOrNull { !it.startsWith("header_") && !it.startsWith("footer_") }
                val lastEventIndex =
                    if (lastVisibleEventId != null) {
                        events.indexOfFirst { it.id == lastVisibleEventId }
                    } else {
                        -1
                    }
                if (lastEventIndex != -1) {
                    val remainingEvents = events.size - 1 - lastEventIndex
                    remainingEvents <= 5
                } else {
                    false
                }
            }
        }
    }

    val paginationRequestKey =
        remember(events.size, hasMore, isLoadingNextPage) {
            "${events.size}_${hasMore}_$isLoadingNextPage"
        }
    LaunchedEffect(shouldLoadMore, paginationRequestKey) {
        if (shouldLoadMore) {
            onLoadNextPage()
        }
    }

    // Viewport geometry and safe offsets
    val density = LocalDensity.current
    val stickyHeaderHeightPx = with(density) { AmberDimens.EventsStickyDateHeight.roundToPx() }
    val topGapPx = with(density) { AmberDimens.ListGap.roundToPx() }
    val safeTopOffset = stickyHeaderHeightPx + topGapPx
    val navBarHeightPx = with(density) { AmberDimens.NavBarHeight.roundToPx() }
    val footerPaddingPx = with(density) { AmberDimens.EventsListFooterPadding.roundToPx() }
    val bottomGapPx = with(density) { AmberDimens.ListGap.roundToPx() }

    var keyboardScrollJob: Job? by remember { mutableStateOf<Job?>(null) }

    val scrollEventIntoView: suspend (String) -> Unit = { eventId ->
        val layoutInfo = listState.layoutInfo
        val bottomPaddingPx =
            if (layoutInfo.afterContentPadding > 0) {
                layoutInfo.afterContentPadding
            } else {
                if (isCompact) navBarHeightPx else footerPaddingPx
            }
        val safeBottomOffset = layoutInfo.viewportEndOffset - bottomPaddingPx - bottomGapPx

        val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.key == eventId }
        if (visibleItem != null) {
            val itemTop = visibleItem.offset
            val itemBottom = visibleItem.offset + visibleItem.size
            val adjustment =
                EventsKeyboardNavigationHelper.calculateScrollAdjustment(
                    itemTop = itemTop,
                    itemBottom = itemBottom,
                    safeTopOffset = safeTopOffset,
                    safeBottomOffset = safeBottomOffset,
                )
            if (adjustment != 0f) {
                listState.animateScrollBy(adjustment)
            }
        } else {
            val targetIdx = eventIdToLazyIndex[eventId]
            if (targetIdx != null) {
                if (targetIdx <= 1) {
                    listState.animateScrollToItem(0)
                } else {
                    listState.animateScrollToItem(maxOf(0, targetIdx - 1))
                    val itemAfter = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == eventId }
                    if (itemAfter != null) {
                        val adjustment =
                            EventsKeyboardNavigationHelper.calculateScrollAdjustment(
                                itemTop = itemAfter.offset,
                                itemBottom = itemAfter.offset + itemAfter.size,
                                safeTopOffset = safeTopOffset,
                                safeBottomOffset = safeBottomOffset,
                            )
                        if (adjustment != 0f) {
                            listState.animateScrollBy(adjustment)
                        }
                    }
                }
            }
        }
    }

    // Keyboard navigation handler
    val handleKeyEvent: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { keyEvent ->
        EventsKeyboardNavigationHelper.handleNavigationKey(
            key = keyEvent.key,
            isCtrlPressed = keyEvent.isCtrlPressed,
            isAltPressed = keyEvent.isAltPressed,
            isMetaPressed = keyEvent.isMetaPressed,
            isShiftPressed = keyEvent.isShiftPressed,
            isKeyDown = keyEvent.type == KeyEventType.KeyDown,
            events = events,
            selectedEventId = selectedEventId,
            isCompact = isCompact,
            onSelectEvent = { newId ->
                val eventToOpen = events.firstOrNull { it.id == newId }
                if (eventToOpen != null) {
                    openEvent(eventToOpen)
                    keyboardScrollJob?.cancel()
                    keyboardScrollJob =
                        coroutineScope.launch {
                            scrollEventIntoView(eventToOpen.id)
                        }
                } else if (newId == null) {
                    onSelectEvent(null)
                }
            },
        )
    }

    val eventIdToActiveGroupIndex =
        remember(groupedEvents, exitingIds) {
            val map = HashMap<String, Int>()
            groupedEvents.forEach { (_, eventsOnDate) ->
                val active = eventsOnDate.filter { it.id !in exitingIds }
                active.forEachIndexed { index, event ->
                    map[event.id] = index
                }
            }
            map
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

                val activeEventsOnDate = eventsOnDate.filter { it.id !in exitingIds }

                itemsIndexed(
                    items = eventsOnDate,
                    key = { _, item -> item.id },
                ) { _, event ->
                    val isExiting = event.id in exitingIds
                    val activeIdx = eventIdToActiveGroupIndex[event.id] ?: -1

                    val isVisuallySelected =
                        if (isCompact) {
                            isViewerOpenInCompact && event.id == selectedEventId
                        } else {
                            event.id == selectedEventId
                        }

                    val isPrevSelected =
                        activeIdx > 0 &&
                            (
                                if (isCompact) {
                                    isViewerOpenInCompact && activeEventsOnDate[activeIdx - 1].id == selectedEventId
                                } else {
                                    activeEventsOnDate[activeIdx - 1].id == selectedEventId
                                }
                            )

                    val isNextSelected =
                        activeIdx != -1 &&
                            activeIdx < activeEventsOnDate.lastIndex &&
                            (
                                if (isCompact) {
                                    isViewerOpenInCompact && activeEventsOnDate[activeIdx + 1].id == selectedEventId
                                } else {
                                    activeEventsOnDate[activeIdx + 1].id == selectedEventId
                                }
                            )

                    val hasGluedAbove = activeIdx > 0 && !isVisuallySelected && !isPrevSelected
                    val hasGluedBelow =
                        activeIdx != -1 &&
                            activeIdx < activeEventsOnDate.lastIndex &&
                            !isVisuallySelected &&
                            !isNextSelected

                    val targetTopGap =
                        if (activeIdx == 0) {
                            AmberDimens.EventsDaySectionSpacing
                        } else if (isVisuallySelected || isPrevSelected) {
                            AmberDimens.EventsSelectedListGap
                        } else {
                            AmberDimens.ListGap
                        }

                    val shouldAnimateEnter = event.id in currentPlan.enterEventIds
                    val cascadeIndex = currentPlan.cascadeIndices[event.id] ?: 0
                    val cascadeDelay = if (currentPlan.batchSize in 2..4) cascadeIndex * 45 else 0

                    val fastSpatial: FiniteAnimationSpec<IntOffset> =
                        if (cascadeDelay > 0) {
                            tween(durationMillis = 250, delayMillis = cascadeDelay, easing = FastOutSlowInEasing)
                        } else {
                            motion.fastSpatialSpec()
                        }
                    val fastEffects: FiniteAnimationSpec<Float> =
                        if (cascadeDelay > 0) {
                            tween(durationMillis = 150, delayMillis = cascadeDelay, easing = FastOutSlowInEasing)
                        } else {
                            motion.fastEffectsSpec()
                        }

                    val enterTransition =
                        if (!shouldAnimateEnter) {
                            EnterTransition.None
                        } else {
                            slideInVertically(animationSpec = fastSpatial) { -it } +
                                fadeIn(animationSpec = fastEffects) +
                                scaleIn(
                                    initialScale = NoticeDefaults.EnterScale,
                                    transformOrigin = TransformOrigin(0.5f, 0f),
                                    animationSpec = motion.fastSpatialSpec(),
                                ) +
                                expandVertically(expandFrom = Alignment.Top, animationSpec = motion.fastSpatialSpec())
                        }

                    val exitTransition =
                        fadeOut(animationSpec = motion.fastEffectsSpec()) +
                            shrinkVertically(animationSpec = motion.fastSpatialSpec())

                    val transitionState =
                        remember(event.id, currentPlan.batchId) {
                            MutableTransitionState(initialState = !shouldAnimateEnter).apply {
                                targetState = true
                            }
                        }

                    if (isExiting) {
                        transitionState.targetState = false
                    } else if (!transitionState.targetState) {
                        transitionState.targetState = true
                    }

                    LaunchedEffect(transitionState) {
                        snapshotFlow { transitionState.isIdle && transitionState.currentState }
                            .filter { it }
                            .first()
                        animatedEventIds.add(event.id)
                    }

                    val exitGen = presentationState.exitingEvents[event.id]?.generation ?: 0L
                    LaunchedEffect(isExiting, transitionState, exitGen) {
                        if (isExiting) {
                            withTimeoutOrNull(600L) {
                                snapshotFlow { transitionState.isIdle && !transitionState.currentState }
                                    .filter { it }
                                    .first()
                            }
                            onExitAnimationFinished(event.id, exitGen)
                        }
                    }

                    AnimatedVisibility(
                        visibleState = transitionState,
                        enter = enterTransition,
                        exit = exitTransition,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
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
                                shape = if (isExiting) RoundedCornerShape(AmberCorners.ExtraLarge) else null,
                                onClick = { openEvent(event) },
                            )
                        }
                    }
                }
            }

            // Infinite Scroll Footer
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

        // Overlay Indicator Pill
        var lastKnownDirectionalCount by remember { mutableStateOf(1) }
        val (pillStatus, displayDirection, directionalCount) =
            remember(
                headResolution,
                isJumpInFlight,
                isHistoryRefreshInFlight,
                activeFlushContext,
            ) {
                when {
                    isJumpInFlight || isHistoryRefreshInFlight || activeFlushContext != null -> {
                        Triple(OffscreenPillStatus.IN_FLIGHT, OffscreenDirection.UP, lastKnownDirectionalCount)
                    }
                    headResolution is OffscreenHeadResolution.Ready -> {
                        lastKnownDirectionalCount = headResolution.directionalCount
                        val dir =
                            when (val t = headResolution.target) {
                                is OffscreenJumpTarget.LoadedEvent -> t.direction
                                is OffscreenJumpTarget.PendingNewerAtTop -> t.direction
                                is OffscreenJumpTarget.PendingHistoryRefresh -> OffscreenDirection.DOWN
                            }
                        Triple(OffscreenPillStatus.READY, dir, headResolution.directionalCount)
                    }
                    headResolution is OffscreenHeadResolution.AwaitingLayout -> {
                        Triple(
                            OffscreenPillStatus.AWAITING_LAYOUT,
                            headResolution.fallbackDirection,
                            lastKnownDirectionalCount,
                        )
                    }
                    else -> {
                        Triple(OffscreenPillStatus.HIDDEN, OffscreenDirection.UP, 0)
                    }
                }
            }

        val isPillVisible = pillStatus != OffscreenPillStatus.HIDDEN

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
            val arrowRotation by animateFloatAsState(
                targetValue = if (displayDirection == OffscreenDirection.UP) 180f else 0f,
                animationSpec = motion.fastSpatialSpec(),
                label = "pill-arrow-rotation",
            )

            // Strictly identical count for both visual text and accessibility description
            val countText =
                if (directionalCount > 99) {
                    stringResource(Res.string.events_offscreen_pill_plus_99)
                } else {
                    stringResource(Res.string.events_offscreen_pill_count, directionalCount)
                }

            val accessibilityLabel =
                if (displayDirection == OffscreenDirection.UP) {
                    stringResource(Res.string.events_offscreen_pill_accessibility_up, directionalCount)
                } else {
                    stringResource(Res.string.events_offscreen_pill_accessibility_down, directionalCount)
                }

            Surface(
                shape = RoundedCornerShape(AmberCorners.ExtraLarge),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = AmberDimens.EventsOffscreenPillElevation,
                modifier =
                    Modifier
                        .height(AmberDimens.EventsOffscreenPillHeight)
                        .clip(RoundedCornerShape(AmberCorners.ExtraLarge))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (isJumpInFlight ||
                                    isHistoryRefreshInFlight ||
                                    activeFlushContext != null
                                ) {
                                    return@clickable
                                }
                                when (headResolution) {
                                    is OffscreenHeadResolution.Ready -> {
                                        when (val target = headResolution.target) {
                                            is OffscreenJumpTarget.LoadedEvent -> {
                                                isJumpInFlight = true
                                                coroutineScope.launch {
                                                    try {
                                                        scrollEventIntoView(target.eventId)
                                                    } finally {
                                                        isJumpInFlight = false
                                                    }
                                                }
                                            }
                                            is OffscreenJumpTarget.PendingNewerAtTop -> {
                                                startPendingNewerFlush(true)
                                            }
                                            is OffscreenJumpTarget.PendingHistoryRefresh -> {
                                                if (isHistoryRefreshInFlight) return@clickable
                                                isHistoryRefreshInFlight = true
                                                preRefreshSnapshotId = currentSnapshotId
                                                coroutineScope.launch {
                                                    try {
                                                        currentOnRefreshHistory?.invoke()
                                                    } finally {
                                                        isHistoryRefreshInFlight = false
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    is OffscreenHeadResolution.AwaitingLayout -> {
                                        // Still awaiting measurement: scroll to top as safe fallback
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(0, 0)
                                        }
                                    }
                                    OffscreenHeadResolution.Empty -> Unit
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
