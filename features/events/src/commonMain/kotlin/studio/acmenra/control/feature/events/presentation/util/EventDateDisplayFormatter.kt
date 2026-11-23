package studio.acmenra.control.feature.events.presentation.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.month_10_genitive
import studio.acmenra.control.core.ui.generated.resources.month_11_genitive
import studio.acmenra.control.core.ui.generated.resources.month_12_genitive
import studio.acmenra.control.core.ui.generated.resources.month_1_genitive
import studio.acmenra.control.core.ui.generated.resources.month_2_genitive
import studio.acmenra.control.core.ui.generated.resources.month_3_genitive
import studio.acmenra.control.core.ui.generated.resources.month_4_genitive
import studio.acmenra.control.core.ui.generated.resources.month_5_genitive
import studio.acmenra.control.core.ui.generated.resources.month_6_genitive
import studio.acmenra.control.core.ui.generated.resources.month_7_genitive
import studio.acmenra.control.core.ui.generated.resources.month_8_genitive
import studio.acmenra.control.core.ui.generated.resources.month_9_genitive
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import kotlin.time.Instant

object EventDateDisplayFormatter {
    fun monthGenitiveStringResource(monthNumber: Int): StringResource =
        when (monthNumber) {
            1 -> Res.string.month_1_genitive
            2 -> Res.string.month_2_genitive
            3 -> Res.string.month_3_genitive
            4 -> Res.string.month_4_genitive
            5 -> Res.string.month_5_genitive
            6 -> Res.string.month_6_genitive
            7 -> Res.string.month_7_genitive
            8 -> Res.string.month_8_genitive
            9 -> Res.string.month_9_genitive
            10 -> Res.string.month_10_genitive
            11 -> Res.string.month_11_genitive
            12 -> Res.string.month_12_genitive
            else -> throw IllegalArgumentException("Invalid month number: $monthNumber")
        }

    fun formatEventDayHeader(
        date: LocalDate,
        today: LocalDate,
        monthName: String,
        formatToday: (baseDate: String) -> String,
        formatYesterday: (baseDate: String) -> String,
        formatCurrentYear: (day: Int, month: String) -> String,
        formatOtherYear: (day: Int, month: String, year: Int) -> String,
    ): String {
        val yesterday = LocalDate.fromEpochDays(today.toEpochDays() - 1)
        return when (date) {
            today -> {
                val baseDate =
                    if (date.year == today.year) {
                        formatCurrentYear(date.day, monthName)
                    } else {
                        formatOtherYear(date.day, monthName, date.year)
                    }
                formatToday(baseDate)
            }
            yesterday -> {
                val baseDate =
                    if (date.year == today.year) {
                        formatCurrentYear(date.day, monthName)
                    } else {
                        formatOtherYear(date.day, monthName, date.year)
                    }
                formatYesterday(baseDate)
            }
            else -> {
                if (date.year == today.year) {
                    formatCurrentYear(date.day, monthName)
                } else {
                    formatOtherYear(date.day, monthName, date.year)
                }
            }
        }
    }

    fun formatEventDayHeader(
        date: LocalDate,
        today: LocalDate,
        monthName: String,
        todayPattern: String,
        yesterdayPattern: String,
        currentYearPattern: String,
        otherYearPattern: String,
    ): String =
        formatEventDayHeader(
            date = date,
            today = today,
            monthName = monthName,
            formatToday = { base -> replacePattern(todayPattern, base) },
            formatYesterday = { base -> replacePattern(yesterdayPattern, base) },
            formatCurrentYear = { d, m -> replacePattern(currentYearPattern, d.toString(), m) },
            formatOtherYear = { d, m, y -> replacePattern(otherYearPattern, d.toString(), m, y.toString()) },
        )

    private fun replacePattern(
        pattern: String,
        vararg args: String,
    ): String {
        var result = pattern
        for (i in args.indices) {
            val idx = i + 1
            result =
                result
                    .replace("%$idx\$s", args[i])
                    .replace("%$idx\$d", args[i])
        }
        return result
    }

    fun formatEventCardSupportingText(
        occurredAt: Instant,
        businessTimeZone: TimeZone,
        locationName: String?,
        deviceName: String?,
    ): String {
        val local = occurredAt.toLocalDateTime(businessTimeZone)
        val h = local.hour.toString().padStart(2, '0')
        val min = local.minute.toString().padStart(2, '0')
        val time = "$h:$min"
        val loc = locationName?.trim()?.takeIf { it.isNotEmpty() }
        val dev = deviceName?.trim()?.takeIf { it.isNotEmpty() }
        val meta =
            when {
                loc != null && dev != null -> "$loc, $dev"
                loc != null -> loc
                dev != null -> dev
                else -> null
            }
        return if (meta != null) "$time • $meta" else time
    }

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
