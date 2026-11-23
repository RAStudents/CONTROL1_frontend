package studio.acmenra.control.feature.events.presentation.components

internal enum class OffscreenDirection {
    UP,
    DOWN,
}

internal sealed interface OffscreenQueueEntry {
    data class LoadedEvent(
        val eventId: String,
    ) : OffscreenQueueEntry

    data class PendingNewerAtTop(
        val count: Int,
    ) : OffscreenQueueEntry

    data class PendingHistory(
        val count: Int,
    ) : OffscreenQueueEntry
}

internal sealed interface OffscreenJumpTarget {
    data class LoadedEvent(
        val eventId: String,
        val lazyItemIndex: Int,
        val direction: OffscreenDirection,
    ) : OffscreenJumpTarget

    data object PendingNewerAtTop : OffscreenJumpTarget

    data object PendingHistoryRefresh : OffscreenJumpTarget
}

internal enum class PendingNewerFlushReason {
    TRANSIENT_AT_TOP,
    ACCUMULATED_WHILE_AWAY,
}

internal data class PendingNewerFlushContext(
    val reason: PendingNewerFlushReason,
    val capturedCount: Int,
    val feedScopeKey: String,
    val preFlushEventIds: Set<String> = emptySet(),
    val pendingEventIds: List<String> = emptyList(),
    val expectedBatchId: Long? = null,
    val expectedSnapshotId: String? = null,
)

