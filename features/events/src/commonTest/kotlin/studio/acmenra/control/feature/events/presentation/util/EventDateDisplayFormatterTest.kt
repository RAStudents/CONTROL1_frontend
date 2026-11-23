package studio.acmenra.control.feature.events.presentation.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class EventDateDisplayFormatterTest {
    private val russianMonthsGenitive =
        listOf(
            "января",
            "февраля",
            "марта",
            "апреля",
            "мая",
            "июня",
            "июля",
            "августа",
            "сентября",
            "октября",
            "ноября",
            "декабря",
        )

    private val todayPatternRu = "Сегодня, %1\$s"
    private val yesterdayPatternRu = "Вчера, %1\$s"
    private val currentYearPatternRu = "%1\$d %2\$s"
    private val otherYearPatternRu = "%1\$d %2\$s %3\$d"

    @Test
    fun testMonthGenitiveStringResource_AllMonthsMapped() {
        val expected =
            listOf(
                Res.string.month_1_genitive,
                Res.string.month_2_genitive,
                Res.string.month_3_genitive,
                Res.string.month_4_genitive,
                Res.string.month_5_genitive,
                Res.string.month_6_genitive,
                Res.string.month_7_genitive,
                Res.string.month_8_genitive,
                Res.string.month_9_genitive,
                Res.string.month_10_genitive,
                Res.string.month_11_genitive,
                Res.string.month_12_genitive,
            )

        for (m in 1..12) {
            assertEquals(expected[m - 1], EventDateDisplayFormatter.monthGenitiveStringResource(m))
        }

        assertFailsWith<IllegalArgumentException> {
            EventDateDisplayFormatter.monthGenitiveStringResource(0)
        }
        assertFailsWith<IllegalArgumentException> {
            EventDateDisplayFormatter.monthGenitiveStringResource(13)
        }
    }

    @Test
    fun testFormatEventDayHeader_TodayAndYesterday() {
        val today = LocalDate(2026, 3, 14)
        val yesterday = LocalDate(2026, 3, 13)

        val headerToday =
            EventDateDisplayFormatter.formatEventDayHeader(
                date = today,
                today = today,
                monthName = "марта",
                todayPattern = todayPatternRu,
                yesterdayPattern = yesterdayPatternRu,
                currentYearPattern = currentYearPatternRu,
                otherYearPattern = otherYearPatternRu,
            )
        assertEquals("Сегодня, 14 марта", headerToday)

        val headerYesterday =
            EventDateDisplayFormatter.formatEventDayHeader(
                date = yesterday,
                today = today,
                monthName = "марта",
                todayPattern = todayPatternRu,
                yesterdayPattern = yesterdayPatternRu,
                currentYearPattern = currentYearPatternRu,
                otherYearPattern = otherYearPatternRu,
            )
        assertEquals("Вчера, 13 марта", headerYesterday)
    }

    @Test
    fun testFormatEventDayHeader_CurrentYearAndOtherYear() {
        val today = LocalDate(2026, 3, 14)

        // Same year, earlier date (not yesterday)
        val currentYearDate = LocalDate(2026, 1, 5)
        val headerCurrentYear =
            EventDateDisplayFormatter.formatEventDayHeader(
                date = currentYearDate,
                today = today,
                monthName = "января",
                todayPattern = todayPatternRu,
                yesterdayPattern = yesterdayPatternRu,
                currentYearPattern = currentYearPatternRu,
                otherYearPattern = otherYearPatternRu,
            )
        assertEquals("5 января", headerCurrentYear)

        // Previous year
        val prevYearDate = LocalDate(2025, 12, 31)
        val headerOtherYear =
            EventDateDisplayFormatter.formatEventDayHeader(
                date = prevYearDate,
                today = today,
                monthName = "декабря",
                todayPattern = todayPatternRu,
                yesterdayPattern = yesterdayPatternRu,
                currentYearPattern = currentYearPatternRu,
                otherYearPattern = otherYearPatternRu,
            )
        assertEquals("31 декабря 2025", headerOtherYear)
    }

    @Test
    fun testFormatEventDayHeader_AllRussianMonthsGenitive() {
        val today = LocalDate(2026, 7, 1)

        for (month in 1..12) {
            val date = LocalDate(2026, month, 10)
            val monthName = russianMonthsGenitive[month - 1]
            val formatted =
                EventDateDisplayFormatter.formatEventDayHeader(
                    date = date,
                    today = today,
                    monthName = monthName,
                    todayPattern = todayPatternRu,
                    yesterdayPattern = yesterdayPatternRu,
                    currentYearPattern = currentYearPatternRu,
                    otherYearPattern = otherYearPatternRu,
                )
            assertEquals("10 $monthName", formatted)
        }
    }

    @Test
    fun testMidnightBoundaryTransitions_InBusinessTimeZone() {
        val businessTz = TimeZone.of("Europe/Moscow") // UTC+3
        // 2026-03-14T20:59:59Z -> 2026-03-14 23:59:59 MSK
        val instantBeforeMidnight = Instant.parse("2026-03-14T20:59:59Z")
        // 2026-03-14T21:00:00Z -> 2026-03-15 00:00:00 MSK
        val instantAtMidnight = Instant.parse("2026-03-14T21:00:00Z")

        val supportingBefore =
            EventDateDisplayFormatter.formatEventCardSupportingText(
                occurredAt = instantBeforeMidnight,
                businessTimeZone = businessTz,
                locationName = "Warehouse A",
                deviceName = "Cam 1",
            )
        assertEquals("23:59 • Warehouse A, Cam 1", supportingBefore)

        val supportingAt =
            EventDateDisplayFormatter.formatEventCardSupportingText(
                occurredAt = instantAtMidnight,
                businessTimeZone = businessTz,
                locationName = "Warehouse A",
                deviceName = "Cam 1",
            )
        assertEquals("00:00 • Warehouse A, Cam 1", supportingAt)
    }

    @Test
    fun testFormatEventCardSupportingText_MetadataCombinationsAndTrailingSeparators() {
        val tz = TimeZone.of("UTC")
        val instant = Instant.parse("2026-03-14T15:45:00Z")

        // 1. Both location and device present
        assertEquals(
            "15:45 • Gate 1, Scanner 2",
            EventDateDisplayFormatter.formatEventCardSupportingText(instant, tz, "Gate 1", "Scanner 2"),
        )

        // 2. Only location present
        assertEquals(
            "15:45 • Gate 1",
            EventDateDisplayFormatter.formatEventCardSupportingText(instant, tz, "Gate 1", null),
        )
        assertEquals(
            "15:45 • Gate 1",
            EventDateDisplayFormatter.formatEventCardSupportingText(instant, tz, "Gate 1", ""),
        )
        assertEquals(
            "15:45 • Gate 1",
            EventDateDisplayFormatter.formatEventCardSupportingText(instant, tz, "Gate 1", "   "),
        )

        // 3. Only device present
        assertEquals(
            "15:45 • Scanner 2",
            EventDateDisplayFormatter.formatEventCardSupportingText(instant, tz, null, "Scanner 2"),
        )
        assertEquals(
            "15:45 • Scanner 2",
            EventDateDisplayFormatter.formatEventCardSupportingText(instant, tz, "", "Scanner 2"),
        )
        assertEquals(
            "15:45 • Scanner 2",
            EventDateDisplayFormatter.formatEventCardSupportingText(instant, tz, "   ", "Scanner 2"),
        )

        // 4. Both null or blank: zero trailing separators
        assertEquals(
            "15:45",
            EventDateDisplayFormatter.formatEventCardSupportingText(instant, tz, null, null),
        )
        assertEquals(
            "15:45",
            EventDateDisplayFormatter.formatEventCardSupportingText(instant, tz, "", ""),
        )
        assertEquals(
            "15:45",
            EventDateDisplayFormatter.formatEventCardSupportingText(instant, tz, "   ", "   "),
        )
    }
}
