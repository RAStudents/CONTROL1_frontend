package studio.acmenra.control.feature.events.presentation.components

import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.repository.EventsFeedDelta

internal enum class LiveEventPhase {
    AWAITING_LAYOUT,
    ENTERING,
    OFFSCREEN_PENDING,
    PRESENTED,
}

internal data class LiveEventPresentationEntry(
    val eventId: String,
    val batchId: Long,
    val batchOrder: Int,
    val phase: LiveEventPhase,
)

internal data class LiveEventTrackerState(
    val entries: List<LiveEventPresentationEntry> = emptyList(),
    val lastRegisteredBatchId: Long = 0L,
) {
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
                    compareBy<LiveEventPresentationEntry> { it.batchId }
                        .thenBy { it.batchOrder },
                ).map { it.eventId }
                .toList()

    val awaitingLayoutIds: List<String>
        get() =
            entries
                .filter { it.phase == LiveEventPhase.AWAITING_LAYOUT }
                .sortedWith(
                    compareBy<LiveEventPresentationEntry> { it.batchId }
                        .thenBy { it.batchOrder },
                ).map { it.eventId }
}

internal object LiveEventTrackerReducer {
    fun registerBatch(
        state: LiveEventTrackerState,
        delta: EventsFeedDelta,
    ): LiveEventTrackerState {
        if (delta.batchId <= state.lastRegisteredBatchId) {
            return state
        }

        val removedIds = delta.removedEventIds.toSet()
        val remaining = state.entries.filterNot { it.eventId in removedIds }
        val knownIds = remaining.mapTo(mutableSetOf()) { it.eventId }

        val additions =
            delta.createdEventIds
                .distinct()
                .filter { id -> knownIds.add(id) }
                .mapIndexed { index, id ->
                    LiveEventPresentationEntry(
                        eventId = id,
                        batchId = delta.batchId,
                        batchOrder = index,
                        phase = LiveEventPhase.AWAITING_LAYOUT,
                    )
                }

        return LiveEventTrackerState(
            entries = remaining + additions,
            lastRegisteredBatchId = delta.batchId,
        )
    }

    fun reconcileAfterLayout(
        state: LiveEventTrackerState,
        currentFeedIds: Set<String>,
        visibleEventIds: Set<String>,
    ): LiveEventTrackerState {
        val next =
            state.entries.mapNotNull { entry ->
                // Event has been removed from the current feed
                if (entry.eventId !in currentFeedIds) {
                    return@mapNotNull null
                }

                val isVisible = entry.eventId in visibleEventIds

                val nextPhase =
                    when (entry.phase) {
                        LiveEventPhase.AWAITING_LAYOUT -> {
                            if (isVisible) {
                                LiveEventPhase.ENTERING
                            } else {
                                LiveEventPhase.OFFSCREEN_PENDING
                            }
                        }
                        LiveEventPhase.ENTERING -> {
                            // If user scrolled away before enter animation completed, immediately become OFFSCREEN_PENDING
                            if (isVisible) {
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

                entry.copy(phase = nextPhase)
            }

        return if (next == state.entries) state else state.copy(entries = next)
    }

    fun enterAnimationFinished(
        state: LiveEventTrackerState,
        eventId: String,
        batchId: Long,
        isCurrentlyVisible: Boolean,
    ): LiveEventTrackerState {
        val index =
            state.entries.indexOfFirst {
                it.eventId == eventId && it.batchId == batchId && it.phase == LiveEventPhase.ENTERING
            }
        if (index < 0) return state

        val next = state.entries.toMutableList()
        next[index] =
            next[index].copy(
                phase = if (isCurrentlyVisible) LiveEventPhase.PRESENTED else LiveEventPhase.OFFSCREEN_PENDING,
            )
        return state.copy(entries = next)
    }

    fun pruneRemovedEvents(
        state: LiveEventTrackerState,
        currentFeedIds: Set<String>,
    ): LiveEventTrackerState {
        val next = state.entries.filter { it.eventId in currentFeedIds }
        return if (next == state.entries) state else state.copy(entries = next)
    }
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

internal data class EventsViewportSnapshot(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val visibleItemIds: Set<String> = emptySet(),
    val minVisibleItemIndex: Int? = null,
    val maxVisibleItemIndex: Int? = null,
    val isAtTop: Boolean = true,
)

internal data class LivePresentationPlan(
    val batchId: Long = 0L,
    val enterEventIds: Set<String> = emptySet(),
    val offscreenEventIds: List<String> = emptyList(),
    val removedEventIds: Set<String> = emptySet(),
    val cascadeIndices: Map<String, Int> = emptyMap(),
    val batchSize: Int = 0,
)

internal data class ExitingEventRecord(
    val event: Event,
    val generation: Long,
    val originalIndex: Int = -1,
    val predecessorId: String? = null,
)

internal fun buildLivePresentationPlan(
    batchId: Long,
    createdEventIds: List<String>,
    removedEventIds: Set<String>,
    events: List<Event>,
    viewportSnapshot: EventsViewportSnapshot,
    animatedEventIds: Set<String>,
): LivePresentationPlan {
    if ((createdEventIds.isEmpty() && removedEventIds.isEmpty()) || batchId <= 0L) {
        return LivePresentationPlan(
            batchId = batchId,
            removedEventIds = removedEventIds,
        )
    }

    val newCandidateIds = createdEventIds.filter { it !in animatedEventIds }
    val enterIds = mutableSetOf<String>()
    val offscreenIds = mutableListOf<String>()

    for (id in newCandidateIds) {
        if (viewportSnapshot.isAtTop || id in viewportSnapshot.visibleItemIds) {
            enterIds.add(id)
        } else {
            offscreenIds.add(id)
        }
    }

    val cascadeMap =
        if (enterIds.size in 1..4) {
            enterIds.mapIndexed { idx, id -> id to idx }.toMap()
        } else {
            emptyMap()
        }

    return LivePresentationPlan(
        batchId = batchId,
        enterEventIds = enterIds,
        offscreenEventIds = offscreenIds,
        removedEventIds = removedEventIds,
        cascadeIndices = cascadeMap,
        batchSize = enterIds.size,
    )
}

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