internal data class OffscreenQueueState(
    val entries: List<OffscreenQueueEntry> = emptyList(),
) {
    val totalCount: Int
        get() =
            entries.sumOf { entry ->
                when (entry) {
                    is OffscreenQueueEntry.LoadedEvent -> 1
                    is OffscreenQueueEntry.PendingNewerAtTop -> entry.count
                    is OffscreenQueueEntry.PendingHistory -> entry.count
                }
            }

    val isVisible: Boolean get() = totalCount > 0

    val hasPendingNewerMarker: Boolean
        get() = entries.any { it is OffscreenQueueEntry.PendingNewerAtTop }

    val pendingNewerMarkerCount: Int
        get() = entries.filterIsInstance<OffscreenQueueEntry.PendingNewerAtTop>().firstOrNull()?.count ?: 0

    val hasPendingHistoryMarker: Boolean
        get() = entries.any { it is OffscreenQueueEntry.PendingHistory }

    val pendingHistoryCount: Int
        get() = entries.filterIsInstance<OffscreenQueueEntry.PendingHistory>().firstOrNull()?.count ?: 0

    val loadedEventIds: List<String>
        get() = entries.filterIsInstance<OffscreenQueueEntry.LoadedEvent>().map { it.eventId }

    /**
     * Enqueues newly detected loaded event IDs.
     * Deduplicates only against IDs currently active in entries (§1.6, §8, §12.1).
     */
    fun enqueue(ids: List<String>): OffscreenQueueState {
        if (ids.isEmpty()) return this
        val existingLoadedIds = loadedEventIds.toSet()
        val toAdd = mutableListOf<OffscreenQueueEntry.LoadedEvent>()
        val seenInBatch = mutableSetOf<String>()

        for (id in ids) {
            if (id !in existingLoadedIds && id !in seenInBatch) {
                seenInBatch.add(id)
                toAdd.add(OffscreenQueueEntry.LoadedEvent(id))
            }
        }
        if (toAdd.isEmpty()) return this

        // Insertion order: keep LoadedEvents before PendingHistory (which is tail)
        val historyIndex = entries.indexOfFirst { it is OffscreenQueueEntry.PendingHistory }
        val newEntries =
            if (historyIndex == -1) {
                entries + toAdd
            } else {
                entries.subList(0, historyIndex) + toAdd + entries.subList(historyIndex, entries.size)
            }
        return copy(entries = newEntries)
    }

    /**
     * Removes loaded event IDs that are now visible or no longer valid.
     */
    fun removeLoaded(id: String): OffscreenQueueState {
        val updated = entries.filterNot { it is OffscreenQueueEntry.LoadedEvent && it.eventId == id }
        return if (updated.size == entries.size) this else copy(entries = updated)
    }

    fun removeLoadedAll(ids: Set<String>): OffscreenQueueState {
        if (ids.isEmpty()) return this
        val updated = entries.filterNot { it is OffscreenQueueEntry.LoadedEvent && it.eventId in ids }
        return if (updated.size == entries.size) this else copy(entries = updated)
    }

    /**
     * Singleton invariant (§8): at most one PendingNewerAtTop in entries.
     * Updates in-place if already present; places before loaded events or at head.
     */
    fun upsertPendingNewer(count: Int): OffscreenQueueState {
        if (count <= 0) {
            val updated = entries.filterNot { it is OffscreenQueueEntry.PendingNewerAtTop }
            return if (updated.size == entries.size) this else copy(entries = updated)
        }
        val existingIndex = entries.indexOfFirst { it is OffscreenQueueEntry.PendingNewerAtTop }
        val updatedEntries =
            if (existingIndex != -1) {
                entries.toMutableList().apply {
                    set(existingIndex, OffscreenQueueEntry.PendingNewerAtTop(count))
                }
            } else {
                listOf(OffscreenQueueEntry.PendingNewerAtTop(count)) + entries
            }
        return copy(entries = updatedEntries)
    }

    /**
     * Replaces the PendingNewerAtTop marker atomically with materialized loaded event IDs (§1.6, §8, P2.1).
     * Replaces the marker at its exact position in entries, preserving delivery order without badge drop.
     */
    fun reconcilePendingNewerWithMaterializedIds(
        materializedIds: List<String>,
        currentlyVisibleIds: Set<String>,
    ): OffscreenQueueState {
        val markerIndex = entries.indexOfFirst { it is OffscreenQueueEntry.PendingNewerAtTop }
        val offscreenIds = materializedIds.filter { it !in currentlyVisibleIds }
        val existingLoadedIds = loadedEventIds.toSet()
        val toInsert = offscreenIds.filter { it !in existingLoadedIds }.map { OffscreenQueueEntry.LoadedEvent(it) }

        if (markerIndex == -1) {
            return enqueue(offscreenIds)
        }
        val newEntries =
            entries.toMutableList().apply {
                removeAt(markerIndex)
                addAll(markerIndex, toInsert)
            }
        return copy(entries = newEntries)
    }

    /**
     * Singleton invariant (§8): at most one PendingHistory at the tail of entries.
     */
    fun upsertPendingHistory(
        count: Int,
        requiresRefresh: Boolean,
        hasMore: Boolean,
    ): OffscreenQueueState {
        val isValid = count > 0 && requiresRefresh && hasMore
        if (!isValid) {
            val updated = entries.filterNot { it is OffscreenQueueEntry.PendingHistory }
            return if (updated.size == entries.size) this else copy(entries = updated)
        }
        val existingIndex = entries.indexOfFirst { it is OffscreenQueueEntry.PendingHistory }
        val updatedEntries =
            if (existingIndex != -1) {
                entries.toMutableList().apply {
                    set(existingIndex, OffscreenQueueEntry.PendingHistory(count))
                }
            } else {
                entries + OffscreenQueueEntry.PendingHistory(count)
            }
        return copy(entries = updatedEntries)
    }

    /**
     * Computes the head target and its direction relative to visible event items in Lazy index space (§8.1, P1.9).
     * Returns a pair of (cleanedQueue, target). If head loaded event is already visible or removed from feed,
     * it is pruned so it never blocks subsequent targets.
     */
    fun computeHeadTarget(
        minVisibleEventLazyIndex: Int?,
        maxVisibleEventLazyIndex: Int?,
        eventIdToLazyIndex: Map<String, Int>,
        allFeedEventIds: Set<String>? = null,
    ): Pair<OffscreenQueueState, OffscreenJumpTarget?> {
        var current = this
        while (current.entries.isNotEmpty()) {
            when (val head = current.entries.first()) {
                is OffscreenQueueEntry.PendingNewerAtTop -> {
                    return current to OffscreenJumpTarget.PendingNewerAtTop
                }
                is OffscreenQueueEntry.LoadedEvent -> {
                    if (allFeedEventIds != null && head.eventId !in allFeedEventIds) {
                        // Event no longer exists in authoritative feed: prune it immediately (§8.1, P1.9)
                        current = current.removeLoaded(head.eventId)
                        continue
                    }
                    val targetIndex = eventIdToLazyIndex[head.eventId]
                    if (targetIndex == null) {
                        // Target item not yet measured / index not found in current layout snapshot.
                        // Hold queue and wait for next layout (§8.1 rule 6).
                        return current to null
                    }
                    if (minVisibleEventLazyIndex == null || maxVisibleEventLazyIndex == null) {
                        return current to null
                    }
                    when {
                        targetIndex < minVisibleEventLazyIndex -> {
                            return current to
                                OffscreenJumpTarget.LoadedEvent(
                                    eventId = head.eventId,
                                    lazyItemIndex = targetIndex,
                                    direction = OffscreenDirection.UP,
                                )
                        }
                        targetIndex > maxVisibleEventLazyIndex -> {
                            return current to
                                OffscreenJumpTarget.LoadedEvent(
                                    eventId = head.eventId,
                                    lazyItemIndex = targetIndex,
                                    direction = OffscreenDirection.DOWN,
                                )
                        }
                        else -> {
                            // Target has entered the visible viewport: prune it and continue to next (§8.1 rule 5)
                            current = current.removeLoaded(head.eventId)
                        }
                    }
                }
                is OffscreenQueueEntry.PendingHistory -> {
                    return current to OffscreenJumpTarget.PendingHistoryRefresh
                }
            }
        }
        return current to null
    }
}
