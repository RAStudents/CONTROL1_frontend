package studio.acmenra.control.feature.events.domain.model

import kotlinx.datetime.LocalDate

/**
 * Temporary calendar selection. This is not duplicated inside [EventsFilter]: the applied filter
 * has one canonical representation, UTC half-open bounds (`dateFrom` / `dateTo`).
 */
sealed interface DateSelection {
    /** No date restriction. */
    data object All : DateSelection

    /** One local calendar day. */
    data class Single(
        val date: LocalDate,
    ) : DateSelection

    /** Inclusive local calendar range. */
    data class Range(
        val startDate: LocalDate,
        val endDateInclusive: LocalDate,
    ) : DateSelection {
        val endDate: LocalDate get() = endDateInclusive

        init {
            require(startDate < endDateInclusive) {
                "A one-day selection must use DateSelection.Single; a range must span at least two days"
            }
        }
    }

    /** An open-ended lower bound restored from an existing query, if the API supports it. */
    data class From(
        val startDate: LocalDate,
    ) : DateSelection

    /** An open-ended upper bound restored from an existing query, if the API supports it. */
    data class Until(
        val endDateInclusive: LocalDate,
    ) : DateSelection {
        val endDate: LocalDate get() = endDateInclusive
    }
}
