package studio.acmenra.control.feature.events.data.realtime

import studio.acmenra.control.feature.events.domain.model.Event
import studio.acmenra.control.feature.events.domain.model.EventValidityFilter
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.domain.model.EventsQuery
import studio.acmenra.control.feature.events.domain.model.OverlayEntry
import studio.acmenra.control.feature.events.domain.model.OverlayMembership
import studio.acmenra.control.feature.events.domain.model.RealtimeOverlay

object RealtimeOverlayReducer {
    fun matchesFilter(
        event: Event,
        filter: EventsFilter,
    ): Boolean {
        filter.searchId?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
            if (s.length < 3) {
                if (!event.id.equals(s, ignoreCase = true)) return false
            } else {
                if (!event.id.startsWith(s, ignoreCase = true)) return false
            }
        }

        filter.dateFrom?.let { if (event.occurredAt < it) return false }
        filter.dateTo?.let { if (event.occurredAt >= it) return false }

        if (filter.eventTypeIds.isNotEmpty() && event.eventTypeId !in filter.eventTypeIds) {
            return false
        }

        if (filter.deviceIds.isNotEmpty() && event.deviceId !in filter.deviceIds) {
            return false
        }

        when (filter.validity) {
            EventValidityFilter.ALL -> {}
            EventValidityFilter.VALID -> if (event.isErroneous) return false
            EventValidityFilter.ERRONEOUS -> if (!event.isErroneous) return false
        }

