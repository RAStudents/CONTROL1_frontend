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
        isCreated: Boolean,
    ): Pair<RealtimeOverlay, OverlayMembership> {
        val existingEntry = currentOverlay.entries[event.id]
        if (existingEntry != null && existingEntry.eventVersion > event.eventVersion) {
            // Out of order/older version ignored
            return currentOverlay to existingEntry.membership
        }

        val existingInSnapshot = snapshotItems.firstOrNull { it.id == event.id }
        if (existingInSnapshot != null && existingInSnapshot.eventVersion > event.eventVersion) {
            return currentOverlay to (existingEntry?.membership ?: OverlayMembership.INCLUDED)
        }

        val matches = matchesFilter(event, query.filter)
        val membership =
            when {
                !matches -> {
                    if (existingInSnapshot != null || existingEntry?.membership == OverlayMembership.INCLUDED) {
                        OverlayMembership.REMOVED
                    } else {
                        OverlayMembership.REMOVED
                    }
                }
                else -> {
                    // Fits the filter
                    if (snapshotItems.isEmpty()) {
                        OverlayMembership.INCLUDED
                    } else {
                        val oldestSnapshot = snapshotItems.last()
                        val newestSnapshot = snapshotItems.first()
                        val cmpNewest = compareEvents(event, newestSnapshot, query.filter.searchId)
                        val cmpOldest = compareEvents(event, oldestSnapshot, query.filter.searchId)

                        when {
                            // Newer than newest visible item
                            cmpNewest < 0 -> OverlayMembership.INCLUDED
                            // Older than oldest snapshot item
                            cmpOldest > 0 -> OverlayMembership.PENDING_HISTORY
                            // In between or matches existing
                            else -> OverlayMembership.INCLUDED
                        }
                    }
                }
            }

        val newEntries = currentOverlay.entries.toMutableMap()
        newEntries[event.id] =
            OverlayEntry(
                eventId = event.id,
                event = event,
                eventVersion = event.eventVersion,
                membership = membership,
            )

        val newOverlay = currentOverlay.copy(entries = newEntries)
        return newOverlay to membership
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
