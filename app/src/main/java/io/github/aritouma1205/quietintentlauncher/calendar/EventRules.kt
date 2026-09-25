package io.github.aritouma1205.quietintentlauncher.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One raw row of a CalendarContract.Instances query (design 8.1): recurring
 * events arrive already expanded, so [beginMs]/[endMs] are this instance's
 * bounds. The caller restricts the query to user-selected calendars;
 * [calendarId] is kept for traceability but selection never consults it.
 * [deleted], [canceled] (Events.STATUS_CANCELED) and [declined] (self
 * attendee status) are resolved to plain flags by the caller. Instance
 * content stays in memory and is never persisted or logged.
 */
data class RawInstance(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val deleted: Boolean,
    val canceled: Boolean,
    val declined: Boolean,
)

/**
 * Pure selection rules for the TODAY 「次の予定」 block (design 8.1). No
 * Android imports and no clock of its own — [select] receives `now` and the
 * device zone as parameters so every boundary is unit-testable. The result
 * is at most three rows in section order Ongoing → Upcoming → AllDay.
 */
object EventRules {
    /** 24 hours in ms — 「今から24時間以内に開始」 (design 8.1). */
    const val UPCOMING_WINDOW_MS = 24L * 60 * 60_000L

    /** Section ordering key: 「開始時刻、終了時刻、安定したIDの順」 (design 8.1). */
    private val BY_BEGIN_END_ID: Comparator<RawInstance> =
        compareBy({ it.beginMs }, { it.endMs }, { it.eventId })

    /**
     * Selects the TODAY event rows. Deleted, canceled and declined
     * instances are dropped first (「キャンセル済み・参加辞退を除外する」).
     *
     * Timed events: Ongoing when `beginMs <= nowMs < endMs`, Upcoming when
     * `nowMs < beginMs <= nowMs + UPCOMING_WINDOW_MS` and the event has not
     * already ended; anything with `endMs <= nowMs` is dropped entirely.
     *
     * All-day events are never Ongoing or Upcoming. The provider stores
     * their begin/end as UTC midnights, so the logical date range is
     * `[utcDate(beginMs), utcDate(endMs))` taken at face value — never
     * re-shifted into local time (design 8.1 「日付をずらさない」). An
     * all-day event belongs to AllDay iff the local "today" of [zoneId]
     * falls inside that range, so a multi-day event counts on every covered
     * day and an all-day event of tomorrow or later is excluded.
     *
     * One event is kept per section — the earliest by (begin, end,
     * eventId) — and rows are emitted Ongoing, Upcoming, AllDay.
     */
    fun select(
        instances: List<RawInstance>,
        nowMs: Long,
        zoneId: ZoneId,
    ): List<TodayEvent> {
        val eligible = instances.filterNot {
            it.deleted || it.canceled || it.declined
        }
        val localToday = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()

        val ongoing = eligible
            .filter { !it.allDay && it.beginMs <= nowMs && nowMs < it.endMs }
            .minWithOrNull(BY_BEGIN_END_ID)
            ?.toTodayEvent(EventSection.Ongoing)

        val upcoming = eligible
            .filter {
                !it.allDay &&
                    nowMs < it.beginMs &&
                    it.beginMs <= nowMs + UPCOMING_WINDOW_MS &&
                    it.endMs > nowMs
            }
            .minWithOrNull(BY_BEGIN_END_ID)
            ?.toTodayEvent(EventSection.Upcoming)

        val allDay = eligible
            .filter { it.allDay && it.coversLocalDate(localToday) }
            .minWithOrNull(BY_BEGIN_END_ID)
            ?.toTodayEvent(EventSection.AllDay)

        return listOfNotNull(ongoing, upcoming, allDay)
    }

    /**
     * Whether the logical all-day range [utcDate(beginMs), utcDate(endMs))
     * covers [day]. UTC dates are taken directly from the stored UTC-
     * midnight millis, independent of the device zone.
     */
    private fun RawInstance.coversLocalDate(day: LocalDate): Boolean =
        day >= utcDate(beginMs) && day < utcDate(endMs)

    private fun utcDate(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()

    private fun RawInstance.toTodayEvent(section: EventSection) = TodayEvent(
        eventId = eventId,
        title = title,
        beginMs = beginMs,
        endMs = endMs,
        allDay = allDay,
        section = section,
    )
}
