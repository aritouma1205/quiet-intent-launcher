package io.github.aritouma1205.quietintentlauncher.home

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** HOME role state and the OS intents around it (design 11.1, 13). */
class HomeRole(context: Context) {
    private val roleManager = context.getSystemService(RoleManager::class.java)

    /**
     * Test seam: instrumentation injects the null/throwing request-intent
     * cases so the caller's fallback is exercised deterministically — on
     * real devices RoleManager is normally present and the role is held.
     */
    internal var requestRoleIntentOverride: (() -> Intent?)? = null

    fun isHeld(): Boolean =
        roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true &&
            roleManager.isRoleHeld(RoleManager.ROLE_HOME)

    /**
     * OS chooser for the HOME role; also lets the user pick another home.
     * Returns null — never throws — when the RoleManager service is
     * absent, the HOME role is unavailable on this device, or the OS
     * call fails; the caller falls back to the default-home settings.
     */
    fun requestIntent(): Intent? =
        try {
            // A set seam is the whole answer — including null — so tests
            // can pin the RoleManager-missing/unavailable cases.
            requestRoleIntentOverride?.let { return it() }
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
            } else {
                null
            }
        } catch (e: RuntimeException) {
            null
        }

    /**
     * "元のホームへ戻す": the OS default-home settings, where another home
     * app can be selected. Falls back to the default-apps list on API < 33.
     */
    fun defaultHomeSettingsIntent(): Intent =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent(Settings.ACTION_HOME_SETTINGS)
        } else {
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
}
