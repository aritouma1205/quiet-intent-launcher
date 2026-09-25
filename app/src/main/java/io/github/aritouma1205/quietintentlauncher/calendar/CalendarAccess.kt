package io.github.aritouma1205.quietintentlauncher.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId

/**
 * Read-only bridge to the Calendar Provider (design 8.1, 13).
 *
 * - Only runs while the user opted in and READ_CALENDAR is granted.
 * - Instances expands recurring events provider-side; titles and times
 *   stay in memory — nothing is persisted or logged.
 * - Query methods throw [SecurityException] when the permission is gone
 *   (revoked mid-session); callers drop in-memory events on that path.
 */
class CalendarAccess(private val context: Context) {

    /**
     * Test seam: instrumentation stubs the OS answer so grant / denial /
     * mid-session revocation paths stay deterministic — a real
     * revokeRuntimePermission would kill the process under test.
     */
    internal var permissionCheck: (() -> Boolean)? = null

    /**
     * Test seam: instrumentation supplies instances so selection rules
     * run against deterministic rows — the emulator's Calendar Provider
     * has no writable fixture.
     */
    internal var instanceSource:
        ((Long, ZoneId, Set<Long>) -> List<RawInstance>)? = null

    fun hasPermission(): Boolean = permissionCheck?.invoke() ?: (
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        )

    /** One selectable calendar in the settings picker. */
    data class CalendarInfo(
        val id: Long,
        val displayName: String,
        val accountName: String,
    )

    /**
     * User-visible calendars for the picker. Provider failures surface as
     * an empty list — the picker then explains there is nothing to pick.
     */
    fun listCalendars(): List<CalendarInfo> {
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CALENDAR_PROJECTION,
            "${CalendarContract.Calendars.VISIBLE} != 0",
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        ) ?: return emptyList()
        cursor.use {
            val idCol = it.getColumnIndex(CalendarContract.Calendars._ID)
            val nameCol = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountCol = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            if (idCol < 0 || nameCol < 0) return emptyList()
            val out = ArrayList<CalendarInfo>(it.count)
            while (it.moveToNext()) {
                out += CalendarInfo(
                    id = it.getLong(idCol),
                    displayName = it.getString(nameCol).orEmpty().ifBlank {
                        it.getString(accountCol)?.takeIf { a -> a.isNotBlank() } ?: ""
                    },
                    accountName = if (accountCol >= 0) it.getString(accountCol).orEmpty() else "",
                )
            }
            return out
        }
    }

    /**
     * Raw instances in the window covering today's all-day events and the
     * next 24 hours (design 8.1). The window starts a day before local
     * midnight so UTC-midnight all-day instances are never missed by the
     * zone offset, and ends a day past the upcoming window.
     */
    fun queryInstances(
        nowMs: Long,
        zoneId: ZoneId,
        calendarIds: Set<Long>,
    ): List<RawInstance> {
        if (calendarIds.isEmpty()) return emptyList()
        instanceSource?.let { return it(nowMs, zoneId, calendarIds) }
        val todayStartMs = Instant.ofEpochMilli(nowMs)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val begin = todayStartMs - DAY_MS
        val end = nowMs + 2 * DAY_MS

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, begin) }
            .also { ContentUris.appendId(it, end) }
            .build()
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN " +
            "(${calendarIds.joinToString(",")})"

        val cursor = context.contentResolver.query(
            uri,
            INSTANCE_PROJECTION,
            selection,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        ) ?: return emptyList()
        cursor.use {
            fun col(name: String) = it.getColumnIndex(name)
            val idCol = col(CalendarContract.Instances.EVENT_ID)
            val calCol = col(CalendarContract.Instances.CALENDAR_ID)
            val titleCol = col(CalendarContract.Instances.TITLE)
            val beginCol = col(CalendarContract.Instances.BEGIN)
            val endCol = col(CalendarContract.Instances.END)
            val allDayCol = col(CalendarContract.Instances.ALL_DAY)
            // Instances never expands deleted events, so there is no
            // deleted column to read; RawInstance keeps the flag for the
            // selection rules.
            val statusCol = col(CalendarContract.Events.STATUS)
            val selfCol = col(CalendarContract.Attendees.SELF_ATTENDEE_STATUS)
            if (idCol < 0 || calCol < 0 || beginCol < 0 || endCol < 0) {
                return emptyList()
            }
            val out = ArrayList<RawInstance>(it.count)
            while (it.moveToNext()) {
                out += RawInstance(
                    eventId = it.getLong(idCol),
                    calendarId = it.getLong(calCol),
                    title = if (titleCol >= 0) it.getString(titleCol).orEmpty() else "",
                    beginMs = it.getLong(beginCol),
                    endMs = it.getLong(endCol),
                    allDay = allDayCol >= 0 && it.getInt(allDayCol) != 0,
                    deleted = false,
                    canceled = statusCol >= 0 &&
                        it.getInt(statusCol) == CalendarContract.Events.STATUS_CANCELED,
                    declined = selfCol >= 0 &&
                        it.getInt(selfCol) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                )
            }
            return out
        }
    }

    /**
     * VIEW intent opening the event in a calendar app (design 8.1). The
     * caller checks [canOpenEvent] first; with no handler the row stays
     * visible and the UI explains the limitation.
     */
    fun viewEventIntent(event: TodayEvent): Intent = Intent(Intent.ACTION_VIEW)
        .setData(
            ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI,
                event.eventId,
            ),
        )
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMs)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMs)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun canOpenEvent(intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    /**
     * Watches event changes while TODAY is open (design 8.1). Returns the
     * unregister function; no-op when the permission is absent.
     */
    fun observeChanges(onChanged: () -> Unit): () -> Unit {
        if (!hasPermission()) return {}
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChanged()
        }
        return try {
            context.contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI,
                true,
                observer,
            )
            val unregister: () -> Unit = {
                context.contentResolver.unregisterContentObserver(observer)
            }
            unregister
        } catch (e: SecurityException) {
            {}
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60_000L

        private val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )

        private val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Events.STATUS,
            CalendarContract.Attendees.SELF_ATTENDEE_STATUS,
        )
    }
}
