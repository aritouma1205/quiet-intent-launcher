package io.github.aritouma1205.quietintentlauncher.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.os.Build
import android.os.SystemClock
import androidx.core.net.toUri
import io.github.aritouma1205.quietintentlauncher.apps.ShortcutCatalog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Shared launch path (design 14). Every screen launches targets through this
 * class so validation, the double-tap gate and error mapping stay consistent.
 */
class TargetLauncher(
    private val context: Context,
    private val gate: LaunchGate = LaunchGate(clock = SystemClock::elapsedRealtime),
    private val isHomeRoleHeld: () -> Boolean = { false },
    private val shortcutCatalog: ShortcutCatalog? = null,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val packageManager = context.packageManager

    private val _recentLaunchEvents = MutableSharedFlow<LaunchRecord>(
        extraBufferCapacity = 16,
    )

    /**
     * Successful launches feed the Recent list (design 9: 「最近」). The
     * persistent Recent store (recent/RecentStore: max 20 entries, 30 days)
     * consumes these events.
     */
    val recentLaunchEvents: SharedFlow<LaunchRecord> =
        _recentLaunchEvents.asSharedFlow()

    fun launch(target: LaunchTarget): LaunchResult = when (target) {
        is LaunchTarget.AppActivity -> launchApp(target)
        is LaunchTarget.AppShortcut -> launchShortcut(target)
        is LaunchTarget.HttpsLink -> launchHttps(target)
    }

    /** True when some installed app can open this https:// link. */
    fun canOpenHttps(url: String): Boolean {
        if (!LinkValidation.isValidHttpsUrl(url)) return false
        return resolveCount(httpsIntent(url)) > 0
    }

    private fun launchApp(target: LaunchTarget.AppActivity): LaunchResult {
        if (!gate.tryAcquire(target.key)) return LaunchResult.Busy
        return try {
            launcherApps.startMainActivity(target.component, target.user, null, null)
            succeeded(target)
        } catch (e: ActivityNotFoundException) {
            failed(target, LaunchResult.NotFound)
        } catch (e: SecurityException) {
            failed(target, LaunchResult.Failure(e.message ?: "security exception"))
        } catch (e: Exception) {
            failed(target, LaunchResult.Failure(e.message ?: "launch failed"))
        }
    }

    private fun launchShortcut(target: LaunchTarget.AppShortcut): LaunchResult {
        if (!gate.tryAcquire(target.key)) return LaunchResult.Busy
        // Public shortcuts are only visible while we hold the HOME role, and
        // the shortcut must still be listed — availability is checked against
        // live OS state, never cached config (design 6, 15).
        if (!isHomeRoleHeld()) {
            return failed(target, LaunchResult.ShortcutUnavailable)
        }
        val available = shortcutCatalog?.isAvailableNow(
            target.packageName,
            target.shortcutId,
        ) == true
        if (!available) {
            return failed(target, LaunchResult.ShortcutUnavailable)
        }
        return try {
            launcherApps.startShortcut(
                target.packageName,
                target.shortcutId,
                null,
                null,
                target.user,
            )
            succeeded(target)
        } catch (e: SecurityException) {
            failed(target, LaunchResult.ShortcutUnavailable)
        } catch (e: Exception) {
            failed(target, LaunchResult.Failure(e.message ?: "launch failed"))
        }
    }

    private fun launchHttps(target: LaunchTarget.HttpsLink): LaunchResult {
        // The stored link was validated at save time; re-check so a corrupt
        // or hand-edited value can never reach an intent.
        if (!LinkValidation.isValidHttpsUrl(target.url)) {
            return LaunchResult.Failure("invalid https url")
        }
        if (!gate.tryAcquire(target.key)) return LaunchResult.Busy
        val intent = httpsIntent(target.url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resolveCount(intent) == 0) {
            return failed(target, LaunchResult.NoHandler)
        }
        return try {
            context.startActivity(intent)
            succeeded(target)
        } catch (e: ActivityNotFoundException) {
            failed(target, LaunchResult.NoHandler)
        } catch (e: SecurityException) {
            failed(target, LaunchResult.Failure(e.message ?: "security exception"))
        } catch (e: Exception) {
            failed(target, LaunchResult.Failure(e.message ?: "launch failed"))
        }
    }

    private fun httpsIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)

    private fun resolveCount(intent: Intent): Int = try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(
                intent,
                ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }.size
    } catch (e: Exception) {
        0
    }

    private fun succeeded(target: LaunchTarget): LaunchResult {
        _recentLaunchEvents.tryEmit(
            LaunchRecord(target.toStoredTarget(), System.currentTimeMillis()),
        )
        return LaunchResult.Success
    }

    private fun failed(target: LaunchTarget, result: LaunchResult): LaunchResult {
        gate.release(target.key)
        return result
    }
}
