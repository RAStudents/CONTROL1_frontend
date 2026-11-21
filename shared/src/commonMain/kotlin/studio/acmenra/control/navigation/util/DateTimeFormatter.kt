package studio.acmenra.control.navigation.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Форматирует [Instant] в человекочитаемую дату и время вида "DD.MM.YYYY HH:mm"
 * с учётом системного или заданного часового пояса [timeZone].
 */
fun formatInstantHuman(
    instant: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val local = instant.toLocalDateTime(timeZone)
    val day = local.day.toString().padStart(2, '0')
    val month =
        local.month.number
            .toString()
            .padStart(2, '0')
    val year = local.year.toString().padStart(4, '0')
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$day.$month.$year $hour:$minute"
}
