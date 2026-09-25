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
    internal var enabledOverride: (() -> Boolean)? = null
    internal var connectedOverride: (() -> Boolean)? = null
    internal var actionRunner: ((SystemAction) -> Boolean)? = null

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

    /** Whether the OS accessibility switch for this service is on now. */
    fun isEnabledInOs(): Boolean {
        val value = enabledOverride?.invoke()
            ?: readEnabled(appContext.contentResolver)
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

    fun refreshEnabled() {
        _enabled.value = enabledOverride?.invoke()
            ?: readEnabled(appContext.contentResolver)
    }

    /**
     * Observes the OS enabled-services list while the launcher UI is
     * foregrounded so the settings screen reflects grants/revokes without a
     * manual refresh (design 13, 15).
     */
    fun setObserving(observe: Boolean) {
        if (observe == observing) return
        observing = observe
        val resolver = appContext.contentResolver
        if (observe) {
            refreshEnabled()
            try {
                resolver.registerContentObserver(
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
            try {
                resolver.unregisterContentObserver(observer)
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
}
