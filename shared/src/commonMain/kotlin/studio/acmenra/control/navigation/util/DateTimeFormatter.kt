package studio.acmenra.control.navigation.util

import kotlin.time.Instant

/**
 * Форматирует [Instant] в человекочитаемую дату и время вида "DD.MM.YYYY HH:mm".
 * При невозможности распарсить ISO-представление возвращает исходную строку [Instant.toString].
 */
fun formatInstantHuman(instant: Instant): String {
    val iso = instant.toString()
    val match = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(iso) ?: return iso
    val (year, month, day, hour, minute) = match.destructured
    return "$day.$month.$year $hour:$minute"
}
