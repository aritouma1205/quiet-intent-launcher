package io.github.aritouma1205.quietintentlauncher.calendar

/**
 * Which 「次の予定」 section of the TODAY panel an event row belongs to
 * (design 8.1). Display order is the declaration order; the labels
 * (「開催中」「明日」「終日」) are applied by the UI, not the model.
 */
enum class EventSection { Ongoing, Upcoming, AllDay }

/**
 * One selected event row for the TODAY panel (design 8.1). Calendar content
 * is held in memory only — never persisted to disk or logged (design
 * 14.1/14.2). An empty [title] stays empty here; the UI substitutes the
 * fallback label 「予定」. The 「明日」 marker is derived by the caller/UI
 * from [beginMs] and the local date, so this model stays dumb.
 */
data class TodayEvent(
    val eventId: Long,
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val section: EventSection,
)
