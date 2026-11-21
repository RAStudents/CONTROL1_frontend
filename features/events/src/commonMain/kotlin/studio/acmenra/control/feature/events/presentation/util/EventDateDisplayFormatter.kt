package studio.acmenra.control.feature.events.presentation.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import kotlin.time.Instant

object EventDateDisplayFormatter {
    fun formatLocalDate(date: LocalDate): String {
        val d = date.day.toString().padStart(2, '0')
        val monthNumber = date.month.number
        val m = monthNumber.toString().padStart(2, '0')
        return "$d.$m.${date.year}"
    }

    fun formatLocalDateTime(dateTime: LocalDateTime): String {
        val d = dateTime.day.toString().padStart(2, '0')
        val monthNumber = dateTime.month.number
        val m = monthNumber.toString().padStart(2, '0')
        val y = dateTime.year
        val h = dateTime.hour.toString().padStart(2, '0')
        val min = dateTime.minute.toString().padStart(2, '0')
        val sec = dateTime.second
        return if (sec > 0) {
            val s = sec.toString().padStart(2, '0')
            "$d.$m.$y $h:$min:$s"
        } else {
            "$d.$m.$y $h:$min"
        }
    }

    fun formatMskOffset(
        at: Instant,
        businessTimeZone: TimeZone,
        mskPrefix: String = "MSK",
    ): String {
        val offset = DateFilterUtils.mskOffsetAt(at, businessTimeZone)
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

    fun formatFilterDisplayDate(
        dateFrom: Instant?,
        dateTo: Instant?,
        businessTimeZone: TimeZone?,
        fromPrefix: String = "от",
        toPrefix: String = "до",
        customIntervalPrefix: String? = null,
    ): String? {
        if (businessTimeZone == null || (dateFrom == null && dateTo == null)) return null

        if (dateFrom != null && dateTo != null) {
            val fromDateTime = dateFrom.toLocalDateTime(businessTimeZone)
            val toDateTime = dateTo.toLocalDateTime(businessTimeZone)
            val startDate = fromDateTime.date
            val endExclusiveDate = toDateTime.date
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

            val formatted = "${formatLocalDateTime(fromDateTime)} – ${formatLocalDateTime(toDateTime)}"
            return if (customIntervalPrefix != null) "$customIntervalPrefix: $formatted" else formatted
        } else if (dateFrom != null) {
            val fromDateTime = dateFrom.toLocalDateTime(businessTimeZone)
            val startDate = fromDateTime.date
            val isFromMidnight = dateFrom == startDate.atStartOfDayIn(businessTimeZone)
            return if (isFromMidnight) {
                "$fromPrefix ${formatLocalDate(startDate)}"
            } else {
                val formatted = "$fromPrefix ${formatLocalDateTime(fromDateTime)}"
                if (customIntervalPrefix != null) "$customIntervalPrefix: $formatted" else formatted
            }
        } else if (dateTo != null) {
            val toDateTime = dateTo.toLocalDateTime(businessTimeZone)
            val endExclusiveDate = toDateTime.date
            val isToMidnight = dateTo == endExclusiveDate.atStartOfDayIn(businessTimeZone)
            return if (isToMidnight) {
                val endDisplayDate = endExclusiveDate.minus(1, DateTimeUnit.DAY)
                "$toPrefix ${formatLocalDate(endDisplayDate)}"
            } else {
                val formatted = "$toPrefix ${formatLocalDateTime(toDateTime)}"
                if (customIntervalPrefix != null) "$customIntervalPrefix: $formatted" else formatted
            }
        }
        return null
    }
}
