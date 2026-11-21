package studio.acmenra.control.feature.events.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DateFilterUtilsTest {
    private val utc = TimeZone.UTC

    @Test
    fun singleDateRoundTripsAsHalfOpenBounds() {
        val selection = DateSelection.Single(LocalDate(2026, 3, 1))

        val bounds = DateFilterUtils.dateSelectionToUtcBounds(selection, utc)
        val restored = DateFilterUtils.utcBoundsToDateSelection(bounds.first, bounds.second, utc)

        assertEquals(Instant.parse("2026-03-01T00:00:00Z"), bounds.first)
        assertEquals(Instant.parse("2026-03-02T00:00:00Z"), bounds.second)
        assertEquals(selection, restored)
    }

    @Test
    fun inclusiveCalendarRangeRoundTripsWithoutAddingAnExtraDay() {
        val selection =
            DateSelection.Range(
                startDate = LocalDate(2026, 3, 1),
                endDateInclusive = LocalDate(2026, 3, 7),
            )

        val bounds = DateFilterUtils.dateSelectionToUtcBounds(selection, utc)
        val restored = DateFilterUtils.utcBoundsToDateSelection(bounds.first, bounds.second, utc)

        assertEquals(Instant.parse("2026-03-01T00:00:00Z"), bounds.first)
        assertEquals(Instant.parse("2026-03-08T00:00:00Z"), bounds.second)
        assertEquals(selection, restored)
    }

    @Test
    fun openEndedBoundsRoundTrip() {
        val from = DateSelection.From(LocalDate(2026, 3, 1))
        val fromBounds = DateFilterUtils.dateSelectionToUtcBounds(from, utc)
        assertEquals(from, DateFilterUtils.utcBoundsToDateSelection(fromBounds.first, fromBounds.second, utc))

        val until = DateSelection.Until(LocalDate(2026, 3, 7))
        val untilBounds = DateFilterUtils.dateSelectionToUtcBounds(until, utc)
        assertEquals(until, DateFilterUtils.utcBoundsToDateSelection(untilBounds.first, untilBounds.second, utc))
    }

    @Test
    fun localDaysRespectDaylightSavingTransitions() {
        val riga = TimeZone.of("Europe/Riga")

        val springBounds =
            DateFilterUtils.dateSelectionToUtcBounds(
                DateSelection.Single(LocalDate(2026, 3, 29)),
                riga,
            )
        val autumnBounds =
            DateFilterUtils.dateSelectionToUtcBounds(
                DateSelection.Single(LocalDate(2026, 10, 25)),
                riga,
            )

        assertEquals(23L * 60L * 60L, (springBounds.second!! - springBounds.first!!).inWholeSeconds)
        assertEquals(25L * 60L * 60L, (autumnBounds.second!! - autumnBounds.first!!).inWholeSeconds)
    }

    @Test
    fun rejectsNonMidnightBoundsInsteadOfSilentlyConvertingToAll() {
        assertFailsWith<IllegalArgumentException> {
            DateFilterUtils.utcBoundsToDateSelection(
                dateFrom = Instant.parse("2026-03-01T12:00:00Z"),
                dateTo = Instant.parse("2026-03-02T00:00:00Z"),
                businessTimeZone = utc,
            )
        }
    }

    @Test
    fun rejectsEmptyOrReversedInterval() {
        assertFailsWith<IllegalArgumentException> {
            DateFilterUtils.utcBoundsToDateSelection(
                dateFrom = Instant.parse("2026-03-02T00:00:00Z"),
                dateTo = Instant.parse("2026-03-02T00:00:00Z"),
                businessTimeZone = utc,
            )
        }
    }

    @Test
    fun activeFilterCountCountsDimensionsAndIgnoresBlankValues() {
        val empty = EventsFilter()
        assertEquals(0, empty.activeFiltersCount)
        assertFalse(empty.hasActiveFilters)

        val filter =
            EventsFilter(
                searchId = "  ",
                eventTypeIds = setOf("", "  "),
                deviceIds = setOf("camera-1", "camera-2", " camera-opaque-id "),
            )
        assertEquals(1, filter.activeFiltersCount)
        assertTrue(filter.hasActiveFilters)
        assertEquals(
            setOf("camera-1", "camera-2", " camera-opaque-id "),
            filter.normalized().deviceIds,
        )
    }
}
