package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.repository.EventsFeedDelta
import studio.acmenra.control.feature.events.presentation.util.EventsKeyboardNavigationHelper

internal enum class LiveEventPhase {
    AWAITING_LAYOUT,
    ENTERING,
    OFFSCREEN_PENDING,
    PRESENTED,
}

internal data class LiveBatchKey(
    val sessionGeneration: Long,
    val batchId: Long,
) : Comparable<LiveBatchKey> {
    override fun compareTo(other: LiveBatchKey): Int {
        val genCmp = sessionGeneration.compareTo(other.sessionGeneration)
        return if (genCmp != 0) genCmp else batchId.compareTo(other.batchId)
    }
}

internal data class LiveEventPresentationEntry(
    val eventId: String,
    val sessionGeneration: Long = 0L,
    val batchId: Long,
    val batchOrder: Int,
    val phase: LiveEventPhase,
)

internal data class LiveEventTrackerState(
    val entries: List<LiveEventPresentationEntry> = emptyList(),
    val lastRegisteredBatchId: Long = 0L,
    val sessionGeneration: Long = 0L,
) {
    fun onSessionGenerationChanged(newGeneration: Long): LiveEventTrackerState {
        if (newGeneration == sessionGeneration) return this
        val preserved = entries.filter { it.phase == LiveEventPhase.OFFSCREEN_PENDING }
        return copy(
            sessionGeneration = newGeneration,
            entries = preserved,
            lastRegisteredBatchId = 0L,
        )
    }

    val enteringIds: Set<String>
        get() =
            entries
                .asSequence()
                .filter { it.phase == LiveEventPhase.ENTERING }
                .map { it.eventId }
                .toSet()

    val offscreenPendingIds: List<String>
        get() =
            entries
                .asSequence()
                .filter { it.phase == LiveEventPhase.OFFSCREEN_PENDING }
                .sortedWith(
                    compareBy<LiveEventPresentationEntry> { it.sessionGeneration }
                        .thenBy { it.batchId }
                        .thenBy { it.batchOrder },
                ).map { it.eventId }
                .toList()

    val awaitingLayoutIds: List<String>
        get() =
            entries
                .filter { it.phase == LiveEventPhase.AWAITING_LAYOUT }
                .sortedWith(
                    compareBy<LiveEventPresentationEntry> { it.sessionGeneration }
                        .thenBy { it.batchId }
                        .thenBy { it.batchOrder },
                ).map { it.eventId }
}

internal const val MAX_PRESENTED_ENTRIES = 200

internal object LiveEventTrackerReducer {
    fun registerBatch(
        state: LiveEventTrackerState,
        delta: EventsFeedDelta,
        sessionGeneration: Long = state.sessionGeneration,
    ): LiveEventTrackerState {
        if (sessionGeneration < state.sessionGeneration) {
            return state
        }
        val effectiveState =
            if (sessionGeneration > state.sessionGeneration) {
                state.onSessionGenerationChanged(sessionGeneration)
            } else {
                state
            }
        if (delta.batchId <= effectiveState.lastRegisteredBatchId) {
            return effectiveState
        }

        val removedIds = delta.removedEventIds.toSet()
        val remaining = effectiveState.entries.filterNot { it.eventId in removedIds }
        val knownIds = remaining.mapTo(mutableSetOf()) { it.eventId }

        val additions =
            delta.createdEventIds
                .distinct()
                .filter { id -> id !in removedIds && knownIds.add(id) }
                .mapIndexed { index, id ->
                    LiveEventPresentationEntry(
                        eventId = id,
                        sessionGeneration = sessionGeneration,
                        batchId = delta.batchId,
                        batchOrder = index,
                        phase = LiveEventPhase.AWAITING_LAYOUT,
                    )
                }

        return effectiveState.copy(
            entries = remaining + additions,
            lastRegisteredBatchId = delta.batchId,
        )
    }