        return true
    }

    fun compareEvents(
        a: Event,
        b: Event,
        searchId: String?,
    ): Int {
        val rawSearch = searchId?.trim()?.takeIf { it.isNotEmpty() }
        if (rawSearch != null && rawSearch.length >= 3) {
            val aExact = a.id.equals(rawSearch, ignoreCase = true)
            val bExact = b.id.equals(rawSearch, ignoreCase = true)
            if (aExact != bExact) return if (aExact) -1 else 1
        }
        val timeCmp = b.occurredAt.compareTo(a.occurredAt)
        if (timeCmp != 0) return timeCmp
        return b.id.compareTo(a.id)
    }

    fun applyEventCommit(
        currentOverlay: RealtimeOverlay,
        event: Event,
        query: EventsQuery,
        snapshotItems: List<Event>,
    ): Pair<RealtimeOverlay, OverlayMembership> {
        val existingEntry = currentOverlay.entries[event.id]
        val existingInSnapshot = snapshotItems.firstOrNull { it.id == event.id }
        val currentEvent = existingEntry?.event ?: existingInSnapshot

        // Validate versions: >= 1, safe integers, specialized <= event_version
        if (event.eventVersion < 1L ||
            !TenantSequenceProcessor.isValidSafeInteger(event.eventVersion) ||
            event.classificationVersion < 1L ||
            !TenantSequenceProcessor.isValidSafeInteger(event.classificationVersion) ||
            event.acknowledgementVersion < 1L ||
            !TenantSequenceProcessor.isValidSafeInteger(event.acknowledgementVersion) ||
            event.classificationVersion > event.eventVersion ||
            event.acknowledgementVersion > event.eventVersion
        ) {
            return currentOverlay.copy(requiresRefresh = true) to
                (existingEntry?.membership ?: OverlayMembership.INCLUDED)
        }

        if (currentEvent != null) {
            if (event.eventVersion <= currentEvent.eventVersion) {
                // Strict event_version gating: older or equal event_version cannot update overlay
                return currentOverlay to (existingEntry?.membership ?: OverlayMembership.INCLUDED)
            }

            // Same specialized version MUST mean identical data
            if (event.classificationVersion == currentEvent.classificationVersion &&
                event.isErroneous != currentEvent.isErroneous
            ) {
                return currentOverlay.copy(requiresRefresh = true) to
                    (existingEntry?.membership ?: OverlayMembership.INCLUDED)
            }

            if (event.acknowledgementVersion == currentEvent.acknowledgementVersion &&
                (
                    event.status != currentEvent.status ||
                        event.acknowledgedAt != currentEvent.acknowledgedAt ||
                        event.acknowledgedBy != currentEvent.acknowledgedBy
                )
            ) {
                return currentOverlay.copy(requiresRefresh = true) to
                    (existingEntry?.membership ?: OverlayMembership.INCLUDED)
            }
        }

        val effectiveEvent =
            if (currentEvent != null) {
                EventMerger.merge(current = currentEvent, incoming = event)
            } else {
                event
            }

        val matches = matchesFilter(effectiveEvent, query.filter)
        val membership =
            when {
                !matches -> OverlayMembership.REMOVED
                snapshotItems.isEmpty() -> OverlayMembership.INCLUDED
                existingInSnapshot != null || existingEntry?.membership == OverlayMembership.INCLUDED -> {
                    OverlayMembership.INCLUDED
                }
                else -> {
                    val oldestSnapshot = snapshotItems.last()
                    val newestSnapshot = snapshotItems.first()
                    val cmpNewest = compareEvents(effectiveEvent, newestSnapshot, query.filter.searchId)
                    val cmpOldest = compareEvents(effectiveEvent, oldestSnapshot, query.filter.searchId)

                    when {
                        // Newer than newest visible item -> queue as pending newer
                        cmpNewest < 0 -> OverlayMembership.PENDING_NEWER
                        // Older than oldest snapshot item -> queue as pending history
                        cmpOldest > 0 -> OverlayMembership.PENDING_HISTORY
                        // Between existing snapshot range
                        else -> OverlayMembership.INCLUDED
                    }
                }
            }

        val newEntries = currentOverlay.entries.toMutableMap()
        newEntries[effectiveEvent.id] =
            OverlayEntry(
                eventId = effectiveEvent.id,
                event = effectiveEvent,
                eventVersion = effectiveEvent.eventVersion,
                membership = membership,
            )

        val newOverlay = currentOverlay.copy(entries = newEntries)
        return newOverlay to membership
    }

    fun integratePendingNewer(overlay: RealtimeOverlay): RealtimeOverlay {
        var changed = false
        val updatedEntries =
            overlay.entries.mapValues { (_, entry) ->
                if (entry.membership == OverlayMembership.PENDING_NEWER) {
                    changed = true
                    entry.copy(membership = OverlayMembership.INCLUDED)
                } else {
                    entry
                }
            }
        return if (changed) overlay.copy(entries = updatedEntries) else overlay
    }

    fun computePendingCounts(overlay: RealtimeOverlay): Pair<Int, Int> {
        var newer = 0
        var history = 0
        for (entry in overlay.entries.values) {
            when (entry.membership) {
                OverlayMembership.PENDING_NEWER -> newer++
                OverlayMembership.PENDING_HISTORY -> history++
                else -> {}
            }
        }
        return newer to history
    }

    fun computeVisibleEvents(
        snapshotItems: List<Event>,
        overlay: RealtimeOverlay,
        query: EventsQuery,
    ): List<Event> {
        val resultMap = mutableMapOf<String, Event>()

        // 1. Add base snapshot items
        for (item in snapshotItems) {
            resultMap[item.id] = item
        }

        // 2. Apply overlay
        for ((id, entry) in overlay.entries) {
            when (entry.membership) {
                OverlayMembership.REMOVED -> {
                    resultMap.remove(id)
                }
                OverlayMembership.INCLUDED -> {
                    val current = resultMap[id]
                    if (current == null || entry.eventVersion >= current.eventVersion) {
                        resultMap[id] = entry.event
                    }
                }
                OverlayMembership.PENDING_NEWER, OverlayMembership.PENDING_HISTORY -> {
                    // Not placed in visible list yet
                }
            }
        }

        // 3. Sort by canonical comparator
        return resultMap.values.sortedWith { a, b ->
            compareEvents(a, b, query.filter.searchId)
        }
    }
}
