package studio.acmenra.control.navigation.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Форматирует [Instant] в человекочитаемую дату и время вида "DD.MM.YYYY HH:mm"
 * с учётом системного или заданного часового пояса [timeZone].
 * При ошибке конвертации возвращает безопасный fallback по ISO-представлению.
 */
fun formatInstantHuman(
    instant: Instant,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String =
    try {
        val local = instant.toLocalDateTime(timeZone)
        val day = local.day.toString().padStart(2, '0')
        val month =
            local.month.number
                .toString()
                .padStart(2, '0')
        val year = local.year.toString().padStart(4, '0')
        val hour = local.hour.toString().padStart(2, '0')
        val minute = local.minute.toString().padStart(2, '0')
        "$day.$month.$year $hour:$minute"
    } catch (_: Exception) {
        val iso = instant.toString()
        val match = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(iso)
        if (match != null) {
            val (year, month, day, hour, minute) = match.destructured
            "$day.$month.$year $hour:$minute"
        } else {
            iso
        }
    }
