package io.github.aritouma1205.quietintentlauncher.home

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.MainActivity
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I43-T05/T06 (design 11.1): 「ホームアプリに設定する」 must never crash.
 * When the RoleManager path cannot produce a chooser — service missing,
 * role unavailable, or the OS call throwing — the button falls back to
 * the OS default-home settings instead.
 *
 * The seam injects the degenerate cases deterministically; the rest of
 * the chain (button → MainActivity.requestHomeRole → fallback intent) is
 * the real code path.
 */
@RunWith(AndroidJUnit4::class)
class HomeRoleFallbackTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() =
        instrumentation.targetContext.applicationContext as QuietLauncherApp

    private fun res(id: Int): String = rule.activity.getString(id)

    @Before
    fun setUp() {
        runBlocking {
            app.container.settingsStore.state.first { it is SettingsState.Ready }
            app.container.settingsStore.update { it.copy(introCompleted = true) }
        }
    }

    @After
    fun tearDown() {
        app.container.homeRole.requestRoleIntentOverride = null
    }

    /**
     * Intercepts every settings-style intent the fallback can produce:
     * ACTION_HOME_SETTINGS (API 33+), the manage-default-apps fallback on
     * older APIs, and the last-resort ACTION_SETTINGS.
     */
    private fun settingsMonitor(): Instrumentation.ActivityMonitor =
        Instrumentation.ActivityMonitor(
            IntentFilter().apply {
                addAction(Settings.ACTION_HOME_SETTINGS)
                addAction(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                addAction(Settings.ACTION_SETTINGS)
            },
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent()),
            true, // block: intercept without opening the real Settings app
        )

    private fun openSettingsScreen() {
        val viewModel = rule.activity.viewModel
        rule.runOnUiThread {
            viewModel.nav.navigateTo(HomeScreen.Settings)
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.screen.value == HomeScreen.Settings
        }
        rule.waitForIdle()
    }

    private fun tapSetHome(): Instrumentation.ActivityMonitor {
        // The button only exists while we are not the default home — on
        // the emulator under test we never are.
        val monitor = settingsMonitor()
        instrumentation.addMonitor(monitor)
        try {
            rule.onNodeWithText(res(R.string.settings_set_home))
                .performScrollTo()
                .performClick()
            rule.waitUntil(timeoutMillis = 5_000) { monitor.hits >= 1 }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        return monitor
    }

    @Test
    fun nullRoleRequestIntentFallsBackToHomeSettings() {
        app.container.homeRole.requestRoleIntentOverride = { null }
        // The seam itself is exercised: requestIntent swallows nothing
        // silently — null is its documented answer for the degenerate OS
        // states, and isHeld must not trip over a missing manager either.
        assertNull(app.container.homeRole.requestIntent())

        openSettingsScreen()
        val monitor = tapSetHome()

        assertEquals(1, monitor.hits)
        assertEquals(HomeScreen.Settings, rule.activity.viewModel.screen.value)
    }

    @Test
    fun throwingRoleRequestIntentFallsBackToHomeSettings() {
        app.container.homeRole.requestRoleIntentOverride = {
            throw RuntimeException("RoleManager dead")
        }
        assertNull(app.container.homeRole.requestIntent())

        openSettingsScreen()
        val monitor = tapSetHome()

        assertEquals(1, monitor.hits)
        assertEquals(HomeScreen.Settings, rule.activity.viewModel.screen.value)
    }

    @Test
    fun unresolvableRoleRequestIntentFallsBackToHomeSettings() {
        // The chooser intent builds fine but nothing on the device can
        // resolve it — launch() throws ActivityNotFoundException and the
        // default-home settings take over.
        app.container.homeRole.requestRoleIntentOverride = {
            Intent("io.github.aritouma1205.NO_SUCH_ROLE_REQUEST")
        }

        openSettingsScreen()
        val monitor = tapSetHome()

        assertEquals(1, monitor.hits)
        assertEquals(HomeScreen.Settings, rule.activity.viewModel.screen.value)
        assertTrue(
            "no crash: activity still alive on the settings screen",
            !rule.activity.isFinishing && !rule.activity.isDestroyed,
        )
    }
}
