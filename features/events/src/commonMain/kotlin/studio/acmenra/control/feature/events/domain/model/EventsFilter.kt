package studio.acmenra.control.feature.events.domain.model

import kotlin.time.Instant

/** Compatibility alias for callers that use the shorter name. */
typealias EventValidity = EventValidityFilter

/**
 * Applied filters for the events query.
 *
 * Date bounds are Instants in UTC and always use a half-open interval: [dateFrom, dateTo).
 * The business timezone is needed only when converting calendar dates to these bounds; it is not
 * stored in this value object because it belongs to the selected company context.
 *
 * `dateFrom` and `dateTo` are independent nullable boundaries. Whether the backend accepts an
 * open-ended interval must be confirmed against the current API contract before serializing it.
 */
data class EventsFilter(
    val searchId: String? = null,
    /** Inclusive lower UTC boundary. */
    val dateFrom: Instant? = null,
    /** Exclusive upper UTC boundary. */
    val dateTo: Instant? = null,
    val eventTypeIds: Set<String> = emptySet(),
    val deviceIds: Set<String> = emptySet(),
    val validity: EventValidityFilter = EventValidityFilter.ALL,
) {
    init {
        require(dateFrom == null || dateTo == null || dateFrom < dateTo) {
            "dateTo is exclusive and must be after dateFrom"
        }
    }

    /**
     * Returns a canonical copy before query hashing/serialization.
     * Do not trim or rewrite IDs in the UI on every keystroke; normalize only at commit time.
     */
    fun normalized(): EventsFilter =
        copy(
            searchId = searchId?.trim()?.takeIf(String::isNotEmpty),
            // IDs are opaque backend identifiers: remove blank entries, but never rewrite their bytes.
            eventTypeIds = eventTypeIds.filter(String::isNotBlank).toSet(),
            deviceIds = deviceIds.filter(String::isNotBlank).toSet(),
        )

    /** Counts active filter dimensions, not the number of selected IDs. */
    val activeFiltersCount: Int
        get() =
            (if (!searchId.isNullOrBlank()) 1 else 0) +
                (if (dateFrom != null || dateTo != null) 1 else 0) +
                (if (eventTypeIds.any(String::isNotBlank)) 1 else 0) +
                (if (deviceIds.any(String::isNotBlank)) 1 else 0) +
                (if (validity != EventValidityFilter.ALL) 1 else 0)

    val hasActiveFilters: Boolean
        get() = activeFiltersCount > 0
}
