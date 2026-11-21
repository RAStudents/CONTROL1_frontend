package studio.acmenra.control.feature.events.domain.model

data class CategoryScore(
    val categoryId: String,
    val name: String,
    val score: Int,
    val trend: ScoreTrend,
)

data class DevicesStatusSummary(
    val totalCount: Int = 0,
    val onlineOkCount: Int = 0,
    val unknownCount: Int = 0,
    val errorCount: Int = 0,
)

data class EventsSummary(
    val businessTimezone: String,
    val businessDate: String,
    val summaryAsOfSequence: Long,
    val todayEventsCount: Int = 0,
    val categories: List<CategoryScore> = emptyList(),
    val devices: DevicesStatusSummary = DevicesStatusSummary(),
)
