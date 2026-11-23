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
import androidx.compose.runtime.SideEffect
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
import studio.acmenra.control.feature.events.domain.model.EventsInitialLoadError
import studio.acmenra.control.feature.events.domain.model.EventsPaginationError
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
    var isLiveInserting by mutableStateOf(false)
    var exitingIds by mutableStateOf<Set<String>>(emptySet())
    var liveEnterIds by mutableStateOf<Set<String>>(emptySet())
    var liveEnterCascadeIndices by mutableStateOf<Map<String, Int>>(emptyMap())
    var liveEnterBatchSize by mutableStateOf(0)
    var previousEventsSnapshot by mutableStateOf<List<Event>>(emptyList())
    var anchorEventId by mutableStateOf<String?>(null)
    var anchorOffsetPx by mutableStateOf(0)
    var priorSnapshotIds by mutableStateOf<List<String>>(emptyList())
    var wasAtTopBeforeBatch by mutableStateOf(true)
    var lastProcessedBatchId by mutableStateOf(0L)
    val eventCache = mutableMapOf<String, Event>()
    val animatedEventIds = mutableSetOf<String>()
    val completedEnterIds = mutableSetOf<String>()
    val exitingGenerations = mutableMapOf<String, Long>()
    var exitGenerationCounter by mutableStateOf(0L)
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
    presentationState: EventsListPresentationState = remember(feedScopeKey) { EventsListPresentationState() },
) {
    val effectiveTimeZone = businessTimeZone ?: timeZone ?: TimeZone.UTC
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val motion = MaterialTheme.motionScheme

    var liveInsertJob by remember { mutableStateOf<Job?>(null) }

    // Delegated presentation state (§1.6, §8, P2.2)
    var queueState by presentationState::queueState
    var activeFlushContext by presentationState::activeFlushContext
    var isJumpInFlight by presentationState::isJumpInFlight
    var isHistoryRefreshInFlight by presentationState::isHistoryRefreshInFlight
    var preRefreshSnapshotId by presentationState::preRefreshSnapshotId
    var isLiveInserting by presentationState::isLiveInserting
    var exitingIds by presentationState::exitingIds
    var liveEnterIds by presentationState::liveEnterIds
    var liveEnterCascadeIndices by presentationState::liveEnterCascadeIndices
    var liveEnterBatchSize by presentationState::liveEnterBatchSize
    var previousEventsSnapshot by presentationState::previousEventsSnapshot
    var anchorEventId by presentationState::anchorEventId
    var anchorOffsetPx by presentationState::anchorOffsetPx
    var priorSnapshotIds by presentationState::priorSnapshotIds
    var wasAtTopBeforeBatch by presentationState::wasAtTopBeforeBatch
    var lastProcessedBatchId by presentationState::lastProcessedBatchId
    val eventCache = presentationState.eventCache
    val animatedEventIds = presentationState.animatedEventIds

    // Stale-capture prevention wrappers (§1.6, P1.3)
    val currentEvents by rememberUpdatedState(events)
    val currentFeedScopeKey by rememberUpdatedState(feedScopeKey)
    val currentSnapshotId by rememberUpdatedState(snapshotId)
    val currentAsOfSequence by rememberUpdatedState(asOfSequence)
    val currentLiveBatchId by rememberUpdatedState(liveBatchId)
    val currentOnViewportAtTop by rememberUpdatedState(onViewportAtTop)
    val currentOnLoadNextPage by rememberUpdatedState(onLoadNextPage)
    val currentOnOffscreenLiveEventsDetected by rememberUpdatedState(onOffscreenLiveEventsDetected)
    val currentOnRefreshHistory by rememberUpdatedState(onRefreshHistory)

    // Reset queue, anchor and flush context when feed scope (company, filter) changes (§1.6, §8.2, P1.2, P2.3)
    var currentScopeKey by remember { mutableStateOf(feedScopeKey) }
    var currentObservedSnapshotId by remember { mutableStateOf(snapshotId) }

    LaunchedEffect(feedScopeKey) {
        if (currentScopeKey != feedScopeKey) {
            currentScopeKey = feedScopeKey
            queueState = OffscreenQueueState()
            activeFlushContext = null
            isJumpInFlight = false
            isHistoryRefreshInFlight = false
            preRefreshSnapshotId = null
            exitingIds = emptySet()
            animatedEventIds.clear()
            presentationState.completedEnterIds.clear()
            presentationState.exitingGenerations.clear()
            presentationState.exitGenerationCounter = 0L
            previousEventsSnapshot = emptyList()
            anchorEventId = null
            anchorOffsetPx = 0
            priorSnapshotIds = emptyList()
            liveEnterIds = emptySet()
            liveEnterCascadeIndices = emptyMap()
            liveEnterBatchSize = 0
            wasAtTopBeforeBatch = true
            isLiveInserting = false
            lastProcessedBatchId = 0L
            liveInsertJob?.cancel()
            coroutineScope.launch { listState.scrollToItem(0, 0) }
        }
    }

    LaunchedEffect(snapshotId) {
        if (currentObservedSnapshotId != snapshotId) {
            currentObservedSnapshotId = snapshotId
            // Independent refresh resets queue, but keep active flush context and marker if ACCUMULATED_WHILE_AWAY
            if (activeFlushContext?.reason != PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY) {
                queueState = OffscreenQueueState()
                priorSnapshotIds = currentEvents.map { it.id }
            }
        }
    }

    // Synchronize PendingHistory with feed state (§8, P2.3, P2.4)
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

    // Top position detection with tolerance (§1.6, §4.3, §8.2)
    val isAtTopState =
        remember {
            derivedStateOf {
                listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset in 0..TOP_OFFSET_TOLERANCE_PX
            }
        }

    // Edge-triggered top observer (§1.6, §8.2, P1.3)
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
                                feedScopeKey = currentFeedScopeKey,
                                preFlushEventIds = currentEvents.map { it.id }.toSet(),
                            )
                    }
                    currentOnViewportAtTop()
                }
            }
    }

    // Handle incoming pendingNewerCount (§1.6, §8, P1.6, P2.2, P4.1)
    LaunchedEffect(pendingNewerCount, isAtTopState.value, liveBatchId) {
        val isCurrentlyAtTop = isAtTopState.value
        if (pendingNewerCount > 0) {
            val flush = activeFlushContext
            if (flush != null && flush.reason == PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY) {
                // Update captured count and marker during in-flight flush without resetting context
                if (pendingNewerCount > flush.capturedCount) {
                    activeFlushContext = flush.copy(capturedCount = pendingNewerCount)
                    queueState = queueState.upsertPendingNewer(pendingNewerCount)
                }
            } else if (isCurrentlyAtTop &&
                !queueState.hasPendingNewerMarker &&
                (activeFlushContext == null || activeFlushContext?.reason == PendingNewerFlushReason.TRANSIENT_AT_TOP)
            ) {
                // TRANSIENT_AT_TOP: already at top, no prior marker. Idempotent flush for new batches (P1.6)
                activeFlushContext =
                    PendingNewerFlushContext(
                        reason = PendingNewerFlushReason.TRANSIENT_AT_TOP,
                        capturedCount = pendingNewerCount,
                        feedScopeKey = currentFeedScopeKey,
                        preFlushEventIds = currentEvents.map { it.id }.toSet(),
                    )
                currentOnViewportAtTop()
            } else if (!isCurrentlyAtTop) {
                // ACCUMULATED_WHILE_AWAY: update pill marker while user is away from top
                val wasAlreadyMarker = queueState.hasPendingNewerMarker
                queueState = queueState.upsertPendingNewer(pendingNewerCount)
                if (!wasAlreadyMarker) {
                    val batchKey =
                        "pending_newer_${currentFeedScopeKey}_${currentAsOfSequence}_${currentLiveBatchId}_$pendingNewerCount"
                    currentOnOffscreenLiveEventsDetected?.invoke(pendingNewerCount, batchKey)
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
                    priorSnapshotIds = currentEvents.map { it.id }
                }
            }
        }
    }

    // Cache event models and manage displayedEvents with exit retention (§1.5, §7.3, P1.5, P3.2, P3.3)
    LaunchedEffect(events) {
        events.forEach { eventCache[it.id] = it }
    }

    LaunchedEffect(events, liveRemovedIds) {
        val currentEventIds = events.map { it.id }.toSet()
        val visibleKeys =
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { it.key as? String }
                .filterNot { it.startsWith("header_") || it.startsWith("footer_") }
                .toSet()

        val newExiting = mutableSetOf<String>()
        for (id in liveRemovedIds) {
            if (id !in currentEventIds && eventCache.containsKey(id) && id in visibleKeys) {
                newExiting.add(id)
                val gen = ++presentationState.exitGenerationCounter
                presentationState.exitingGenerations[id] = gen
            } else if (id !in currentEventIds && id !in visibleKeys) {
                // Offscreen removed item: remove from cache directly, don't leak exit record (§1.5, P5.1)
                eventCache.remove(id)
            }
        }
        // Cancel exit for any event that reappears in currentEventIds (P3.2)
        val cancelledExiting = exitingIds.filter { it in currentEventIds }.toSet()
        cancelledExiting.forEach { presentationState.exitingGenerations.remove(it) }
        val updatedExiting = (exitingIds - cancelledExiting) + newExiting
        if (updatedExiting != exitingIds) {
            exitingIds = updatedExiting
        }
        // Prune eventCache to current events + exiting items (P3.3)
        eventCache.keys.retainAll(currentEventIds + exitingIds)
    }

    val displayedEvents =
        remember(events, exitingIds) {
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

    val onExitAnimationFinished: (String, Long) -> Unit = { finishedId, finishedGen ->
        if (presentationState.exitingGenerations[finishedId] == finishedGen) {
            exitingIds = exitingIds - finishedId
            presentationState.exitingGenerations.remove(finishedId)
            if (currentEvents.none { it.id == finishedId }) {
                eventCache.remove(finishedId)
            }
        }
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

    // Live Event Classification & offscreen queue detection (§1.5, §1.6, §7.1, §8, P1.1, P1.2, P1.6, P1.7, P3.1)
    val oldEvents = previousEventsSnapshot
    val isOldEmpty = oldEvents.isEmpty()
    val isLiveCreatedInEmptyFeed = !isInitialLoading && isOldEmpty
    val (minIdx, maxIdx) = minMaxVisibleEventLazyIndices.value

    var offscreenToNotify by remember { mutableStateOf<List<String>>(emptyList()) }

    if (liveBatchId != 0L && liveBatchId > lastProcessedBatchId && liveCreatedIds.isNotEmpty()) {
        lastProcessedBatchId = liveBatchId

        val isAtTop = wasAtTopBeforeBatch
        val flush = activeFlushContext

        val offscreenToEnqueue = mutableListOf<String>()
        val enterToAnimate = mutableListOf<String>()

        for (createdId in liveCreatedIds) {
            val isHeadCandidate = events.take(liveCreatedIds.size).any { it.id == createdId }
            val targetLazyIdx = eventIdToLazyIndex[createdId]

            if ((
                    isHeadCandidate &&
                        isAtTop &&
                        (flush == null || flush.reason == PendingNewerFlushReason.TRANSIENT_AT_TOP)
                ) ||
                (isHeadCandidate && isLiveCreatedInEmptyFeed)
            ) {
                isLiveInserting = true
                enterToAnimate.add(createdId)
            } else if (targetLazyIdx != null && minIdx != null && maxIdx != null && targetLazyIdx in minIdx..maxIdx) {
                enterToAnimate.add(createdId)
            } else {
                offscreenToEnqueue.add(createdId)
            }
        }

        if (enterToAnimate.isNotEmpty()) {
            val unrecorded = enterToAnimate.filter { it !in presentationState.completedEnterIds }
            for (id in unrecorded) {
                if (animatedEventIds.size >= 500) {
                    val oldest = animatedEventIds.first()
                    animatedEventIds.remove(oldest)
                }
                animatedEventIds.add(id)
            }
            liveEnterBatchSize = unrecorded.size
            val indicesMap = unrecorded.mapIndexed { idx, id -> id to idx }.toMap()
            liveEnterCascadeIndices = liveEnterCascadeIndices + indicesMap
            liveEnterIds = liveEnterIds + unrecorded.toSet()
        }

        if (offscreenToEnqueue.isNotEmpty()) {
            queueState = queueState.enqueue(offscreenToEnqueue)
            offscreenToNotify = offscreenToEnqueue
        }
    }

    SideEffect {
        previousEventsSnapshot = events
    }

    LaunchedEffect(liveBatchId) {
        if (isLiveInserting) {
            val maxDelay = if (liveEnterBatchSize in 2..5) liveEnterBatchSize * 45L else 0L
            delay(450L + maxDelay)
            isLiveInserting = false
        }
    }

    LaunchedEffect(offscreenToNotify) {
        if (offscreenToNotify.isNotEmpty()) {
            val joinedIds = offscreenToNotify.joinToString("_")
            val batchKey =
                "live_batch_${currentFeedScopeKey}_${currentAsOfSequence}_${currentLiveBatchId}_$joinedIds"
            currentOnOffscreenLiveEventsDetected?.invoke(offscreenToNotify.size, batchKey)
            offscreenToNotify = emptyList()
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

    // Reconcile ACCUMULATED_WHILE_AWAY flush with new layout snapshot (§1.6, §8, P1.4, P1.5, P4.1, P4.2)
    LaunchedEffect(events, pendingNewerCount, activeFlushContext, feedScopeKey, snapshotId) {
        val flush = activeFlushContext
        if (flush != null &&
            flush.feedScopeKey == feedScopeKey &&
            flush.reason == PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY &&
            pendingNewerCount == 0
        ) {
            val candidateIds =
                if (flush.pendingEventIds.isNotEmpty()) {
                    flush.pendingEventIds
                } else if (liveCreatedIds.isNotEmpty()) {
                    liveCreatedIds
                } else {
                    events.filter { it.id !in flush.preFlushEventIds }.take(flush.capturedCount).map { it.id }
                }
            val candidateSet = candidateIds.toSet()

            // Await layout remeasure with materialized items without premature clearing on timeout
            if (candidateSet.isNotEmpty()) {
                snapshotFlow {
                    !listState.isScrollInProgress &&
                        (
                            listState.firstVisibleItemIndex == 0 ||
                                listState.layoutInfo.visibleItemsInfo.any { (it.key as? String) in candidateSet }
                        )
                }.filter { it }.first()
            }

            val newlyVisibleKeys =
                listState.layoutInfo.visibleItemsInfo
                    .mapNotNull { it.key as? String }
                    .filterNot { it.startsWith("header_") || it.startsWith("footer_") }
                    .toSet()

            queueState =
                queueState.reconcilePendingNewerWithMaterializedIds(
                    materializedIds = candidateIds,
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

                    // Connected geometry computed strictly on active events (§6.1, §7.3, P1.11, P2.4, P3.4)
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

                    val cascadeIndex = liveEnterCascadeIndices[event.id] ?: 0
                    val cascadeDelay = if (liveEnterBatchSize in 2..5) cascadeIndex * 45 else 0

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

                    val placementSpec: FiniteAnimationSpec<IntOffset>? =
                        if (isLiveInserting && !listState.isScrollInProgress) {
                            fastSpatial
                        } else {
                            null
                        }

                    val targetLazyIdx = eventIdToLazyIndex[event.id]
                    val (minIdx, maxIdx) = minMaxVisibleEventLazyIndices.value
                    // Guard enter-animation strictly by liveEnterIds, never raw liveCreatedIds (§1.5, P1.8)
                    val isLiveTarget = event.id in liveEnterIds && event.id !in presentationState.completedEnterIds
                    val isGlobalHead =
                        isLiveTarget &&
                            (wasAtTopBeforeBatch || isLiveCreatedInEmptyFeed) &&
                            events.take(liveEnterBatchSize.coerceAtLeast(1)).any { it.id == event.id }
                    val isMidListLive =
                        isLiveTarget &&
                            targetLazyIdx != null &&
                            minIdx != null &&
                            maxIdx != null &&
                            targetLazyIdx in minIdx..maxIdx

                    val shouldAnimateEnter = isGlobalHead || isMidListLive

                    val enterTransition =
                        if (!shouldAnimateEnter) {
                            EnterTransition.None
                        } else if (isGlobalHead) {
                            slideInVertically(animationSpec = fastSpatial) { -it } +
                                fadeIn(animationSpec = fastEffects) +
                                scaleIn(
                                    initialScale = NoticeDefaults.EnterScale,
                                    transformOrigin = TransformOrigin(0.5f, 0f),
                                    animationSpec = motion.fastSpatialSpec(),
                                ) +
                                expandVertically(expandFrom = Alignment.Top, animationSpec = motion.fastSpatialSpec())
                        } else {
                            fadeIn(animationSpec = fastEffects) +
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
                    } else if (!transitionState.targetState) {
                        // Cancel exit transition and restore visibility if event is re-included (P3.2)
                        transitionState.targetState = true
                    }

                    LaunchedEffect(transitionState) {
                        snapshotFlow { transitionState.isIdle && transitionState.currentState }
                            .filter { it }
                            .first()
                        presentationState.completedEnterIds.add(event.id)
                        liveEnterIds = liveEnterIds - event.id
                        liveEnterCascadeIndices = liveEnterCascadeIndices - event.id
                    }

                    val exitGen = presentationState.exitingGenerations[event.id] ?: 0L
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
                                    isHistoryRefreshInFlight ||
                                    activeFlushContext != null
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
                                                        feedScopeKey = currentFeedScopeKey,
                                                        preFlushEventIds = currentEvents.map { it.id }.toSet(),
                                                    )
                                                currentOnViewportAtTop()
                                                listState.animateScrollToItem(0)
                                            } finally {
                                                isJumpInFlight = false
                                            }
                                        }
                                    }
                                    is OffscreenJumpTarget.PendingHistoryRefresh -> {
                                        if (isHistoryRefreshInFlight) return@clickable
                                        isHistoryRefreshInFlight = true
                                        preRefreshSnapshotId = currentSnapshotId
                                        coroutineScope.launch {
                                            try {
                                                currentOnRefreshHistory?.invoke()
                                            } catch (e: Throwable) {
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
