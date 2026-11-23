package studio.acmenra.control.feature.events.presentation.components

import studio.acmenra.control.feature.events.domain.model.Event

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
        if (viewportSnapshot.isAtTop) {
            enterIds.add(id)
        } else if (id in viewportSnapshot.visibleItemIds) {
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
    return result
}
