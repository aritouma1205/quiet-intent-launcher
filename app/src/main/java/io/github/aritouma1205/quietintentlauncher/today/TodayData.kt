package io.github.aritouma1205.quietintentlauncher.today

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import java.util.Date

/**
 * Snapshot for the TODAY panel / GLANCE (design 8). Only device-local data is
 * read here: clock, date and the battery sticky broadcast. Weather and events
 * are optional features of a later stage.
 */
data class TodayInfo(
    val timeText: String,
    val dateText: String,
    val weekdayText: String,
    val batteryPercent: Int?,
    val charging: Boolean?,
)

class TodayDataProvider(private val context: Context) {

    fun current(): TodayInfo {
        val now = Date()
        return TodayInfo(
            // Follows the device's 12/24h setting (design 8.1).
            timeText = DateFormat.getTimeFormat(context).format(now),
            dateText = DateFormat.getDateFormat(context).format(now),
            weekdayText = DateFormat.format("EEEE", now).toString(),
            batteryPercent = batteryPercent(),
            charging = isCharging(),
        )
    }

    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun batteryPercent(): Int? {
        val intent = batteryIntent() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100f / scale).toInt()
    }

    private fun isCharging(): Boolean? {
        val intent = batteryIntent() ?: return null
        return when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
            -> true
            else -> false
        }
    }
}
