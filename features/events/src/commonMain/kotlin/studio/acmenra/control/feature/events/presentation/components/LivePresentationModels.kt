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
    val sessionGeneration: Long = 0L,
) {
    fun onSessionGenerationChanged(newGeneration: Long): LiveEventTrackerState {
        if (newGeneration == sessionGeneration) return this
        val preserved = entries.filter { it.phase == LiveEventPhase.OFFSCREEN_PENDING }
        return copy(
            sessionGeneration = newGeneration,
            entries = preserved,
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

internal const val MAX_PRESENTED_ENTRIES = 200

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
                .filter { id -> id !in removedIds && knownIds.add(id) }
                .mapIndexed { index, id ->
                    LiveEventPresentationEntry(
                        eventId = id,
                        batchId = delta.batchId,
                        batchOrder = index,
                        phase = LiveEventPhase.AWAITING_LAYOUT,
                    )
                }

        return state.copy(
            entries = remaining + additions,
            lastRegisteredBatchId = delta.batchId,
        )
    }

    fun reconcileAfterLayout(
        state: LiveEventTrackerState,
        actuallyVisibleKeys: Set<String>,
        expandingInViewportIds: Set<String> = emptySet(),
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
