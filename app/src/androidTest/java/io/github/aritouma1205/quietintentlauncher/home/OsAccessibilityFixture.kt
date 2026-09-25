package io.github.aritouma1205.quietintentlauncher.home

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Runs a shell command through UiAutomation (shell uid). Shell is allowed
 * to write Settings.Secure and to send protected broadcasts — the two
 * powers these tests need to exercise the real OS paths.
 */
internal fun shellExec(command: String): String =
    InstrumentationRegistry.getInstrumentation()
        .uiAutomation.executeShellCommand(command).use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use {
                it.bufferedReader().readText().trim()
            }
        }

/**
 * Enables/disables ONLY our own QuietSystemService in the real OS
 * accessibility settings, keeping the previous values for restore
 * (review I7 fix: the own service must not count as an external
 * assistive service, so the tests verify the real predicate against a
 * genuinely enabled service instead of a stubbed flag).
 *
 * Note: while ANY UiAutomation is connected without
 * FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, the OS suppresses binding of
 * user accessibility services — and Compose's input injection keeps the
 * default UiAutomation connected for the whole session. An enabled service
 * therefore stays enabled-but-unbound under instrumentation; tests must
 * assert [SystemActions.isEnabledInOs] (the real settings path) rather
 * than waiting for a live connection.
 */
internal class OsQuietServiceFixture(private val packageName: String) {

    private val component = "$packageName/.system.QuietSystemService"
    private var savedServices: String? = null
    private var savedEnabled: String? = null

    fun enable() {
        // Sideloaded (instrumented) apps are blocked from accessibility on
        // API 33+ until the restricted-settings app op is lifted — without
        // this the services write below is silently reverted and the
        // service never binds.
        shellExec("appops set $packageName ACCESS_RESTRICTED_SETTINGS allow")
        savedServices = secureGet(SettingsKey.ENABLED_SERVICES)
        savedEnabled = secureGet(SettingsKey.ACCESSIBILITY_ENABLED)
        shellExec("settings put secure ${SettingsKey.ENABLED_SERVICES} $component")
        shellExec("settings put secure ${SettingsKey.ACCESSIBILITY_ENABLED} 1")
    }

    fun restore() {
        val services = savedServices ?: return
        // A blank saved list must delete the key: `settings put` without a
        // value argument is a shell usage error, which would silently leave
        // our service enabled for the rest of the run.
        if (services.isBlank()) {
            shellExec("settings delete secure ${SettingsKey.ENABLED_SERVICES}")
        } else {
            shellExec("settings put secure ${SettingsKey.ENABLED_SERVICES} $services")
        }
        shellExec("appops set $packageName ACCESS_RESTRICTED_SETTINGS default")
        // accessibility_enabled is deliberately left as is: writing 0
        // disconnects the UiAutomation connection every later test relies
        // on for executeShellCommand. With our component removed from the
        // enabled list, the flag alone satisfies no readiness check.
        savedServices = null
        savedEnabled = null
    }

    private fun secureGet(key: String): String =
        shellExec("settings get secure $key").let {
            if (it == "null") "" else it
        }

    private object SettingsKey {
        const val ENABLED_SERVICES = "enabled_accessibility_services"
        const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
    }
}
