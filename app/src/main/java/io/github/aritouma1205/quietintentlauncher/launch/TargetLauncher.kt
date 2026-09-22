package io.github.aritouma1205.quietintentlauncher.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.LauncherApps
import android.os.SystemClock

/**
 * Shared launch path (design 14). Every screen launches targets through this
 * class so validation, the double-tap gate and error mapping stay consistent.
 */
class TargetLauncher(
    context: Context,
    private val gate: LaunchGate = LaunchGate(clock = SystemClock::elapsedRealtime),
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    fun launch(target: LaunchTarget): LaunchResult = when (target) {
        is LaunchTarget.AppActivity -> launchApp(target)
    }

    private fun launchApp(target: LaunchTarget.AppActivity): LaunchResult {
        if (!gate.tryAcquire(target.key)) return LaunchResult.Busy
        return try {
            launcherApps.startMainActivity(target.component, target.user, null, null)
            LaunchResult.Success
        } catch (e: ActivityNotFoundException) {
            gate.release(target.key)
            LaunchResult.NotFound
        } catch (e: SecurityException) {
            gate.release(target.key)
            LaunchResult.Failure(e.message ?: "security exception")
        } catch (e: Exception) {
            gate.release(target.key)
            LaunchResult.Failure(e.message ?: "launch failed")
        }
    }
}
