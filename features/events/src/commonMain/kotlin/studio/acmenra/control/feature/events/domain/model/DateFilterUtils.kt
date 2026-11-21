package studio.acmenra.control.feature.events.domain.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.offsetIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Converts calendar selections to the Events API's UTC half-open interval.
 *
 * The platform bootstrap must initialize the IANA timezone database on WasmJS before these
 * functions receive region-based zones.
 */
object DateFilterUtils {
    private const val MOSCOW_TIME_ZONE_ID = "Europe/Moscow"

    /** Returns null for absent/invalid zone IDs; callers should keep the date control disabled. */
    fun resolveBusinessTimeZone(zoneId: String?): TimeZone? {
        ensureTimezoneDatabaseInitialized()
        if (zoneId.isNullOrBlank()) return null
        return runCatching { TimeZone.of(zoneId.trim()) }.getOrNull()
    }

    /** Strict variant for domain operations that cannot proceed without a valid business zone. */
    fun parseSafeTimeZone(zoneId: String?): TimeZone =
        resolveBusinessTimeZone(zoneId)
            ?: throw IllegalArgumentException("Invalid or unavailable business timezone: '$zoneId'")

    /**
     * Converts a calendar selection to [fromInclusive, toExclusive) UTC bounds.
     * Calendar arithmetic is performed on LocalDate, never by adding 24 hours to an Instant;
     * local days around daylight-saving transitions can be 23 or 25 hours long.
     */
    fun dateSelectionToUtcBounds(
        selection: DateSelection,
        businessTimeZone: TimeZone,
    ): Pair<Instant?, Instant?> =
        when (selection) {
            DateSelection.All -> null to null

            is DateSelection.Single -> {
                val from = selection.date.atStartOfDayIn(businessTimeZone)
                val to = selection.date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(businessTimeZone)
                from to to
            }

            is DateSelection.Range -> {
                require(selection.startDate < selection.endDateInclusive) {
                    "Range startDate must not be after endDateInclusive"
                }
                val from = selection.startDate.atStartOfDayIn(businessTimeZone)
                val to =
                    selection.endDateInclusive
                        .plus(1, DateTimeUnit.DAY)
                        .atStartOfDayIn(businessTimeZone)
                from to to
            }

            is DateSelection.From -> selection.startDate.atStartOfDayIn(businessTimeZone) to null

            is DateSelection.Until -> {
                val to =
                    selection.endDateInclusive
                        .plus(1, DateTimeUnit.DAY)
                        .atStartOfDayIn(businessTimeZone)
                null to to
            }
        }

    /**
     * Restores the calendar model from API UTC half-open bounds.
     *
     * This conversion is intentionally strict: DateRangePicker represents whole local dates, so a
     * non-midnight boundary cannot be represented faithfully. Throw rather than silently turning
     * such a filter into `All` or rounding it to a different day.
     */
    fun utcBoundsToDateSelection(
        dateFrom: Instant?,
        dateTo: Instant?,
        businessTimeZone: TimeZone,
    ): DateSelection {
        if (dateFrom == null && dateTo == null) return DateSelection.All

        val startDate = dateFrom?.toLocalDateTime(businessTimeZone)?.date
        val endExclusiveDate = dateTo?.toLocalDateTime(businessTimeZone)?.date

        if (dateFrom != null) {
            require(startDate != null && dateFrom == startDate.atStartOfDayIn(businessTimeZone)) {
                "dateFrom must be the start of a local day in the business timezone"
            }
        }
        if (dateTo != null) {
            require(endExclusiveDate != null && dateTo == endExclusiveDate.atStartOfDayIn(businessTimeZone)) {
                "dateTo must be an exclusive local-day boundary in the business timezone"
            }
        }

        return when {
            startDate != null && endExclusiveDate != null -> {
                require(endExclusiveDate > startDate) { "dateTo must be after dateFrom" }
                val endInclusive = endExclusiveDate.minus(1, DateTimeUnit.DAY)
                if (startDate == endInclusive) {
                    DateSelection.Single(startDate)
                } else {
                    DateSelection.Range(startDate, endInclusive)
                }
            }

            startDate != null -> DateSelection.From(startDate)
            endExclusiveDate != null -> DateSelection.Until(endExclusiveDate.minus(1, DateTimeUnit.DAY))
            else -> DateSelection.All
        }
    }

