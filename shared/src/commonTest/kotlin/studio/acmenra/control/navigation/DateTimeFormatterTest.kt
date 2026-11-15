package studio.acmenra.control.navigation

import kotlinx.datetime.TimeZone
import studio.acmenra.control.navigation.util.formatInstantHuman
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class DateTimeFormatterTest {
    @Test
    fun formatsIsoInstantToUtcFormat() {
        val instant = Instant.parse("2026-11-20T12:00:00Z")
        assertEquals("20.11.2026 12:00", formatInstantHuman(instant, timeZone = TimeZone.UTC))
    }

    @Test
    fun formatsIsoInstantWithMillisecondsInUtc() {
        val instant = Instant.parse("2026-05-04T08:05:30.123456Z")
        assertEquals("04.05.2026 08:05", formatInstantHuman(instant, timeZone = TimeZone.UTC))
    }

    @Test
    fun formatsEndOfYearInstantInUtc() {
        val instant = Instant.parse("2026-12-31T23:59:59Z")
        assertEquals("31.12.2026 23:59", formatInstantHuman(instant, timeZone = TimeZone.UTC))
    }

    @Test
    fun formatsIsoInstantWithSpecificTimeZoneOffset() {
        val instant = Instant.parse("2026-11-20T12:00:00Z")
        val utcPlus3 = TimeZone.of("UTC+3")
        assertEquals("20.11.2026 15:00", formatInstantHuman(instant, timeZone = utcPlus3))
    }

    @Test
    fun formatsIsoInstantWithNegativeTimeZoneOffset() {
        val instant = Instant.parse("2026-11-20T02:00:00Z")
        val utcMinus5 = TimeZone.of("UTC-5")
        assertEquals("19.11.2026 21:00", formatInstantHuman(instant, timeZone = utcMinus5))
    }

    @Test
    fun formatsIsoInstantWithSystemDefaultMatchesPattern() {
        val instant = Instant.parse("2026-11-20T12:00:00Z")
        val formatted = formatInstantHuman(instant)
        assertTrue(Regex("""^\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}$""").matches(formatted))
    }
}
