package io.github.aritouma1205.quietintentlauncher.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * Launch bridge for the timer tool (design 7): the launcher opens the
 * system timer list — it never creates or starts a timer itself, so a tap
 * can never silently start a countdown. The intent is checked against the
 * package manager first; without a handler the row offers the fallback of
 * assigning a clock app as the launch target.
 */
class ToolLauncher(private val context: Context) {

    // Test seams: the emulator may or may not ship a timer-list handler.
    internal var timersAvailableCheck: (() -> Boolean)? = null
    internal var timersLauncher: (() -> Boolean)? = null

    fun canShowTimers(): Boolean =
        timersAvailableCheck?.invoke() ?: (
            context.packageManager
                .queryIntentActivities(timerListIntent(), 0)
                .isNotEmpty()
            )

    /** Returns false when no activity accepts the timer-list intent. */
    fun showTimers(): Boolean {
        timersLauncher?.let { return it() }
        return try {
            context.startActivity(
                timerListIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun timerListIntent(): Intent =
        Intent(AlarmClock.ACTION_SHOW_TIMERS)
}
