package studio.acmenra.control.feature.events.presentation

import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import studio.acmenra.control.feature.events.domain.model.DateFilterUtils
import studio.acmenra.control.feature.events.domain.model.DateSelection
import studio.acmenra.control.feature.events.domain.model.EventValidityFilter
import studio.acmenra.control.feature.events.domain.model.EventsFilter
import studio.acmenra.control.feature.events.presentation.util.EventDateDisplayFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class EventsPass1Tests {
    @Test
    fun testCalculateEventsLayoutMode_BreakpointsMatrix() {
        // Section 13.1 Scenarios:
        // 1. 900.dp window with collapsed rail (96.dp) and 16.dp margin each side (~772.dp usable) -> Medium
        assertEquals(
            EventsLayoutMode.Medium,
            calculateEventsLayoutMode(windowWidth = 900.dp, usableContentWidth = 772.dp),
        )

        // 2. 840.dp window with collapsed rail (96.dp) and 16.dp margin each side (~712.dp usable) -> Compact
        assertEquals(
            EventsLayoutMode.Compact,
            calculateEventsLayoutMode(windowWidth = 840.dp, usableContentWidth = 712.dp),
        )

        // 3. 1280.dp window with expanded rail (220.dp) and 16.dp margin each side (~1028.dp usable) -> Wide
        assertEquals(
            EventsLayoutMode.Wide,
            calculateEventsLayoutMode(windowWidth = 1280.dp, usableContentWidth = 1028.dp),
        )

        // 4. 1120.dp window with collapsed rail (96.dp) and 16.dp margin each side (~992.dp usable) -> Wide
        assertEquals(
            EventsLayoutMode.Wide,
            calculateEventsLayoutMode(windowWidth = 1120.dp, usableContentWidth = 992.dp),
        )

        // Rail collapsed (96.dp) vs expanded (220.dp) on the same 900.dp window width:
        // Collapsed (usable 772.dp) -> Medium
        assertEquals(
            EventsLayoutMode.Medium,
            calculateEventsLayoutMode(windowWidth = 900.dp, usableContentWidth = 772.dp),
        )
        // Expanded (usable 648.dp) -> Compact (because usableContentWidth < 760.dp)
        assertEquals(
            EventsLayoutMode.Compact,
            calculateEventsLayoutMode(windowWidth = 900.dp, usableContentWidth = 648.dp),
        )

        // Rail collapsed (96.dp) vs expanded (220.dp) on 1120.dp window width:
        // Collapsed (usable 992.dp) -> Wide
        assertEquals(
            EventsLayoutMode.Wide,
            calculateEventsLayoutMode(windowWidth = 1120.dp, usableContentWidth = 992.dp),
        )
        // If content does not fit minWideUsableWidth (e.g. 780.dp < 800.dp), it falls back to Medium
        assertEquals(
            EventsLayoutMode.Medium,
            calculateEventsLayoutMode(windowWidth = 1120.dp, usableContentWidth = 780.dp),
        )
        // If content does not fit minMediumUsableWidth (e.g. 750.dp < 760.dp), it falls back to Compact
        assertEquals(
            EventsLayoutMode.Compact,
            calculateEventsLayoutMode(windowWidth = 1120.dp, usableContentWidth = 750.dp),
        )

        // Mobile compact widths (< 840.dp)
        assertEquals(
            EventsLayoutMode.Compact,
            calculateEventsLayoutMode(windowWidth = 360.dp, usableContentWidth = 360.dp),
        )
        assertEquals(
            EventsLayoutMode.Compact,
            calculateEventsLayoutMode(windowWidth = 412.dp, usableContentWidth = 412.dp),
        )
        assertEquals(
            EventsLayoutMode.Compact,
            calculateEventsLayoutMode(windowWidth = 760.dp, usableContentWidth = 760.dp),
        )
    }

    @Test
    fun testDateFilterUtils_FormatMskOffset() {
        val instant = Instant.parse("2026-03-01T12:00:00Z")

        // Europe/Moscow -> MSK
        assertEquals(
            "MSK",
            EventDateDisplayFormatter.formatMskOffset(instant, TimeZone.of("Europe/Moscow"), "MSK"),
        )

        // Asia/Yekaterinburg (UTC+5, MSK is UTC+3) -> MSK+2
        assertEquals(
            "MSK+2",
            EventDateDisplayFormatter.formatMskOffset(instant, TimeZone.of("Asia/Yekaterinburg"), "MSK"),
        )

        // Europe/Kaliningrad (UTC+2, MSK is UTC+3) -> MSK-1
        assertEquals(
            "MSK-1",
            EventDateDisplayFormatter.formatMskOffset(instant, TimeZone.of("Europe/Kaliningrad"), "MSK"),
        )

        // Asia/Kolkata (UTC+5:30, MSK is UTC+3) -> MSK+2:30
        assertEquals(
            "MSK+2:30",
            EventDateDisplayFormatter.formatMskOffset(instant, TimeZone.of("Asia/Kolkata"), "MSK"),
        )

        // Custom localized prefix "МСК"
        assertEquals(
            "МСК+2",
            EventDateDisplayFormatter.formatMskOffset(instant, TimeZone.of("Asia/Yekaterinburg"), "МСК"),
        )
    }

    @Test
    fun testEventDateDisplayFormatter_FormatLocalDateAndFilterDisplay() {
        val date = LocalDate(2026, 3, 5)
        assertEquals("05.03.2026", EventDateDisplayFormatter.formatLocalDate(date))

        val tz = TimeZone.of("UTC+3")
        val (singleFrom, singleTo) = DateFilterUtils.dateSelectionToUtcBounds(DateSelection.Single(date), tz)
        assertEquals("05.03.2026", EventDateDisplayFormatter.formatFilterDisplayDate(singleFrom, singleTo, tz))

        val rangeStart = LocalDate(2026, 3, 1)
        val rangeEnd = LocalDate(2026, 3, 7)
        val (rangeFrom, rangeTo) =
            DateFilterUtils.dateSelectionToUtcBounds(DateSelection.Range(rangeStart, rangeEnd), tz)
        assertEquals(
            "01.03.2026 – 07.03.2026",
            EventDateDisplayFormatter.formatFilterDisplayDate(rangeFrom, rangeTo, tz),
        )

        assertNull(EventDateDisplayFormatter.formatFilterDisplayDate(null, null, tz))
    }

    @Test
    fun testDateFilterUtils_UtcBoundsToDateSelection_ContractCases() {
        val tz = TimeZone.of("UTC+3")

        // 1. Single day round-trip: 2026-03-01 to 2026-03-02 local midnight
        val singleSelection = DateSelection.Single(LocalDate(2026, 3, 1))
        val (fromSingle, toSingle) = DateFilterUtils.dateSelectionToUtcBounds(singleSelection, tz)
        val restoredSingle = DateFilterUtils.utcBoundsToDateSelection(fromSingle, toSingle, tz)
        assertTrue(restoredSingle is DateSelection.Single)
        assertEquals(LocalDate(2026, 3, 1), restoredSingle.date)

        // 2. Bounded range round-trip: 2026-03-01 to 2026-03-08 local midnight -> Range(2026-03-01, 2026-03-07)
        val rangeSelection = DateSelection.Range(LocalDate(2026, 3, 1), LocalDate(2026, 3, 7))
        val (fromRange, toRange) = DateFilterUtils.dateSelectionToUtcBounds(rangeSelection, tz)
        val restoredRange = DateFilterUtils.utcBoundsToDateSelection(fromRange, toRange, tz)
        assertTrue(restoredRange is DateSelection.Range)
        assertEquals(LocalDate(2026, 3, 1), restoredRange.startDate)
        assertEquals(LocalDate(2026, 3, 7), restoredRange.endDate)

        // 3. Partial bounds: from is null or to is null -> returns DateSelection.From / Until
        assertEquals(
            DateSelection.From(LocalDate(2026, 3, 1)),
            DateFilterUtils.utcBoundsToDateSelection(fromSingle, null, tz),
        )
        assertEquals(
            DateSelection.Until(LocalDate(2026, 3, 1)),
            DateFilterUtils.utcBoundsToDateSelection(null, toSingle, tz),
        )
        assertEquals(DateSelection.All, DateFilterUtils.utcBoundsToDateSelection(null, null, tz))

        // 4. Inverted or zero-length bounds: to <= from -> throws IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            DateFilterUtils.utcBoundsToDateSelection(toSingle, fromSingle, tz)
        }
        assertFailsWith<IllegalArgumentException> {
            DateFilterUtils.utcBoundsToDateSelection(fromSingle, fromSingle, tz)
        }

        // 5. Non-midnight bounds: from or to does not land exactly on 00:00:00 local time -> throws IllegalArgumentException
        val unalignedFrom = Instant.parse("2026-03-01T12:00:00Z")
        assertFailsWith<IllegalArgumentException> {
            DateFilterUtils.utcBoundsToDateSelection(unalignedFrom, toSingle, tz)
        }

        val unalignedTo = Instant.parse("2026-03-02T12:00:00Z")
        assertFailsWith<IllegalArgumentException> {
            DateFilterUtils.utcBoundsToDateSelection(fromSingle, unalignedTo, tz)
        }
    }

    @Test
    fun testActiveFiltersCount_Dimensions() {
        val base = EventsFilter()
        assertEquals(0, base.activeFiltersCount)

        // 1. searchId: non-blank is 1, blank is 0
        assertEquals(1, base.copy(searchId = "123").activeFiltersCount)
        assertEquals(0, base.copy(searchId = "   ").activeFiltersCount)

        // 2. date: null/null is 0, any bound is 1
        val instant = Instant.parse("2026-03-01T00:00:00Z")
        val instant2 = Instant.parse("2026-03-02T00:00:00Z")
        assertEquals(0, base.copy(dateFrom = null, dateTo = null).activeFiltersCount)
        assertEquals(1, base.copy(dateFrom = instant, dateTo = null).activeFiltersCount)
        assertEquals(1, base.copy(dateFrom = null, dateTo = instant).activeFiltersCount)
        assertEquals(1, base.copy(dateFrom = instant, dateTo = instant2).activeFiltersCount)

        // 3. eventTypeIds: empty is 0, non-empty is 1
        assertEquals(0, base.copy(eventTypeIds = emptySet()).activeFiltersCount)
        assertEquals(1, base.copy(eventTypeIds = setOf("ev1")).activeFiltersCount)
        assertEquals(1, base.copy(eventTypeIds = setOf("ev1", "ev2")).activeFiltersCount)

        // 4. deviceIds: empty is 0, non-empty is 1
        assertEquals(0, base.copy(deviceIds = emptySet()).activeFiltersCount)
        assertEquals(1, base.copy(deviceIds = setOf("d1")).activeFiltersCount)
        assertEquals(1, base.copy(deviceIds = setOf("d1", "d2")).activeFiltersCount)

        // 5. validity: ALL is 0, VALID is 1, ERRONEOUS is 1
        assertEquals(0, base.copy(validity = EventValidityFilter.ALL).activeFiltersCount)
        assertEquals(1, base.copy(validity = EventValidityFilter.VALID).activeFiltersCount)
        assertEquals(1, base.copy(validity = EventValidityFilter.ERRONEOUS).activeFiltersCount)

        // All 5 dimensions active:
        val allActive =
            EventsFilter(
                searchId = "test",
                dateFrom = instant,
                dateTo = instant2,
                eventTypeIds = setOf("t1"),
                deviceIds = setOf("d1"),
                validity = EventValidityFilter.VALID,
            )
        assertEquals(5, allActive.activeFiltersCount)
    }

    @Test
    fun testDateSelectionPresets_Calculations() {
        val today = LocalDate(2026, 3, 10)

        // Preset "Сегодня": today..today
        val singleToday = DateSelection.Single(today)
        assertEquals(today, singleToday.date)

        // Preset "Вчера": yesterday..yesterday
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val singleYesterday = DateSelection.Single(yesterday)
        assertEquals(LocalDate(2026, 3, 9), singleYesterday.date)

        // Preset "Последние 7 дней": today - 6 .. today (7 calendar days inclusive)
        val sevenDaysAgo = today.minus(6, DateTimeUnit.DAY)
        val range7Days = DateSelection.Range(startDate = sevenDaysAgo, endDateInclusive = today)
        assertEquals(LocalDate(2026, 3, 4), range7Days.startDate)
        assertEquals(LocalDate(2026, 3, 10), range7Days.endDate)
    }
}
