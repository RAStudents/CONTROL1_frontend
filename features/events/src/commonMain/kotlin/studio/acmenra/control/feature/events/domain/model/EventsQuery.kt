package studio.acmenra.control.feature.events.domain.model

/** The filter and ordering applied to an events page request. */
data class EventsQuery(
    val filter: EventsFilter = EventsFilter(),
    val sort: EventsSort = EventsSort.OCCURRED_AT_DESC,
) {
    /** Normalize once at the boundary where the query is hashed/serialized. */
    fun normalized(): EventsQuery = copy(filter = filter.normalized())
}
