package studio.acmenra.control.feature.events.domain.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

typealias EventValidity = EventValidityFilter

data class EventsFilter(
    val searchId: String? = null,
    val dateSelection: DateSelection = DateSelection.All,
    val dateFrom: Instant? = null,
    val dateTo: Instant? = null,
    val eventTypeIds: Set<String> = emptySet(),
    val deviceIds: Set<String> = emptySet(),
    val validity: EventValidityFilter = EventValidityFilter.ALL,
) {
    val activeFiltersCount: Int
        get() =
            listOfNotNull(
                searchId?.trim()?.takeIf { it.isNotEmpty() },
                dateFrom ?: dateTo,
                eventTypeIds.takeIf { it.isNotEmpty() },
                deviceIds.takeIf { it.isNotEmpty() },
                validity.takeIf { it != EventValidityFilter.ALL },
            ).size

    val hasActiveFilters: Boolean
        get() = activeFiltersCount > 0
}

data class EventsQuery(
    val filter: EventsFilter = EventsFilter(),
    val sort: EventsSort = EventsSort.OCCURRED_AT_DESC,
)

sealed interface DateSelection {
    data object All : DateSelection

    data class Single(
        val date: LocalDate,
    ) : DateSelection

    data class Range(
        val startDate: LocalDate,
        val endDate: LocalDate,
    ) : DateSelection
}

object DateFilterUtils {
    fun resolveBusinessTimeZone(zoneId: String?): TimeZone? {
        ensureTimezoneDatabaseInitialized()
        if (zoneId.isNullOrBlank()) return null
        return runCatching { TimeZone.of(zoneId.trim()) }.getOrNull()
    }

    fun parseSafeTimeZone(zoneId: String?): TimeZone =
        resolveBusinessTimeZone(zoneId)
            ?: throw IllegalArgumentException("Invalid or unavailable business timezone: '$zoneId'")

    fun dateSelectionToUtcBounds(
        selection: DateSelection,
        businessTimezone: TimeZone,
    ): Pair<Instant?, Instant?> =
        when (selection) {
            is DateSelection.All -> null to null
            is DateSelection.Single -> {
                val startOfDay = selection.date.atStartOfDayIn(businessTimezone)
                val nextDay = selection.date.plus(1, DateTimeUnit.DAY)
                val startOfNextDay = nextDay.atStartOfDayIn(businessTimezone)
                startOfDay to startOfNextDay
            }
            is DateSelection.Range -> {
                val startOfDay = selection.startDate.atStartOfDayIn(businessTimezone)
                val dayAfterEnd = selection.endDate.plus(1, DateTimeUnit.DAY)
                val startOfDayAfterEnd = dayAfterEnd.atStartOfDayIn(businessTimezone)
                startOfDay to startOfDayAfterEnd
            }
        }

    fun utcBoundsToDateSelection(
        dateFrom: Instant?,
        dateTo: Instant?,
        businessTimezone: TimeZone,
    ): DateSelection {
        if (dateFrom == null || dateTo == null) return DateSelection.All
        val startDate = dateFrom.toLocalDateTime(businessTimezone).date
        // Compute the date corresponding to one second before dateTo to get the inclusive endDate
        val beforeEndInstant = dateTo.minus(1.seconds)
        val endDate = beforeEndInstant.toLocalDateTime(businessTimezone).date
        return if (startDate == endDate) {
            DateSelection.Single(startDate)
        } else {
            DateSelection.Range(startDate, endDate)
        }
    }
}