    fun reconcileAfterLayout(
        state: LiveEventTrackerState,
        actuallyVisibleKeys: Set<String>,
        expandingInViewportIds: Set<String> = emptySet(),
        layoutVisibleKeys: Set<String>? = null,
        confirmedOffscreenIds: Set<String>? = null,
    ): LiveEventTrackerState {
        val next =
            state.entries.map { entry ->
                val isVisible = entry.eventId in actuallyVisibleKeys
                val isExpanding = entry.eventId in expandingInViewportIds

                val nextPhase =
                    when (entry.phase) {
                        LiveEventPhase.AWAITING_LAYOUT -> {
                            if (isVisible) {
                                LiveEventPhase.ENTERING
                            } else if (isExpanding) {
                                LiveEventPhase.AWAITING_LAYOUT
                            } else if (confirmedOffscreenIds != null) {
                                if (entry.eventId in confirmedOffscreenIds) {
                                    LiveEventPhase.OFFSCREEN_PENDING
                                } else {
                                    LiveEventPhase.AWAITING_LAYOUT
                                }
                            } else {
                                LiveEventPhase.OFFSCREEN_PENDING
                            }
                        }
                        LiveEventPhase.ENTERING -> {
                            if (isVisible || isExpanding) {
                                LiveEventPhase.ENTERING
                            } else {
                                LiveEventPhase.OFFSCREEN_PENDING
                            }
                        }
                        LiveEventPhase.OFFSCREEN_PENDING -> {
                            if (isVisible) {
                                LiveEventPhase.PRESENTED
                            } else {
                                LiveEventPhase.OFFSCREEN_PENDING
                            }
                        }
                        LiveEventPhase.PRESENTED -> LiveEventPhase.PRESENTED
                    }

                if (nextPhase == entry.phase) entry else entry.copy(phase = nextPhase)
            }

        val presentedEntries = next.filter { it.phase == LiveEventPhase.PRESENTED }
        val finalEntries =
            if (presentedEntries.size > MAX_PRESENTED_ENTRIES) {
                val excess = presentedEntries.size - MAX_PRESENTED_ENTRIES
                val toDropIds = presentedEntries.take(excess).map { it.eventId }.toSet()
                next.filterNot { it.eventId in toDropIds }
            } else {
                next
            }

        return if (finalEntries == state.entries) state else state.copy(entries = finalEntries)
    }

    fun enterAnimationFinished(
        state: LiveEventTrackerState,
        eventId: String,
        batchId: Long,
        isCurrentlyVisible: Boolean,
        sessionGeneration: Long = state.sessionGeneration,
    ): LiveEventTrackerState {
        val index =
            state.entries.indexOfFirst {
                it.eventId == eventId &&
                    it.batchId == batchId &&
                    it.sessionGeneration == sessionGeneration &&
                    it.phase == LiveEventPhase.ENTERING
            }
        if (index < 0) return state

        val next = state.entries.toMutableList()
        next[index] =
            next[index].copy(
                phase = if (isCurrentlyVisible) LiveEventPhase.PRESENTED else LiveEventPhase.OFFSCREEN_PENDING,
            )
        return state.copy(entries = next)
    }
}

fun extractRemovedEventBatches(
    pendingPresentationDeltas: List<EventsFeedDelta>,
    liveRemovedIds: Set<String>,
    liveBatchId: Long,
): Map<String, Long> {
    if (liveRemovedIds.isEmpty()) return emptyMap()
    val result = LinkedHashMap<String, Long>()
    for (delta in pendingPresentationDeltas) {
        for (removedId in delta.removedEventIds) {
            if (removedId in liveRemovedIds && !result.containsKey(removedId)) {
                result[removedId] = delta.batchId
            }
        }
    }
    for (removedId in liveRemovedIds) {
        if (!result.containsKey(removedId)) {
            result[removedId] = liveBatchId
        }
    }
    return result
}

internal fun isItemActuallyVisible(
    itemOffset: Int,
    itemSize: Int,
    viewportStart: Int,
    viewportEnd: Int,
    minimumVisiblePixels: Int,
): Boolean {
    if (itemSize <= 0) return false
    val visibleStart = maxOf(itemOffset, viewportStart)
    val visibleEnd = minOf(itemOffset + itemSize, viewportEnd)
    val visibleHeight = (visibleEnd - visibleStart).coerceAtLeast(0)
    return visibleHeight >= minimumVisiblePixels
}

internal data class ExitingEventRecord(
    val event: Event,
    val generation: Long,
    val originalIndex: Int = -1,
    val predecessorId: String? = null,
    val wasFirstInDayGroup: Boolean = false,
)

internal fun mergeExitingEvents(
    currentEvents: List<Event>,
    exitingEvents: Map<String, ExitingEventRecord>,
): List<Event> {
    if (exitingEvents.isEmpty()) return currentEvents
    val currentIds = currentEvents.map { it.id }.toSet()
    val stillExiting = exitingEvents.filterKeys { it !in currentIds }
    if (stillExiting.isEmpty()) return currentEvents

    val result = currentEvents.toMutableList()
    for ((_, record) in stillExiting) {
        val predecessorIndex =
            if (record.predecessorId != null) {
                result.indexOfFirst { it.id == record.predecessorId }
            } else {
                -1
            }

        if (predecessorIndex != -1) {
            result.add(predecessorIndex + 1, record.event)
        } else if (record.originalIndex in 0..result.size) {
            result.add(record.originalIndex, record.event)
        } else {
            val insertIndex =
                result.indexOfFirst {
                    it.occurredAt < record.event.occurredAt ||
                        (it.occurredAt == record.event.occurredAt && it.id < record.event.id)
                }
            if (insertIndex != -1) {
                result.add(insertIndex, record.event)
            } else {
                result.add(record.event)
            }
        }
    }
    return result
}

