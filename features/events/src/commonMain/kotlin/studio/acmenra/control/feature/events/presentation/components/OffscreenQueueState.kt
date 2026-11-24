package studio.acmenra.control.feature.events.presentation.components

internal const val MAX_OFFSCREEN_QUEUE_ENTRIES = 500

internal enum class OffscreenDirection {
    UP,
    DOWN,
}

internal sealed interface OffscreenQueueEntry {
    data class LoadedEvent(
        val eventId: String,
    ) : OffscreenQueueEntry

    data class PendingNewerAtTop(
        val eventIds: List<String> = emptyList(),
        val reportedCount: Int = eventIds.size,
    ) : OffscreenQueueEntry {
        val effectiveCount: Int
            get() = maxOf(eventIds.size, reportedCount)
    }

    data class PendingHistory(
        val count: Int,
    ) : OffscreenQueueEntry
}

internal sealed interface OffscreenJumpTarget {
    val direction: OffscreenDirection
        get() =
            when (this) {
                is LoadedEvent -> direction
                is PendingNewerAtTop -> direction
                is PendingHistoryRefresh -> OffscreenDirection.DOWN
            }

    data class LoadedEvent(
        val eventId: String,
        val lazyItemIndex: Int,
        override val direction: OffscreenDirection,
    ) : OffscreenJumpTarget

    data class PendingNewerAtTop(
        val eventIds: List<String> = emptyList(),
        override val direction: OffscreenDirection = OffscreenDirection.UP,
    ) : OffscreenJumpTarget

    data object PendingHistoryRefresh : OffscreenJumpTarget
}

internal sealed interface OffscreenHeadResolution {
    data object Empty : OffscreenHeadResolution

    data class AwaitingLayout(
        val targetEventId: String,
        val fallbackDirection: OffscreenDirection = OffscreenDirection.UP,
        val pendingCount: Int = 1,
    ) : OffscreenHeadResolution

    data class Ready(
        val target: OffscreenJumpTarget,
        val directionalCount: Int,
    ) : OffscreenHeadResolution
}

internal enum class OffscreenPillStatus {
    HIDDEN,
    AWAITING_LAYOUT,
    READY,
    IN_FLIGHT,
}

internal fun computePillStatus(
    headResolution: OffscreenHeadResolution,
    isInFlight: Boolean = false,
): OffscreenPillStatus =
    when {
        isInFlight -> OffscreenPillStatus.IN_FLIGHT
        headResolution is OffscreenHeadResolution.Ready -> OffscreenPillStatus.READY
        headResolution is OffscreenHeadResolution.AwaitingLayout -> OffscreenPillStatus.AWAITING_LAYOUT
        else -> OffscreenPillStatus.HIDDEN
    }

internal enum class PendingNewerFlushReason {
    TRANSIENT_AT_TOP,
    ACCUMULATED_WHILE_AWAY,
}

internal enum class PendingNewerFlushPhase {
    IDLE,
    SCROLLING_TO_TOP,
    DISPATCHING_FLUSH,
    AWAITING_MATERIALIZATION,
    AWAITING_LAYOUT,
    RECONCILING,
}

internal data class PendingNewerFlushContext(
    val operationId: Long = 0L,
    val phase: PendingNewerFlushPhase = PendingNewerFlushPhase.IDLE,
    val reason: PendingNewerFlushReason = PendingNewerFlushReason.ACCUMULATED_WHILE_AWAY,
    val capturedCount: Int,
    val feedScopeKey: String,
    val capturedEventIds: List<String> = emptyList(),
    val preFlushEventIds: Set<String> = emptySet(),
    val expectedBatchId: Long? = null,
    val expectedSnapshotId: String? = null,
    val result: studio.acmenra.control.feature.events.domain.repository.PendingNewerFlushResult? = null,
)

