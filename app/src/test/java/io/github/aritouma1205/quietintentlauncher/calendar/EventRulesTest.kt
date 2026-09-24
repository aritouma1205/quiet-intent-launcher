package io.github.aritouma1205.quietintentlauncher.calendar

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [EventRules] (design 8.1): the clock and the device
 * zone are injected, so the 24h window, the local "today" boundary and the
 * UTC-midnight all-day dates are all deterministic.
 */
class EventRulesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    // 2025-01-15 10:00 JST — mid-morning, away from every boundary.
    private val now: Long = local(2025, 1, 15, 10)

    private val hourMs = 60L * 60 * 1000
    private val minuteMs = 60L * 1000

    /** Epoch millis of a local wall-clock time in [zoneId]. */
    private fun local(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
        zoneId: ZoneId = zone,
    ): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zoneId)
            .toInstant().toEpochMilli()

    /** Epoch millis of the UTC midnight starting [date] — how the provider stores all-day begin/end. */
    private fun utcMidnight(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun timed(
        beginMs: Long,
        endMs: Long,
        eventId: Long = 1L,
        title: String = "event",
        deleted: Boolean = false,
        canceled: Boolean = false,
        declined: Boolean = false,
    ) = RawInstance(
        eventId = eventId,
        calendarId = 7L,
        title = title,
        beginMs = beginMs,
        endMs = endMs,
        allDay = false,
        deleted = deleted,
        canceled = canceled,
        declined = declined,
    )

    /** An all-day instance covering the UTC dates [start] (inclusive) to [end] (exclusive). */
    private fun allDay(
        start: LocalDate,
        end: LocalDate,
        eventId: Long = 1L,
        title: String = "all-day",
        deleted: Boolean = false,
        canceled: Boolean = false,
        declined: Boolean = false,
    ) = RawInstance(
        eventId = eventId,
        calendarId = 7L,
        title = title,
        beginMs = utcMidnight(start),
        endMs = utcMidnight(end),
        allDay = true,
        deleted = deleted,
        canceled = canceled,
        declined = declined,
    )

    private fun select(
        instances: List<RawInstance>,
        nowMs: Long = now,
        zoneId: ZoneId = zone,
    ): List<TodayEvent> = EventRules.select(instances, nowMs, zoneId)

    // --- candidates and exclusion ---------------------------------------

    @Test
    fun `empty input returns an empty list`() {
        assertTrue(select(emptyList()).isEmpty())
    }

    @Test
    fun `in-progress timed event is selected as ongoing`() {
        val event = timed(beginMs = now - hourMs, endMs = now + hourMs)
        val row = select(listOf(event)).single()
        assertEquals(EventSection.Ongoing, row.section)
        assertEquals(event.eventId, row.eventId)
        assertFalse(row.allDay)
    }

    @Test
    fun `timed event starting exactly now is ongoing not upcoming`() {
        val event = timed(beginMs = now, endMs = now + hourMs)
        assertEquals(EventSection.Ongoing, select(listOf(event)).single().section)
    }

    @Test
    fun `timed event starting within 24 hours is upcoming`() {
        val event = timed(beginMs = now + hourMs, endMs = now + 2 * hourMs)
        assertEquals(EventSection.Upcoming, select(listOf(event)).single().section)
    }

    @Test
    fun `timed event starting exactly 24 hours from now is still upcoming`() {
        val event = timed(
            beginMs = now + EventRules.UPCOMING_WINDOW_MS,
            endMs = now + EventRules.UPCOMING_WINDOW_MS + hourMs,
        )
        assertEquals(EventSection.Upcoming, select(listOf(event)).single().section)
    }

    @Test
    fun `timed event starting one millisecond past 24 hours is excluded`() {
        val event = timed(
            beginMs = now + EventRules.UPCOMING_WINDOW_MS + 1,
            endMs = now + EventRules.UPCOMING_WINDOW_MS + hourMs,
        )
        assertTrue(select(listOf(event)).isEmpty())
    }

    @Test
    fun `already-ended timed event is dropped`() {
        val event = timed(beginMs = now - 2 * hourMs, endMs = now - hourMs)
        assertTrue(select(listOf(event)).isEmpty())
    }

    @Test
    fun `timed event ending exactly now is dropped`() {
        val event = timed(beginMs = now - hourMs, endMs = now)
        assertTrue(select(listOf(event)).isEmpty())
    }

    @Test
    fun `deleted canceled and declined instances are each dropped`() {
        val result = select(
            listOf(
                timed(now - hourMs, now + hourMs, eventId = 1, deleted = true),
                timed(now - hourMs, now + hourMs, eventId = 2, canceled = true),
                timed(now + hourMs, now + 2 * hourMs, eventId = 3, declined = true),
                allDay(
                    LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 16),
                    eventId = 4, declined = true,
                ),
            ),
        )
        assertTrue(result.isEmpty())
    }

    // --- all-day --------------------------------------------------------

    @Test
    fun `todays all-day event is selected as all-day`() {
        val event = allDay(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 16))
        val row = select(listOf(event)).single()
        assertEquals(EventSection.AllDay, row.section)
        assertTrue(row.allDay)
    }

    @Test
    fun `tomorrows all-day event is excluded`() {
        val event = allDay(LocalDate.of(2025, 1, 16), LocalDate.of(2025, 1, 17))
        assertTrue(select(listOf(event)).isEmpty())
    }

    @Test
    fun `yesterdays all-day event is excluded`() {
        val event = allDay(LocalDate.of(2025, 1, 14), LocalDate.of(2025, 1, 15))
        assertTrue(select(listOf(event)).isEmpty())
    }

    @Test
    fun `all-day event keeps its utc date instead of shifting to local time`() {
        // Stored as 2025-01-15T00:00Z..2025-01-16T00:00Z; the begin instant
        // is 09:00 JST on the 15th, but the logical date stays the UTC date.
        val event = allDay(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 16))
        assertEquals(
            EventSection.AllDay,
            select(listOf(event), nowMs = local(2025, 1, 15, 10)).single().section,
        )
        // Local 2025-01-14 is outside the logical range, even in the evening.
        assertTrue(select(listOf(event), nowMs = local(2025, 1, 14, 23)).isEmpty())
    }

    @Test
    fun `utc midnight on the previous local evening still counts as the utc date`() {
        // America/New_York: 2025-01-15T00:00Z falls on local 2025-01-14
        // 19:00 — the previous evening — yet the event belongs to the 15th.
        val ny = ZoneId.of("America/New_York")
        val event = allDay(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 16))
        assertEquals(
            EventSection.AllDay,
            select(
                listOf(event),
                nowMs = local(2025, 1, 15, 10, zoneId = ny),
                zoneId = ny,
            ).single().section,
        )
        // On local 2025-01-14 evening the UTC-midnight begin has already
        // passed, but the event's logical date is the 15th: not shown.
        assertTrue(
            select(
                listOf(event),
                nowMs = local(2025, 1, 14, 21, zoneId = ny),
                zoneId = ny,
            ).isEmpty(),
        )
    }

    @Test
    fun `multi-day all-day event counts on every covered day`() {
        val event = allDay(LocalDate.of(2025, 1, 14), LocalDate.of(2025, 1, 17))
        assertEquals(1, select(listOf(event), nowMs = local(2025, 1, 14, 12)).size)
        assertEquals(1, select(listOf(event), nowMs = now).size)
        assertEquals(1, select(listOf(event), nowMs = local(2025, 1, 16, 23)).size)
        // The range end is exclusive: the 17th is no longer covered.
        assertTrue(select(listOf(event), nowMs = local(2025, 1, 17, 0)).isEmpty())
    }

    // --- ordering and section limits ------------------------------------

    @Test
    fun `the earliest ongoing event wins by begin`() {
        val later = timed(now - 30 * minuteMs, now + hourMs, eventId = 1)
        val earlier = timed(now - hourMs, now + 30 * minuteMs, eventId = 2)
        assertEquals(2L, select(listOf(later, earlier)).single().eventId)
    }

    @Test
    fun `equal begins break the tie by end then event id`() {
        val longer = timed(now - hourMs, now + 2 * hourMs, eventId = 1)
        val sameEndLargerId = timed(now - hourMs, now + hourMs, eventId = 9)
        val shorter = timed(now - hourMs, now + hourMs, eventId = 2)
        val row = select(listOf(longer, sameEndLargerId, shorter)).single()
        // Shorter end wins over longer; between equal ends the smaller id wins.
        assertEquals(2L, row.eventId)
        assertEquals(now + hourMs, row.endMs)
    }

    @Test
    fun `the earliest upcoming event wins`() {
        val later = timed(now + 2 * hourMs, now + 3 * hourMs, eventId = 1)
        val earlier = timed(now + hourMs, now + 2 * hourMs, eventId = 2)
        assertEquals(2L, select(listOf(later, earlier)).single().eventId)
    }

    @Test
    fun `the earliest all-day event covering today wins`() {
        val later = allDay(
            LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 16), eventId = 1,
        )
        val earlier = allDay(
            LocalDate.of(2025, 1, 14), LocalDate.of(2025, 1, 16), eventId = 2,
        )
        assertEquals(2L, select(listOf(later, earlier)).single().eventId)
    }

    @Test
    fun `at most one row per section in ongoing upcoming all-day order`() {
        val result = select(
            listOf(
                allDay(
                    LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 16),
                    eventId = 30,
                ),
                timed(now + 3 * hourMs, now + 4 * hourMs, eventId = 21),
                timed(now + hourMs, now + 2 * hourMs, eventId = 20),
                timed(now - 30 * minuteMs, now + 90 * minuteMs, eventId = 11),
                timed(now - hourMs, now + hourMs, eventId = 10),
            ),
        )
        assertEquals(3, result.size)
        assertEquals(
            listOf(EventSection.Ongoing, EventSection.Upcoming, EventSection.AllDay),
            result.map { it.section },
        )
        assertEquals(listOf(10L, 20L, 30L), result.map { it.eventId })
    }

    // --- model pass-through ----------------------------------------------

    @Test
    fun `empty title stays empty for the ui fallback label`() {
        val event = timed(now - hourMs, now + hourMs, title = "")
        assertEquals("", select(listOf(event)).single().title)
    }
}
