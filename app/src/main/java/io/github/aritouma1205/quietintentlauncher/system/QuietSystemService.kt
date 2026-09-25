package io.github.aritouma1205.quietintentlauncher.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp

/**
 * Optional accessibility bridge (design 13): the only thing this service
 * does is forward three explicit user-triggered global actions —
 * notification shade, screen lock and OS screenshot.
 *
 * Boundaries, enforced by declaration and implementation alike:
 * - The service config (res/xml/quiet_system_service.xml) requests no event
 *   types and no window-content/gesture capabilities, so nothing about the
 *   screen, keys or notification text is ever delivered to it.
 * - [onAccessibilityEvent] intentionally does nothing — no data is read,
 *   stored or forwarded.
 * - The service is bound only through BIND_ACCESSIBILITY_SERVICE; it exposes
 *   no exported component, no binder API and no Intent entry point, so
 *   external apps cannot invoke it. Reachability is process-internal only:
 *   the service instance registers itself with the app container.
 */
class QuietSystemService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        (application as? QuietLauncherApp)?.container?.systemActions?.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        (application as? QuietLauncherApp)?.container?.systemActions?.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        (application as? QuietLauncherApp)?.container?.systemActions?.detach(this)
        super.onDestroy()
    }
}
