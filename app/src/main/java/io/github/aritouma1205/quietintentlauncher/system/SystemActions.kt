package io.github.aritouma1205.quietintentlauncher.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The three fixed operations the optional service can run (design 13). */
enum class SystemAction(val globalAction: Int) {
    Notifications(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    LockScreen(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN),
    TakeScreenshot(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT),
}

/**
 * App-side facade for the optional accessibility service (design 13).
 *
 * - The OS-level "enabled" bit is re-read from Settings.Secure on demand —
 *   never cached beyond a refresh — so revoking the permission while the
 *   launcher is open is seen on the next attempt.
 * - "Connected" means a live service instance registered itself with this
 *   facade; every [perform] re-checks it immediately before calling through.
 * - A revoked, stopped or failed operation only fails that one call: nothing
 *   here touches the rest of the launcher.
 * - The service instance is never exposed outside this class, keeping the
 *   only invocation surface the fixed [SystemAction] set.
 */
class SystemActions(context: Context) {
    private val appContext = context.applicationContext
    private val component = ComponentName(context, QuietSystemService::class.java)

    @Volatile
    private var service: QuietSystemService? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** OS permission state, refreshed by [setObserving] and [refreshEnabled]. */
    private val _enabled = MutableStateFlow(readEnabled(appContext.contentResolver))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = refreshEnabled()
    }
    private var observing = false

    // ---- Test seams -------------------------------------------------------
    // Assigning or clearing a seam invalidates the enabled-bit cache so a
    // test swap can never be masked by a stale TTL entry.
    internal var enabledOverride: (() -> Boolean)? = null
        set(value) {
            field = value
            enabledCacheValid = false
        }
    internal var connectedOverride: (() -> Boolean)? = null
    internal var actionRunner: ((SystemAction) -> Boolean)? = null

    /**
     * Test seam: the clock backing the gesture-path TTL cache, so
     * instrumentation can advance time without sleeping.
     */
    internal var enabledCacheClock: () -> Long = { SystemClock.elapsedRealtime() }
        set(value) {
            field = value
            enabledCacheValid = false
        }

    /**
     * Short-TTL cache for the gesture path (design 15): swipe-down and
     * double-tap handlers call [isEnabledInOs] on the UI thread, so
     * repeated gestures inside the window reuse one Settings.Secure read
     * instead of one binder call per event.
     *
     * [refreshEnabled] and [setObserving] always bypass this cache with a
     * fresh read and re-arm it — a revoke surfaced through the
     * ENABLED_ACCESSIBILITY_SERVICES ContentObserver or the foreground
     * refresh is also the next gesture's answer, so design 13's
     * 実行直前のrevoke検知 is not weakened. An OS-level change while the
     * UI is backgrounded is caught by the setObserving(true) refresh on
     * the next foregrounding.
     */
    @Volatile
    private var enabledCacheValid = false

    @Volatile
    private var enabledCacheValue = false

    @Volatile
    private var enabledCacheAtMs = 0L

    /** Called by [QuietSystemService] on bind; never invoked externally. */
    fun attach(service: QuietSystemService) {
        this.service = service
        _connected.value = true
    }

    /** Called by [QuietSystemService] on unbind/destroy. */
    fun detach(service: QuietSystemService) {
        if (this.service === service) {
            this.service = null
            _connected.value = false
        }
    }

    /**
     * Whether the OS accessibility switch for this service is on now.
     * Gesture-path callers get the short-TTL cached answer; [refreshEnabled]
     * is the always-fresh path.
     */
    fun isEnabledInOs(): Boolean {
        val now = enabledCacheClock()
        val stale = !enabledCacheValid ||
            now - enabledCacheAtMs >= ENABLED_CACHE_TTL_MS
        val value = if (stale) {
            readEnabledNow().also { fresh ->
                enabledCacheValue = fresh
                enabledCacheAtMs = now
                enabledCacheValid = true
            }
        } else {
            enabledCacheValue
        }
        _enabled.value = value
        return value
    }

    /** Whether a live service instance is registered right now. */
    fun isConnected(): Boolean =
        connectedOverride?.invoke() ?: (service != null)

    /**
     * Runs [action] through the connected service. Connection is re-checked
     * immediately before the call (design 13); `false` means the operation
     * did not run and the caller should surface a failure or guidance.
     */
    fun perform(action: SystemAction): Boolean {
        val runner = actionRunner
        return if (runner != null) {
            if (!isConnected()) return false
            runner(action)
        } else {
            service?.performGlobalAction(action.globalAction) == true
        }
    }

    /**
     * Always a fresh Settings.Secure read — the gesture-path cache is
     * bypassed and then re-armed with the result, so a revoke surfaced by
     * the ContentObserver / foreground refresh is seen by the very next
     * gesture (design 13: 実行直前のrevoke検知).
     */
    fun refreshEnabled() {
        val value = readEnabledNow()
        enabledCacheValue = value
        enabledCacheAtMs = enabledCacheClock()
        enabledCacheValid = true
        _enabled.value = value
    }

    private fun readEnabledNow(): Boolean =
        enabledOverride?.invoke() ?: readEnabled(appContext.contentResolver)

    /**
     * Observes the OS enabled-services list while the launcher UI is
     * foregrounded so the settings screen reflects grants/revokes without a
     * manual refresh (design 13, 15).
     *
     * `setObserving(true)` is the foreground boundary: it always performs a
     * fresh read — bypassing and re-arming the gesture-path TTL cache — so
     * a revoke that landed while the UI was backgrounded is visible to the
     * very next gesture, even when the observer was already registered.
     */
    fun setObserving(observe: Boolean) {
        if (observe) {
            refreshEnabled()
            if (observing) return
            observing = true
            try {
                appContext.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ),
                    false,
                    observer,
                )
            } catch (e: RuntimeException) {
                observing = false
            }
        } else {
            if (!observing) return
            observing = false
            try {
                appContext.contentResolver.unregisterContentObserver(observer)
            } catch (e: RuntimeException) {
                // Already unregistered; nothing to propagate.
            }
        }
    }

    /** Intent opening the OS accessibility settings for manual enabling. */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun readEnabled(resolver: ContentResolver): Boolean {
        val accessibilityOn = try {
            Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            )
        } catch (e: RuntimeException) {
            0
        }
        if (accessibilityOn != 1) return false
        val flattened = try {
            Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        } catch (e: RuntimeException) {
            null
        } ?: return false
        return flattened
            .split(':')
            .mapNotNull { entry ->
                entry.takeIf { it.isNotBlank() }
                    ?.let(ComponentName::unflattenFromString)
            }
            .any { it == component }
    }

    companion object {
        /**
         * Gesture-path cache window (design 15): long enough to absorb a
         * gesture burst, short enough that a stale answer is never trusted
         * beyond the moment the observer/refresh paths would land.
         */
        internal const val ENABLED_CACHE_TTL_MS = 2_000L
    }
}
