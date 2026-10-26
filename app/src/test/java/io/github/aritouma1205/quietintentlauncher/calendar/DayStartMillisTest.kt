package io.github.aritouma1205.quietintentlauncher.calendar

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for [CalendarAccess.dayStartMillis] (Issue #48): the
 * day-view URI carries the device timezone's midnight of the tapped day,
 * so every boundary must collapse to 00:00.000 local.
 */
class DayStartMillisTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun keepZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    /** Local wall time in the *default* zone — the same view the code under test sees. */
    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
        second: Int = 0,
        millis: Int = 0,
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
        set(Calendar.MILLISECOND, millis)
    }.timeInMillis

    @Test
    fun `last millisecond of the day collapses to that days midnight`() {
        val endOfDay = at(2025, 1, 15, 23, 59, 59, 999)
        assertEquals(at(2025, 1, 15, 0), CalendarAccess.dayStartMillis(endOfDay))
    }

    @Test
    fun `first minute of the day collapses to midnight`() {
        val justAfter = at(2025, 1, 15, 0, 1)
        assertEquals(at(2025, 1, 15, 0), CalendarAccess.dayStartMillis(justAfter))
    }

    @Test
    fun `exact midnight stays midnight`() {
        val midnight = at(2025, 1, 15, 0)
        assertEquals(midnight, CalendarAccess.dayStartMillis(midnight))
    }

    @Test
    fun `month-end evening stays inside the ending month`() {
        val endOfMonth = at(2025, 1, 31, 23, 59)
        assertEquals(at(2025, 1, 31, 0), CalendarAccess.dayStartMillis(endOfMonth))
    }

    @Test
    fun `new years eve stays in the old year and new years day in the new`() {
        assertEquals(
            at(2025, 12, 31, 0),
            CalendarAccess.dayStartMillis(at(2025, 12, 31, 23, 59)),
        )
        assertEquals(
            at(2026, 1, 1, 0),
            CalendarAccess.dayStartMillis(at(2026, 1, 1, 0, 0, 0, 30)),
        )
    }

    @Test
    fun `the same instant resolves in whatever zone the device is in`() {
        // 2025-01-15 15:30 UTC — the 16th in Tokyo, still the 15th in New York.
        val instant =
            ZonedDateTime.of(2025, 1, 15, 15, 30, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        assertEquals(
            at(2025, 1, 16, 0),
            CalendarAccess.dayStartMillis(instant),
        )

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals(
            at(2025, 1, 15, 0),
            CalendarAccess.dayStartMillis(instant),
        )
    }
}