    /**
     * Difference between the business-zone and Moscow UTC offsets at the exact same Instant.
     * Keep this numeric in domain; formatting `MSK+2:30` and localization belong in presentation.
     */
    fun mskOffsetAt(
        at: Instant,
        businessTimeZone: TimeZone,
    ): MskOffset {
        ensureTimezoneDatabaseInitialized()
        val moscowTimeZone = TimeZone.of(MOSCOW_TIME_ZONE_ID)
        val businessOffsetSeconds = at.offsetIn(businessTimeZone).totalSeconds
        val moscowOffsetSeconds = at.offsetIn(moscowTimeZone).totalSeconds
        return MskOffset(businessOffsetSeconds - moscowOffsetSeconds)
    }

    fun formatMskOffset(
        at: Instant,
        businessTimeZone: TimeZone,
        mskPrefix: String = "MSK",
    ): String {
        val offset = mskOffsetAt(at, businessTimeZone)
        val diffSeconds = offset.totalSecondsFromMoscow
        if (diffSeconds == 0) return mskPrefix
        val sign = if (diffSeconds > 0) "+" else "-"
        val absSec = kotlin.math.abs(diffSeconds)
        val hours = absSec / 3600
        val minutes = (absSec % 3600) / 60
        val seconds = absSec % 60
        return when {
            seconds > 0 ->
                "$mskPrefix$sign$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
            minutes > 0 -> "$mskPrefix$sign$hours:${minutes.toString().padStart(2, '0')}"
            else -> "$mskPrefix$sign$hours"
        }
    }

    fun formatLocalDate(date: LocalDate): String {
        val d = date.day.toString().padStart(2, '0')
        val m =
            date.month.number
                .toString()
                .padStart(2, '0')
        return "$d.$m.${date.year}"
    }

    fun formatFilterDisplayDate(
        dateFrom: Instant?,
        dateTo: Instant?,
        businessTimeZone: TimeZone?,
    ): String? {
        if (businessTimeZone == null || (dateFrom == null && dateTo == null)) return null
        if (dateFrom != null && dateTo != null) {
            val startDate = dateFrom.toLocalDateTime(businessTimeZone).date
            val endExclusiveDate = dateTo.toLocalDateTime(businessTimeZone).date
            val isFromMidnight = dateFrom == startDate.atStartOfDayIn(businessTimeZone)
            val isToMidnight = dateTo == endExclusiveDate.atStartOfDayIn(businessTimeZone)
            if (isFromMidnight && isToMidnight && endExclusiveDate > startDate) {
                val endInclusiveDate = endExclusiveDate.minus(1, DateTimeUnit.DAY)
                return if (startDate == endInclusiveDate) {
                    formatLocalDate(startDate)
                } else {
                    "${formatLocalDate(startDate)} – ${formatLocalDate(endInclusiveDate)}"
                }
            }
            return "${formatLocalDate(startDate)} – ${formatLocalDate(endExclusiveDate)}"
        } else if (dateFrom != null) {
            val startDate = dateFrom.toLocalDateTime(businessTimeZone).date
            return "от ${formatLocalDate(startDate)}"
        } else if (dateTo != null) {
            val endExclusiveDate = dateTo.toLocalDateTime(businessTimeZone).date
            val isToMidnight = dateTo == endExclusiveDate.atStartOfDayIn(businessTimeZone)
            val endDisplayDate =
                if (isToMidnight) endExclusiveDate.minus(1, DateTimeUnit.DAY) else endExclusiveDate
            return "до ${formatLocalDate(endDisplayDate)}"
        }
        return null
    }
}