internal data class ViewportBounds(
    val top: Int,
    val bottom: Int,
)

internal data class EventBounds(
    val top: Int,
    val bottom: Int,
) {
    val height: Int get() = bottom - top
}

internal data class EventsViewportSnapshot(
    val feedScopeKey: String,
    val sessionGeneration: Long,
    val feedRevision: Long,
    val layoutVisibleEventIds: Set<String>,
    val actuallyVisibleEventIds: Set<String>,
    val partiallyVisibleEventIds: Set<String>,
    val confirmedOffscreenIds: Set<String>,
    val safeViewport: ViewportBounds,
    val eventBounds: Map<String, EventBounds>,
    val isLayoutReady: Boolean,
)

internal fun computeViewportSnapshot(
    feedScopeKey: String,
    sessionGeneration: Long,
    feedRevision: Long,
    listState: LazyListState,
    eventIdToLazyIndex: Map<String, Int>,
    allFeedEventIds: Set<String>,
    safeTopOffset: Int,
    safeBottomOffset: Int,
    minVisibleCardHeightPx: Int,
): EventsViewportSnapshot {
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val isReady = visibleItems.isNotEmpty()

    val safeViewport =
        ViewportBounds(
            top = safeTopOffset,
            bottom = safeBottomOffset,
        )
    val safeViewportHeight = (safeBottomOffset - safeTopOffset).coerceAtLeast(0)

    val layoutVisibleEventIds = mutableSetOf<String>()
    val actuallyVisibleEventIds = mutableSetOf<String>()
    val partiallyVisibleEventIds = mutableSetOf<String>()
    val eventBounds = mutableMapOf<String, EventBounds>()

    var minEventLazyIndex: Int? = null
    var maxEventLazyIndex: Int? = null

    for (item in visibleItems) {
        val key = item.key as? String ?: continue
        if (key.startsWith("date-group-") ||
            key.startsWith("header_") ||
            key == "pagination-footer" ||
            key.startsWith("footer_") ||
            key !in allFeedEventIds
        ) {
            continue
        }

        layoutVisibleEventIds.add(key)
        val bounds = EventBounds(top = item.offset, bottom = item.offset + item.size)
        eventBounds[key] = bounds

        if (minEventLazyIndex == null || item.index < minEventLazyIndex) {
            minEventLazyIndex = item.index
        }
        if (maxEventLazyIndex == null || item.index > maxEventLazyIndex) {
            maxEventLazyIndex = item.index
        }

        val visibleStart = maxOf(bounds.top, safeViewport.top)
        val visibleEnd = minOf(bounds.bottom, safeViewport.bottom)
        val visibleHeight = (visibleEnd - visibleStart).coerceAtLeast(0)

        val threshold = minOf(minVisibleCardHeightPx, safeViewportHeight)
        if (visibleHeight >= threshold) {
            actuallyVisibleEventIds.add(key)
        } else if (visibleHeight > 0) {
            partiallyVisibleEventIds.add(key)
        }
    }

    val confirmedOffscreenIds = mutableSetOf<String>()
    if (minEventLazyIndex != null && maxEventLazyIndex != null) {
        val isAtTop = listState.firstVisibleItemIndex == 0
        for ((eventId, targetIdx) in eventIdToLazyIndex) {
            if (eventId in layoutVisibleEventIds) continue
            if (targetIdx < minEventLazyIndex) {
                if (!isAtTop) {
                    confirmedOffscreenIds.add(eventId)
                }
            } else if (targetIdx > maxEventLazyIndex) {
                confirmedOffscreenIds.add(eventId)
            }
        }
    }

    return EventsViewportSnapshot(
        feedScopeKey = feedScopeKey,
        sessionGeneration = sessionGeneration,
        feedRevision = feedRevision,
        layoutVisibleEventIds = layoutVisibleEventIds,
        actuallyVisibleEventIds = actuallyVisibleEventIds,
        partiallyVisibleEventIds = partiallyVisibleEventIds,
        confirmedOffscreenIds = confirmedOffscreenIds,
        safeViewport = safeViewport,
        eventBounds = eventBounds,
        isLayoutReady = isReady,
    )
}

internal sealed interface ScrollToEventResult {
    data object Visible : ScrollToEventResult

    data object PartiallyVisible : ScrollToEventResult

    data object EventNotFound : ScrollToEventResult

