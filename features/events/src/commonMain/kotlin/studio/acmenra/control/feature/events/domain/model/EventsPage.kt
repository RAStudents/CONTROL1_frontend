package studio.acmenra.control.feature.events.domain.model

data class EventsPage(
    val items: List<Event>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val totalCount: Int,
    val asOfSequence: Long,
)

data class EventsBootstrap(
    val page: EventsPage,
    val summary: EventsSummary,
    val streamCursor: Long,
)
