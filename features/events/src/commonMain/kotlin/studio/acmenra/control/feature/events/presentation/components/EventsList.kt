package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
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
import kotlinx.coroutines.delay
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
import studio.acmenra.control.feature.events.domain.model.EventAcknowledgementStatus
import studio.acmenra.control.feature.events.domain.model.EventsPaginationError
import studio.acmenra.control.feature.events.presentation.util.EventDateDisplayFormatter
import studio.acmenra.control.feature.events.presentation.util.EventsKeyboardNavigationHelper
import studio.acmenra.control.feature.events.presentation.util.EventsScrollAnchorHelper
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
    liveCreatedIds: Set<String> = emptySet(),
    liveRemovedIds: Set<String> = emptySet(),
    isInitialLoading: Boolean = false,
) {
    val effectiveTimeZone = businessTimeZone ?: timeZone ?: TimeZone.UTC
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val motion = MaterialTheme.motionScheme

    // Presentation state for offscreen queue & flush context (§1.6, §8)
    var queueState by remember { mutableStateOf(OffscreenQueueState()) }
    var activeFlushContext by remember { mutableStateOf<PendingNewerFlushContext?>(null) }
    var isJumpInFlight by remember { mutableStateOf(false) }
    var isHistoryRefreshInFlight by remember { mutableStateOf(false) }
    var isLiveInserting by remember { mutableStateOf(false) }
    var liveInsertJob by remember { mutableStateOf<Job?>(null) }

    // Event model retention cache and exit animation tracking (§1.5, §7.3, P1.5)
    val eventCache = remember { mutableMapOf<String, Event>() }
    var exitingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val animatedEventIds = remember { mutableSetOf<String>() }
    var liveEnterIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var previousEventsSnapshot by remember { mutableStateOf<List<Event>>(emptyList()) }

    // Scroll Anchor tracking & pre-mutation viewport snapshot (§2, §1.6, P1.6, P2.1)
    var wasAtTopBeforeBatch by remember { mutableStateOf(true) }
    var anchorEventId by remember { mutableStateOf<String?>(null) }
    var anchorOffsetPx by remember { mutableStateOf(0) }
    var priorSnapshotIds by remember { mutableStateOf<List<String>>(emptyList()) }

    // Reset queue, anchor and flush context when feed scope (company, filter) changes (§1.6, §8.2, P1.2, P2.3)
    var currentScopeKey by remember { mutableStateOf(feedScopeKey) }
    var currentSnapshotId by remember { mutableStateOf(snapshotId) }

    LaunchedEffect(feedScopeKey) {
        if (currentScopeKey != feedScopeKey) {
            currentScopeKey = feedScopeKey
            queueState = OffscreenQueueState()
            activeFlushContext = null
            isJumpInFlight = false
            isHistoryRefreshInFlight = false
            exitingIds = emptySet()
            animatedEventIds.clear()
            previousEventsSnapshot = emptyList()
            anchorEventId = null
            anchorOffsetPx = 0
            priorSnapshotIds = emptyList()
            liveEnterIds = emptySet()
            wasAtTopBeforeBatch = true
            isLiveInserting = false
            liveInsertJob?.cancel()
            coroutineScope.launch { listState.scrollToItem(0, 0) }
        }
    }

    LaunchedEffect(snapshotId) {
        if (currentSnapshotId != snapshotId) {
            currentSnapshotId = snapshotId
            // Independent refresh resets queue, but keep active flush context and marker if ACCUMULATED_WHILE_AWAY
            if (activeFlushContext?.reason != PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY) {
                queueState = OffscreenQueueState()
                priorSnapshotIds = events.map { it.id }
            }
        }
    }

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
                                feedScopeKey = feedScopeKey,
                                preFlushEventIds = events.map { it.id }.toSet(),
                            )
                    }
                    onViewportAtTop()
                }
            }
    }

    // Handle incoming pendingNewerCount (§1.6, §8, P2.2, P2.4)
    LaunchedEffect(pendingNewerCount, isAtTopState.value) {
        val isCurrentlyAtTop = isAtTopState.value
        if (pendingNewerCount > 0) {
            val flush = activeFlushContext
            if (flush != null && flush.reason == PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY) {
                // Update captured count and marker during in-flight flush without resetting context
                if (pendingNewerCount > flush.capturedCount) {
                    activeFlushContext = flush.copy(capturedCount = pendingNewerCount)
                    queueState = queueState.upsertPendingNewer(pendingNewerCount)
                }
            } else if (isCurrentlyAtTop && !queueState.hasPendingNewerMarker && activeFlushContext == null) {
                // TRANSIENT_AT_TOP: already at top, no prior marker
                activeFlushContext =
                    PendingNewerFlushContext(
                        reason = PendingNewerFlushReason.TRANSIENT_AT_TOP,
                        capturedCount = pendingNewerCount,
                        feedScopeKey = feedScopeKey,
                        preFlushEventIds = events.map { it.id }.toSet(),
                    )
                onViewportAtTop()
            } else if (!isCurrentlyAtTop) {
                // ACCUMULATED_WHILE_AWAY: update pill marker while user is away from top
                val wasAlreadyMarker = queueState.hasPendingNewerMarker
                queueState = queueState.upsertPendingNewer(pendingNewerCount)
                if (!wasAlreadyMarker) {
                    val batchKey = "pending_newer_${feedScopeKey}_$pendingNewerCount"
                    onOffscreenLiveEventsDetected?.invoke(pendingNewerCount, batchKey)
                }
            }
        } else if (pendingNewerCount == 0) {
            val flush = activeFlushContext
            if (flush == null || flush.reason == PendingNewerFlushReason.TRANSIENT_AT_TOP) {
                queueState = queueState.upsertPendingNewer(0)
                activeFlushContext = null
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collect { (_, _, isScrolling) ->
            wasAtTopBeforeBatch = isAtTopState.value
            if (!isScrolling) {
                val firstVisible =
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                        val k = item.key as? String
                        k != null && !k.startsWith("header_") && !k.startsWith("footer_")
                    }
                if (firstVisible != null) {
                    anchorEventId = firstVisible.key as? String
                    anchorOffsetPx = firstVisible.offset
                    priorSnapshotIds = events.map { it.id }
                }
            }
        }
    }

    // Cache event models and manage displayedEvents with exit retention (§1.5, §7.3, P1.5)
    LaunchedEffect(events) {
        events.forEach { eventCache[it.id] = it }
    }

    LaunchedEffect(events, liveRemovedIds) {
        val currentEventIds = events.map { it.id }.toSet()
        val newExiting = mutableSetOf<String>()
        for (id in liveRemovedIds) {
            if (id !in currentEventIds && eventCache.containsKey(id)) {
                newExiting.add(id)
            }
        }
        if (newExiting.isNotEmpty()) {
            exitingIds = exitingIds + newExiting
        }
    }

    val displayedEvents =
        remember(events, exitingIds, eventCache) {
            events.forEach { eventCache[it.id] = it }
            if (exitingIds.isEmpty()) {
                events
            } else {
                val activeExitingMap = exitingIds.mapNotNull { eventCache[it] }.associateBy { it.id }
                val nextDisplayed = mutableListOf<Event>()
                val addedIds = mutableSetOf<String>()
                for (event in events) {
                    nextDisplayed.add(event)
                    addedIds.add(event.id)
                }
                for ((id, exitingEvent) in activeExitingMap) {
                    if (id !in addedIds) {
                        nextDisplayed.add(exitingEvent)
                    }
                }
                nextDisplayed.sortedWith { a, b ->
                    compareValuesBy(b, a, { it.occurredAt }, { it.id })
                }
            }
        }

    val onExitAnimationFinished: (String) -> Unit = { finishedId ->
        exitingIds = exitingIds - finishedId
    }

    // Group events by date: active displayed + exiting (§1.1, §6.1, §7.3)
    val groupedEvents =
        remember(displayedEvents, effectiveTimeZone) {
            displayedEvents
                .distinctBy { it.id }
                .groupBy { it.occurredAt.toLocalDateTime(effectiveTimeZone).date }
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
                        val k = item.key as? String
                        k != null && !k.startsWith("header_") && !k.startsWith("footer_")
                    }
                val min = eventItems.minOfOrNull { it.index }
                val max = eventItems.maxOfOrNull { it.index }
                min to max
            }
        }

    val allFeedEventIds = remember(events) { events.map { it.id }.toSet() }

    // Live Event Classification & offscreen queue detection (§1.5, §1.6, §7.1, §8, P1.1, P1.2, P1.6)
    LaunchedEffect(liveCreatedIds, events) {
        val oldEvents = previousEventsSnapshot
        previousEventsSnapshot = events

        if (liveCreatedIds.isEmpty()) return@LaunchedEffect
        if (oldEvents.isEmpty()) return@LaunchedEffect

        val isAtTop = wasAtTopBeforeBatch
        val flush = activeFlushContext
        val (minIdx, maxIdx) = minMaxVisibleEventLazyIndices.value

        val offscreenToEnqueue = mutableListOf<String>()
        val enterToAnimate = mutableSetOf<String>()

        for (createdId in liveCreatedIds) {
            val isLeadingEdge = events.firstOrNull()?.id == createdId
            val targetLazyIdx = eventIdToLazyIndex[createdId]

            if (isLeadingEdge &&
                isAtTop &&
                (flush == null || flush.reason == PendingNewerFlushReason.TRANSIENT_AT_TOP)
            ) {
                // Leading edge LIVE insertion at top (§1.6): keep at top and animate placement
                listState.requestScrollToItem(0, 0)
                activeFlushContext = null
                isLiveInserting = true
                liveInsertJob?.cancel()
                liveInsertJob =
                    coroutineScope.launch {
                        delay(450)
                        isLiveInserting = false
                    }
                enterToAnimate.add(createdId)
            } else if (targetLazyIdx != null && minIdx != null && maxIdx != null && targetLazyIdx in minIdx..maxIdx) {
                // Mid-list live insertion inside visible viewport (§1.5): animate inside viewport, do not enqueue
                enterToAnimate.add(createdId)
            } else {
                // Offscreen live insertion (above/below viewport or backdated) (§1.6, P1.2)
                offscreenToEnqueue.add(createdId)
            }
        }

        if (enterToAnimate.isNotEmpty()) {
            liveEnterIds = liveEnterIds + enterToAnimate
            coroutineScope.launch {
                delay(600L)
                liveEnterIds = liveEnterIds - enterToAnimate
            }
        }

        if (offscreenToEnqueue.isNotEmpty()) {
            queueState = queueState.enqueue(offscreenToEnqueue)
            val batchKey = "live_batch_${feedScopeKey}_${offscreenToEnqueue.joinToString("_")}"
            onOffscreenLiveEventsDetected?.invoke(offscreenToEnqueue.size, batchKey)
        }
    }

    // Restore scroll anchor strictly if the anchored event was deleted/evicted (§2, P1.6, P2.2)
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

    // Reconcile ACCUMULATED_WHILE_AWAY flush with new layout snapshot (§1.6, §8, P1.3, P1.4)
    LaunchedEffect(events, pendingNewerCount, activeFlushContext, feedScopeKey, snapshotId) {
        val flush = activeFlushContext
        if (flush != null &&
            flush.feedScopeKey == feedScopeKey &&
            flush.reason == PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY &&
            pendingNewerCount == 0
        ) {
            // Request top position and await layout remeasure with materialized items (§1.6)
            listState.requestScrollToItem(0, 0)
            snapshotFlow {
                val info = listState.layoutInfo
                listState.firstVisibleItemIndex == 0 && info.visibleItemsInfo.isNotEmpty()
            }.filter { it }.first()

            val newlyVisibleKeys =
                listState.layoutInfo.visibleItemsInfo
                    .mapNotNull { it.key as? String }
                    .filterNot { it.startsWith("header_") || it.startsWith("footer_") }
                    .toSet()

            // Materialized IDs are strictly the prepended candidate segment bounded by flush.capturedCount (§1.6, P1.3)
            val prependedCandidates = events.takeWhile { it.id !in flush.preFlushEventIds }
            val materializedIds =
                if (prependedCandidates.isNotEmpty()) {
                    prependedCandidates.take(flush.capturedCount).map { it.id }
                } else {
                    emptyList()
                }

            queueState =
                queueState.reconcilePendingNewerWithMaterializedIds(
                    materializedIds = materializedIds,
                    currentlyVisibleIds = newlyVisibleKeys,
                )
            activeFlushContext = null
        }
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

    // Infinite scroll trigger: <= 5 event items remaining (excluding headers/footers) (§10, P2.3)
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
                    if (lastVisibleEventId !=
                        null
                    ) {
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

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadNextPage()
        }
    }

    // Compute head target and direction (§8.1, P1.8, P1.9)
    val headTargetState =
        remember(queueState, eventIdToLazyIndex, minMaxVisibleEventLazyIndices.value, allFeedEventIds) {
            derivedStateOf {
                val (minIdx, maxIdx) = minMaxVisibleEventLazyIndices.value
                queueState.computeHeadTarget(
                    minVisibleEventLazyIndex = minIdx,
                    maxVisibleEventLazyIndex = maxIdx,
                    eventIdToLazyIndex = eventIdToLazyIndex,
                    allFeedEventIds = allFeedEventIds,
                )
            }
        }

    val currentHeadTarget = headTargetState.value.second

    // Synchronize queue state if heads were pruned during target evaluation (§8.1, P1.9)
    LaunchedEffect(headTargetState.value.first) {
        val cleaned = headTargetState.value.first
        if (cleaned != queueState) {
            queueState = cleaned
        }
    }

    // Unified event open & acknowledge action (§1.3, §6.3, P1.13, P2.5)
    val openEvent: (Event) -> Unit = { event ->
        if (!isCompact && event.id == selectedEventId) {
            onSelectEvent(null)
        } else {
            onSelectEvent(event.id)
            val canAck =
                canAcknowledge &&
                    (
                        event.status == EventAcknowledgementStatus.NEW ||
                            event.status == EventAcknowledgementStatus.LEGACY_UNKNOWN
                    ) &&
                    event.id !in pendingAckIds &&
                    !event.isErroneous
            if (canAck) {
                onAcknowledgeEvent?.invoke(event)
            }
        }
    }

    // Viewport geometry and safe offsets (§9)
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

    // Keyboard navigation handler (§9, P2.5)
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
                    val activeIdx = activeEventsOnDate.indexOfFirst { it.id == event.id }

                    // Connected geometry computed strictly on active events (§6.1, §7.3, P1.11, P2.4)
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

                    val isLiveInsertingNow =
                        isLiveInserting ||
                            (
                                liveCreatedIds.isNotEmpty() &&
                                    wasAtTopBeforeBatch &&
                                    events.firstOrNull()?.id in liveCreatedIds
                            )

                    val placementSpec: FiniteAnimationSpec<IntOffset>? =
                        if (isLiveInsertingNow && !listState.isScrollInProgress) {
                            motion.fastSpatialSpec<IntOffset>()
                        } else {
                            null
                        }

                    val targetLazyIdx = eventIdToLazyIndex[event.id]
                    val (minIdx, maxIdx) = minMaxVisibleEventLazyIndices.value
                    val isLiveTarget = event.id in liveEnterIds || event.id in liveCreatedIds
                    val isGlobalHead =
                        isLiveTarget &&
                            wasAtTopBeforeBatch &&
                            event.id == events.firstOrNull()?.id
                    val isMidListLive =
                        isLiveTarget &&
                            targetLazyIdx != null &&
                            minIdx != null &&
                            maxIdx != null &&
                            targetLazyIdx in minIdx..maxIdx

                    val shouldAnimateEnter =
                        (isGlobalHead || isMidListLive) && event.id !in animatedEventIds

                    if (shouldAnimateEnter) {
                        animatedEventIds.add(event.id)
                    }

                    val enterTransition =
                        if (!shouldAnimateEnter) {
                            EnterTransition.None
                        } else if (isGlobalHead) {
                            slideInVertically(animationSpec = motion.fastSpatialSpec()) { -it } +
                                fadeIn(animationSpec = motion.fastEffectsSpec()) +
                                scaleIn(
                                    initialScale = NoticeDefaults.EnterScale,
                                    transformOrigin = TransformOrigin(0.5f, 0f),
                                    animationSpec = motion.fastSpatialSpec(),
                                ) +
                                expandVertically(expandFrom = Alignment.Top, animationSpec = motion.fastSpatialSpec())
                        } else {
                            fadeIn(animationSpec = motion.fastEffectsSpec()) +
                                scaleIn(
                                    initialScale = NoticeDefaults.EnterScale,
                                    transformOrigin = TransformOrigin(0.5f, 0.5f),
                                    animationSpec = motion.fastSpatialSpec(),
                                ) +
                                expandVertically(
                                    expandFrom = Alignment.CenterVertically,
                                    animationSpec = motion.fastSpatialSpec(),
                                )
                        }

                    val exitTransition =
                        fadeOut(animationSpec = motion.fastEffectsSpec()) +
                            shrinkVertically(animationSpec = motion.fastSpatialSpec())

                    val transitionState =
                        remember(event.id) {
                            MutableTransitionState(initialState = !shouldAnimateEnter).apply {
                                targetState = true
                            }
                        }
                    if (isExiting) {
                        transitionState.targetState = false
                    }

                    LaunchedEffect(isExiting, transitionState) {
                        if (isExiting) {
                            withTimeoutOrNull(600L) {
                                snapshotFlow { transitionState.isIdle && !transitionState.currentState }
                                    .filter { it }
                                    .first()
                            }
                            onExitAnimationFinished(event.id)
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
                                shape = if (isExiting) RoundedCornerShape(AmberCorners.ExtraLarge) else null,
                                onClick = { openEvent(event) },
                            )
                        }
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

        // Overlay Indicator Pill (§1.6, §8, P5)
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

            val (minVisibleIdx, maxVisibleIdx) = minMaxVisibleEventLazyIndices.value
            val directionalCount =
                if (direction == OffscreenDirection.UP) {
                    val pendingNewer = if (queueState.hasPendingNewerMarker) queueState.pendingNewerMarkerCount else 0
                    val loadedAbove =
                        if (minVisibleIdx != null) {
                            queueState.entries
                                .filterIsInstance<OffscreenQueueEntry.LoadedEvent>()
                                .count { eventIdToLazyIndex[it.eventId]?.let { idx -> idx < minVisibleIdx } == true }
                        } else {
                            0
                        }
                    val sum = pendingNewer + loadedAbove
                    if (sum > 0) sum else totalCount
                } else {
                    val pendingHistory = if (queueState.hasPendingHistoryMarker) queueState.pendingHistoryCount else 0
                    val loadedBelow =
                        if (maxVisibleIdx != null) {
                            queueState.entries
                                .filterIsInstance<OffscreenQueueEntry.LoadedEvent>()
                                .count { eventIdToLazyIndex[it.eventId]?.let { idx -> idx > maxVisibleIdx } == true }
                        } else {
                            0
                        }
                    val sum = pendingHistory + loadedBelow
                    if (sum > 0) sum else totalCount
                }

            val accessibilityLabel =
                if (direction == OffscreenDirection.UP) {
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
                                    activeFlushContext?.reason == PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY
                                ) {
                                    return@clickable
                                }
                                when (currentHeadTarget) {
                                    is OffscreenJumpTarget.LoadedEvent -> {
                                        isJumpInFlight = true
                                        coroutineScope.launch {
                                            try {
                                                scrollEventIntoView(currentHeadTarget.eventId)
                                            } finally {
                                                isJumpInFlight = false
                                            }
                                        }
                                    }
                                    is OffscreenJumpTarget.PendingNewerAtTop -> {
                                        isJumpInFlight = true
                                        coroutineScope.launch {
                                            try {
                                                activeFlushContext =
                                                    PendingNewerFlushContext(
                                                        reason = PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY,
                                                        capturedCount = queueState.pendingNewerMarkerCount,
                                                        feedScopeKey = feedScopeKey,
                                                        preFlushEventIds = events.map { it.id }.toSet(),
                                                    )
                                                onViewportAtTop()
                                                listState.animateScrollToItem(0)
                                            } finally {
                                                isJumpInFlight = false
                                            }
                                        }
                                    }
                                    is OffscreenJumpTarget.PendingHistoryRefresh -> {
                                        if (isHistoryRefreshInFlight) return@clickable
                                        isHistoryRefreshInFlight = true
                                        coroutineScope.launch {
                                            try {
                                                onRefreshHistory?.invoke()
                                            } finally {
                                                isHistoryRefreshInFlight = false
                                            }
                                        }
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
