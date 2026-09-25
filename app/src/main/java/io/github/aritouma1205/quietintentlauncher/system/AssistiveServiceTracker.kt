package io.github.aritouma1205.quietintentlauncher.system

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Cached answer to "is an assistive service OTHER than our own
 * operation-only service enabled" (design 8.3).
 *
 * The GLANCE timer reads this every ~50ms, so the enabled-service list is
 * recomputed only when the OS settings change — the ContentObserver on the
 * two accessibility keys keeps the cache current without a binder call on
 * every tick.
 *
 * A failed query (RuntimeException from the binder call, transient
 * system_server trouble) resolves to the conservative side — true — so a
 * broken read can never dismiss text a screen-reader user is still reading.
 */
internal class AssistiveServiceTracker(
    context: Context,
    private val ownComponent: ComponentName,
) {
    private val manager =
        context.getSystemService(AccessibilityManager::class.java)

    /** Test seam: flattened ids of the OS-enabled accessibility services. */
    internal var enabledServiceIds: (AccessibilityManager) -> List<String> = {
        it.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).map { info -> info.id }
    }

    @Volatile
    private var cached: Boolean = compute()

    /** The current cached answer; never throws. */
    fun active(): Boolean = cached

    /** Recomputes the cache — called by the settings observer. */
    fun refresh() {
        cached = compute()
    }

    private fun compute(): Boolean {
        val am = manager ?: return false
        return runCatching {
            enabledServiceIds(am)
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it != ownComponent }
        }.getOrElse { true }
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
    }

    init {
        val resolver = context.contentResolver
        listOf(
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            Settings.Secure.ACCESSIBILITY_ENABLED,
        ).forEach { key ->
            try {
                resolver.registerContentObserver(
                    Settings.Secure.getUriFor(key),
                    false,
                    observer,
                )
            } catch (e: RuntimeException) {
                // Registration failure leaves the initial value cached;
                // the predicate still answers conservatively.
            }
        }
    }
}
