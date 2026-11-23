package studio.acmenra.control.feature.events.domain.repository

sealed interface HistoryRefreshResult {
    data object Updated : HistoryRefreshResult

    data object Unchanged : HistoryRefreshResult

    data class Failed(
        val message: String,
    ) : HistoryRefreshResult
}