    data object Cancelled : ScrollToEventResult

    data object TimedOut : ScrollToEventResult
}

internal class EventsNavigationCoordinator(
    private val coroutineScope: CoroutineScope,
    private val listState: LazyListState,
) {
    private var currentJob: Job? = null
    private var currentToken: Long = 0L

    fun navigateToEvent(
        eventId: String,
        feedScopeKey: String,
        sessionGeneration: Long,
        getEventIdToLazyIndex: () -> Map<String, Int>,
        getAllFeedEventIds: () -> Set<String>,
        getSafeViewport: () -> ViewportBounds,
        minVisibleCardHeightPx: Int,
        onStarted: () -> Unit = {},
        onCompleted: (ScrollToEventResult) -> Unit = {},
    ) {
        val token = ++currentToken
        currentJob?.cancel()
        currentJob =
            coroutineScope.launch {
                onStarted()
                val result =
                    executeScroll(
                        eventId = eventId,
                        token = token,
                        getEventIdToLazyIndex = getEventIdToLazyIndex,
                        getAllFeedEventIds = getAllFeedEventIds,
                        getSafeViewport = getSafeViewport,
                        minVisibleCardHeightPx = minVisibleCardHeightPx,
                    )
                onCompleted(result)
            }
    }

    private suspend fun executeScroll(
        eventId: String,
        token: Long,
        getEventIdToLazyIndex: () -> Map<String, Int>,
        getAllFeedEventIds: () -> Set<String>,
        getSafeViewport: () -> ViewportBounds,
        minVisibleCardHeightPx: Int,
    ): ScrollToEventResult {
        var targetIdx = getEventIdToLazyIndex()[eventId]
        if (targetIdx == null) {
            val found =
                withTimeoutOrNull(500L) {
                    snapshotFlow { getEventIdToLazyIndex()[eventId] }
                        .filterNotNull()
                        .first()
                }
            if (found == null) return ScrollToEventResult.EventNotFound
            targetIdx = found
        }

        if (token != currentToken) return ScrollToEventResult.Cancelled

        val safeViewport = getSafeViewport()
        val safeHeight = (safeViewport.bottom - safeViewport.top).coerceAtLeast(0)
        val initialItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == eventId }
        if (initialItem != null) {
            val itemTop = initialItem.offset
            val itemBottom = initialItem.offset + initialItem.size
            val isTall = initialItem.size > safeHeight
            if (isTall) {
                if (itemTop in (safeViewport.top - 10)..(safeViewport.top + 10)) {
                    return ScrollToEventResult.PartiallyVisible
                }
            } else if (itemTop >= safeViewport.top && itemBottom <= safeViewport.bottom) {
                return ScrollToEventResult.Visible
            }
        }

        listState.animateScrollToItem(targetIdx, 0)

        val itemAfter =
            withTimeoutOrNull(800L) {
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == eventId }
                }.filterNotNull().first()
            } ?: return ScrollToEventResult.TimedOut

        if (token != currentToken) return ScrollToEventResult.Cancelled

        var currentItem = itemAfter
        for (pass in 1..2) {
            val vp = getSafeViewport()
            val vpHeight = (vp.bottom - vp.top).coerceAtLeast(0)
            val isTall = currentItem.size > vpHeight
            val adjustment =
                EventsKeyboardNavigationHelper.calculateScrollAdjustment(
                    itemTop = currentItem.offset,
                    itemBottom = currentItem.offset + currentItem.size,
                    safeTopOffset = vp.top,
                    safeBottomOffset = vp.bottom,
                )
            if (kotlin.math.abs(adjustment) > 1f) {
                listState.animateScrollBy(adjustment)
            }
            val refreshed = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == eventId }
            if (refreshed != null) {
                currentItem = refreshed
            }
            if (isTall) {
                if (kotlin.math.abs(currentItem.offset - vp.top) <= 10) {
                    return ScrollToEventResult.PartiallyVisible
                }
            } else if (currentItem.offset >= vp.top && (currentItem.offset + currentItem.size) <= vp.bottom) {
                return ScrollToEventResult.Visible
            }
        }

        val finalVp = getSafeViewport()
        val finalHeight = (finalVp.bottom - finalVp.top).coerceAtLeast(0)
        return if (currentItem.size > finalHeight) {
            ScrollToEventResult.PartiallyVisible
        } else if (currentItem.offset >= finalVp.top && (currentItem.offset + currentItem.size) <= finalVp.bottom) {
            ScrollToEventResult.Visible
        } else {
            ScrollToEventResult.PartiallyVisible
        }
    }

    fun cancel() {
        currentToken++
        currentJob?.cancel()
        currentJob = null
    }
}
