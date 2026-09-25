package io.github.aritouma1205.quietintentlauncher.today

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.annotation.StringRes
import io.github.aritouma1205.quietintentlauncher.calendar.TodayEvent
import android.text.format.DateFormat
import java.util.Date

/**
 * Time/date/battery snapshot for TODAY and GLANCE (design 8.1, 8.3).
 * Nullable fields keep unavailable information off the panel entirely —
 * the layout must not leave empty placeholders.
 */
data class TodayInfo(
    val timeText: String,
    val dateText: String,
    val weekdayText: String,
    val batteryPercent: Int?,
    val charging: Boolean,
)

/** Weather block of TODAY/GLANCE (design 8.2); null hides the block. */
data class WeatherBlock(
    val temperatureCelsius: Double,
    @param:StringRes val labelRes: Int,
    /** False in the 60min–6h stale window — TODAY shows the fetch time. */
    val fresh: Boolean,
    val fetchedAtWallMs: Long,
    val regionName: String,
)

/**
 * Everything the TODAY panel and GLANCE render (design 8). Recomputed on
 * the designed refresh points — panel open, foreground return, minute
 * boundary while a clock is visible, time/date/timezone change, weather
 * cache updates and calendar changes.
 */
data class TodayUi(
    val timeText: String = "",
    val dateText: String = "",
    val weekdayText: String = "",
    val weather: WeatherBlock? = null,
    val events: List<TodayEvent> = emptyList(),
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** Wall-clock ms backing this snapshot — the UI derives 「明日」 etc. */
    val nowMs: Long = 0L,
)

/** Reads the current [TodayInfo] from platform sources. */
class TodayDataProvider(private val context: Context) {

    fun current(now: Date = Date()): TodayInfo {
        // DateFormat.getTimeFormat respects the user's 12/24-hour choice.
        val timeText = DateFormat.getTimeFormat(context).format(now)
        val dateText = DateFormat.getDateFormat(context).format(now)
        val weekdayText = DateFormat.format("EEEE", now).toString()
        val battery = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val percent = battery?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) (level * 100) / scale else null
        }
        val charging = battery?.let {
            val status = it.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN,
            )
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
        return TodayInfo(
            timeText = timeText,
            dateText = dateText,
            weekdayText = weekdayText,
            batteryPercent = percent,
            charging = charging,
        )
    }
}
