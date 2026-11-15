package studio.acmenra.control.navigation

import studio.acmenra.control.navigation.util.formatInstantHuman
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DateTimeFormatterTest {
    @Test
    fun formatsIsoInstantToHumanReadableFormat() {
        val instant = Instant.parse("2026-11-20T12:00:00Z")
        assertEquals("20.11.2026 12:00", formatInstantHuman(instant))
    }

    @Test
    fun formatsIsoInstantWithMilliseconds() {
        val instant = Instant.parse("2026-05-04T08:05:30.123456Z")
        assertEquals("04.05.2026 08:05", formatInstantHuman(instant))
    }

    @Test
    fun formatsEndOfYearInstant() {
        val instant = Instant.parse("2026-12-31T23:59:59Z")
        assertEquals("31.12.2026 23:59", formatInstantHuman(instant))
    }
}