internal data class OffscreenQueueState(
    val entries: List<OffscreenQueueEntry> = emptyList(),
) {
    val totalCount: Int
        get() =
            entries.sumOf { entry ->
                when (entry) {
                    is OffscreenQueueEntry.LoadedEvent -> 1
                    is OffscreenQueueEntry.PendingNewerAtTop -> entry.effectiveCount
                    is OffscreenQueueEntry.PendingHistory -> entry.count
                }
            }

    val isVisible: Boolean get() = totalCount > 0

    val hasPendingNewerMarker: Boolean
        get() = entries.any { it is OffscreenQueueEntry.PendingNewerAtTop }

    val pendingNewerMarkerCount: Int
        get() = entries.filterIsInstance<OffscreenQueueEntry.PendingNewerAtTop>().firstOrNull()?.effectiveCount ?: 0

    val hasPendingHistoryMarker: Boolean
        get() = entries.any { it is OffscreenQueueEntry.PendingHistory }

    val pendingHistoryCount: Int
        get() = entries.filterIsInstance<OffscreenQueueEntry.PendingHistory>().firstOrNull()?.count ?: 0

    val loadedEventIds: List<String>
        get() = entries.filterIsInstance<OffscreenQueueEntry.LoadedEvent>().map { it.eventId }

    /**
     * Enqueues newly detected loaded event IDs.
     * Deduplicates only against IDs currently active in entries.
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
        val capped =
            if (newEntries.size > MAX_OFFSCREEN_QUEUE_ENTRIES) {
                val nonLoaded = newEntries.filterNot { it is OffscreenQueueEntry.LoadedEvent }
                val loaded = newEntries.filterIsInstance<OffscreenQueueEntry.LoadedEvent>()
                val allowed = (MAX_OFFSCREEN_QUEUE_ENTRIES - nonLoaded.size).coerceAtLeast(0)
                nonLoaded + loaded.take(allowed)
            } else {
                newEntries
            }
        return copy(entries = capped)
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
     * Clears the pending newer marker without losing live events.
     */
    fun clearPendingNewerMarker(): OffscreenQueueState =
        if (hasPendingNewerMarker) {
            val updated = entries.filterNot { it is OffscreenQueueEntry.PendingNewerAtTop }
            copy(entries = updated)
        } else {
            this
        }

    fun replaceLiveTargets(orderedIds: List<String>): OffscreenQueueState {
        val currentLoadedIds = loadedEventIds
        val uniqueIds = orderedIds.distinct()
        if (currentLoadedIds == uniqueIds) {
            return this
        }
        val retained = entries.filterNot { it is OffscreenQueueEntry.LoadedEvent }
        val loaded = uniqueIds.take(MAX_OFFSCREEN_QUEUE_ENTRIES).map { OffscreenQueueEntry.LoadedEvent(it) }
        val historyIndex = retained.indexOfFirst { it is OffscreenQueueEntry.PendingHistory }
        val next =
            if (historyIndex < 0) {
                retained + loaded
            } else {
                retained.subList(0, historyIndex) +
                    loaded +
                    retained.subList(historyIndex, retained.size)
            }
        return copy(entries = next)
    }

    /**
     * Singleton invariant: at most one PendingNewerAtTop in entries.
     * Merges eventIds and updates in-place if already present; places before loaded events or at head.
     */
    fun upsertPendingNewer(
        count: Int,
        eventIds: List<String> = emptyList(),
    ): OffscreenQueueState {
        val existing = entries.filterIsInstance<OffscreenQueueEntry.PendingNewerAtTop>().firstOrNull()
        val mergedIds = (existing?.eventIds.orEmpty() + eventIds).distinct()
        val nextCount = maxOf(count, mergedIds.size)

        if (nextCount <= 0 && mergedIds.isEmpty()) {
            val updated = entries.filterNot { it is OffscreenQueueEntry.PendingNewerAtTop }
            return if (updated.size == entries.size) this else copy(entries = updated)
        }

        val marker =
            OffscreenQueueEntry.PendingNewerAtTop(
                eventIds = mergedIds,
                reportedCount = nextCount,
            )
        val remaining = entries.filterNot { it is OffscreenQueueEntry.PendingNewerAtTop }
        return copy(entries = listOf(marker) + remaining)
    }

    /**
     * Replaces the PendingNewerAtTop marker atomically with materialized loaded event IDs.
     * Preserves remaining pending IDs/count if any arrived during flush.
     */
    fun reconcilePendingNewer(
        materializedIds: List<String>,
        currentlyVisibleIds: Set<String>,
        remainingCreatedIds: List<String> = emptyList(),
        remainingCreatedCount: Int = remainingCreatedIds.size,
    ): OffscreenQueueState {
        val offscreenIds = materializedIds.filter { it !in currentlyVisibleIds }
        val existingLoadedIds = loadedEventIds.toSet()
        val toInsert = offscreenIds.filter { it !in existingLoadedIds }.map { OffscreenQueueEntry.LoadedEvent(it) }

        val remainingEntries = entries.filterNot { it is OffscreenQueueEntry.PendingNewerAtTop }
        val nextEntries = mutableListOf<OffscreenQueueEntry>()

        if (remainingCreatedCount > 0 || remainingCreatedIds.isNotEmpty()) {
            nextEntries.add(
                OffscreenQueueEntry.PendingNewerAtTop(
                    eventIds = remainingCreatedIds,
                    reportedCount = maxOf(remainingCreatedCount, remainingCreatedIds.size),
                ),
            )
        }

        // Materialized items from PendingNewerAtTop belong before older loaded events
        nextEntries.addAll(toInsert)
        nextEntries.addAll(remainingEntries)

        val capped =
            if (nextEntries.size > MAX_OFFSCREEN_QUEUE_ENTRIES) {
                val nonLoaded = nextEntries.filterNot { it is OffscreenQueueEntry.LoadedEvent }
                val loaded = nextEntries.filterIsInstance<OffscreenQueueEntry.LoadedEvent>()
                val allowed = (MAX_OFFSCREEN_QUEUE_ENTRIES - nonLoaded.size).coerceAtLeast(0)
                nonLoaded + loaded.take(allowed)
            } else {
                nextEntries
            }

        return copy(entries = capped)
    }

    fun reconcilePendingNewerWithMaterializedIds(
        materializedIds: List<String>,
        currentlyVisibleIds: Set<String>,
    ): OffscreenQueueState = reconcilePendingNewer(materializedIds, currentlyVisibleIds)

    /**
     * Singleton invariant: at most one PendingHistory at the tail of entries.
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
     * Calculates the directional count for the indicator.
     */
    fun directionalCount(
        direction: OffscreenDirection,
        minVisibleIndex: Int?,
        maxVisibleIndex: Int?,
        eventIdToLazyIndex: Map<String, Int>,
    ): Int {
        var count = 0
        for (entry in entries) {
            when (entry) {
                is OffscreenQueueEntry.PendingNewerAtTop -> {
                    if (direction == OffscreenDirection.UP) {
                        count += entry.effectiveCount
                    }
                }
                is OffscreenQueueEntry.LoadedEvent -> {
                    val idx = eventIdToLazyIndex[entry.eventId]
                    if (idx != null && minVisibleIndex != null && maxVisibleIndex != null) {
                        if (direction == OffscreenDirection.UP && idx < minVisibleIndex) {
                            count++
                        } else if (direction == OffscreenDirection.DOWN && idx > maxVisibleIndex) {
                            count++
                        }
                    }
                }
                is OffscreenQueueEntry.PendingHistory -> {
                    if (direction == OffscreenDirection.DOWN) {
                        count += entry.count
                    }
                }
            }
        }
        return count
    }

    /**
     * Resolves the head target and its direction relative to visible event items in Lazy index space.
     * Prunes invalid or already-visible loaded items.
     */
    fun resolveHeadTarget(
        minVisibleEventLazyIndex: Int?,
        maxVisibleEventLazyIndex: Int?,
        eventIdToLazyIndex: Map<String, Int>,
        allFeedEventIds: Set<String>? = null,
        actuallyVisibleIds: Set<String> = emptySet(),
    ): Pair<OffscreenQueueState, OffscreenHeadResolution> {
        var current =
            if (allFeedEventIds != null) {
                val filtered =
                    entries.filterNot {
                        it is OffscreenQueueEntry.LoadedEvent &&
                            it.eventId !in allFeedEventIds
                    }
                if (filtered.size == entries.size) this else copy(entries = filtered)
            } else {
                this
            }
        while (current.entries.isNotEmpty()) {
            when (val head = current.entries.first()) {
                is OffscreenQueueEntry.PendingNewerAtTop -> {
                    val count =
                        current.directionalCount(
                            direction = OffscreenDirection.UP,
                            minVisibleIndex = minVisibleEventLazyIndex,
                            maxVisibleIndex = maxVisibleEventLazyIndex,
                            eventIdToLazyIndex = eventIdToLazyIndex,
                        )
                    return current to
                        OffscreenHeadResolution.Ready(
                            target =
                                OffscreenJumpTarget.PendingNewerAtTop(
                                    eventIds = head.eventIds,
                                    direction = OffscreenDirection.UP,
                                ),
                            directionalCount = count,
                        )
                }
                is OffscreenQueueEntry.LoadedEvent -> {
                    if (allFeedEventIds != null && head.eventId !in allFeedEventIds) {
                        current = current.removeLoaded(head.eventId)
                        continue
                    }
                    if (head.eventId in actuallyVisibleIds) {
                        current = current.removeLoaded(head.eventId)
                        continue
                    }
                    val targetIndex = eventIdToLazyIndex[head.eventId]
                    if (targetIndex == null) {
                        return current to
                            OffscreenHeadResolution.AwaitingLayout(
                                targetEventId = head.eventId,
                                fallbackDirection = OffscreenDirection.UP,
                                pendingCount = current.totalCount,
                            )
                    }
                    if (minVisibleEventLazyIndex == null || maxVisibleEventLazyIndex == null) {
                        return current to
                            OffscreenHeadResolution.AwaitingLayout(
                                targetEventId = head.eventId,
                                fallbackDirection = OffscreenDirection.UP,
                                pendingCount = current.totalCount,
                            )
                    }
                    when {
                        targetIndex < minVisibleEventLazyIndex -> {
                            val count =
                                current.directionalCount(
                                    direction = OffscreenDirection.UP,
                                    minVisibleIndex = minVisibleEventLazyIndex,
                                    maxVisibleIndex = maxVisibleEventLazyIndex,
                                    eventIdToLazyIndex = eventIdToLazyIndex,
                                )
                            return current to
                                OffscreenHeadResolution.Ready(
                                    target =
                                        OffscreenJumpTarget.LoadedEvent(
                                            eventId = head.eventId,
                                            lazyItemIndex = targetIndex,
                                            direction = OffscreenDirection.UP,
                                        ),
                                    directionalCount = count,
                                )
                        }
                        targetIndex > maxVisibleEventLazyIndex -> {
                            val count =
                                current.directionalCount(
                                    direction = OffscreenDirection.DOWN,
                                    minVisibleIndex = minVisibleEventLazyIndex,
                                    maxVisibleIndex = maxVisibleEventLazyIndex,
                                    eventIdToLazyIndex = eventIdToLazyIndex,
                                )
                            return current to
                                OffscreenHeadResolution.Ready(
                                    target =
                                        OffscreenJumpTarget.LoadedEvent(
                                            eventId = head.eventId,
                                            lazyItemIndex = targetIndex,
                                            direction = OffscreenDirection.DOWN,
                                        ),
                                    directionalCount = count,
                                )
                        }
                        else -> {
                            // Already in visible viewport -> prune
                            current = current.removeLoaded(head.eventId)
                        }
                    }
                }
                is OffscreenQueueEntry.PendingHistory -> {
                    val count =
                        current.directionalCount(
                            direction = OffscreenDirection.DOWN,
                            minVisibleIndex = minVisibleEventLazyIndex,
                            maxVisibleIndex = maxVisibleEventLazyIndex,
                            eventIdToLazyIndex = eventIdToLazyIndex,
                        )
                    return current to
                        OffscreenHeadResolution.Ready(
                            target = OffscreenJumpTarget.PendingHistoryRefresh,
                            directionalCount = count,
                        )
                }
            }
        }
        return current to OffscreenHeadResolution.Empty
    }

    fun computeHeadTarget(
        minVisibleEventLazyIndex: Int?,
        maxVisibleEventLazyIndex: Int?,
        eventIdToLazyIndex: Map<String, Int>,
        allFeedEventIds: Set<String>? = null,
    ): Pair<OffscreenQueueState, OffscreenJumpTarget?> {
        val (q, res) =
            resolveHeadTarget(
                minVisibleEventLazyIndex = minVisibleEventLazyIndex,
                maxVisibleEventLazyIndex = maxVisibleEventLazyIndex,
                eventIdToLazyIndex = eventIdToLazyIndex,
                allFeedEventIds = allFeedEventIds,
            )
        val target =
            when (res) {
                is OffscreenHeadResolution.Ready -> res.target
                else -> null
            }
        return q to target
    }
}

internal fun reconcileQueue(
    currentState: OffscreenQueueState,
    trackerState: LiveEventTrackerState,
    actuallyVisibleKeys: Set<String>,
    allFeedEventIds: Set<String>,
): OffscreenQueueState {
    var state = currentState.removeLoadedAll(actuallyVisibleKeys)
    if (allFeedEventIds.isNotEmpty()) {
        val filtered =
            state.entries.filterNot {
                it is OffscreenQueueEntry.LoadedEvent && it.eventId !in allFeedEventIds
            }
        if (filtered.size != state.entries.size) {
            state = state.copy(entries = filtered)
        }
    }
    val trackerOffscreen =
        trackerState.offscreenPendingIds.filter {
            it in allFeedEventIds && it !in actuallyVisibleKeys
        }
    return state.replaceLiveTargets(trackerOffscreen)
}
