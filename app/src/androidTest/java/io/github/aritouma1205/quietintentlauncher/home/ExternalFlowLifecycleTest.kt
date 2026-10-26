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
import androidx.lifecycle.Lifecycle
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I40 coverage (Issue #40): the external-flow flag lifecycle across the
 * real activity states. Dialog-type OS surfaces (uninstall confirmation,
 * role request) only pause MainActivity, so the armed flag is released
 * from onResume as well — otherwise it stays armed forever and suppresses
 * the next ordinary Quiet reset (design 3).
 */
@RunWith(AndroidJUnit4::class)
class ExternalFlowLifecycleTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val activity get() = rule.activity
    private val scenario get() = rule.activityRule.scenario
    private val app get() =
        instrumentation.targetContext.applicationContext as QuietLauncherApp

    @Before
    fun setUp() {
        runBlocking {
            app.container.settingsStore.state.first { it is SettingsState.Ready }
            app.container.settingsStore.update { it.copy(introCompleted = true) }
        }
    }

    @After
    fun tearDown() {
        // An armed flag must never leak into the other classes sharing
        // this process.
        rule.runOnUiThread { activity.viewModel.nav.onForegrounded() }
    }

    private fun bogusIntent() =
        Intent("io.github.aritouma1205.quietintentlauncher.NO_SUCH_ACTION")

    // I40-T02 (discriminating): returning from a pause-only external
    // surface — the dialog-type case — releases the armed flag through
    // onResume, so the next ordinary backgrounding resets to Quiet.
    // Pre-fix this stays on Settings because nothing cleared the flag.
    @Test
    fun pauseOnlyExternalFlowReleasesFlagOnResume() {
        rule.runOnUiThread {
            activity.viewModel.nav.navigateTo(HomeScreen.Settings)
            activity.viewModel.nav.beginExternalFlow()
        }
        rule.waitForIdle()

        // onPause only — exactly what a dialog-type surface does.
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()

        // The flag is released, so a later ordinary backgrounding
        // returns to Quiet (design 3).
        rule.runOnUiThread { activity.viewModel.nav.onBackgrounded() }
        assertEquals(HomeScreen.Quiet, activity.viewModel.screen.value)
    }

    // I40-T03: a full stop keeps the armed flag for the trip so the
    // launching screen survives; the flag is consumed on the way back and
    // a later backgrounding resets.
    @Test
    fun stoppedExternalFlowKeepsScreenThenConsumesFlag() {
        rule.runOnUiThread {
            activity.viewModel.nav.navigateTo(HomeScreen.Settings)
            activity.viewModel.nav.beginExternalFlow()
        }
        rule.waitForIdle()

        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()

        // The armed flag protected the screen across the trip.
        assertEquals(HomeScreen.Settings, activity.viewModel.screen.value)

        // Consumed on return: the next backgrounding resets to Quiet.
        rule.runOnUiThread { activity.viewModel.nav.onBackgrounded() }
        assertEquals(HomeScreen.Quiet, activity.viewModel.screen.value)
    }

    // I40-T05: a recreation keeps the ViewModel and must not collapse the
    // stack — the screen survives a configuration change (design 3).
    @Test
    fun recreateKeepsTheCurrentScreen() {
        rule.runOnUiThread {
            activity.viewModel.nav.navigateTo(HomeScreen.Settings)
        }
        rule.waitForIdle()

        scenario.recreate()
        rule.waitForIdle()

        assertEquals(HomeScreen.Settings, activity.viewModel.screen.value)
    }

    // I40-T06: the rollback now also covers the wallpaper / default-home /
    // accessibility paths — an intent nobody resolves must disarm the
    // flag on each, including a fallback that also resolves to nothing.
    @Test
    fun unresolvedExternalLaunchesRollBackArmedFlow() {
        assertRollsBackToQuiet { activity.openWallpaperPicker(bogusIntent()) }
        assertRollsBackToQuiet {
            activity.openDefaultHomeSettings(bogusIntent(), bogusIntent())
        }
        assertRollsBackToQuiet {
            activity.openAccessibilitySettings(bogusIntent(), bogusIntent())
        }
        assertRollsBackToQuiet {
            activity.openMediaOutputPanel(bogusIntent(), bogusIntent())
        }
    }

    // I49-T01 (Issue #49): the TODAY 「出力先」 row dispatches exactly
    // Settings.Panel.ACTION_MEDIA_OUTPUT (literal — the SDK constant is
    // @SystemApi, so this test asserts the string itself, not a shared
    // symbol) through the real Activity — the blocking monitor's filter
    // IS the strict action match — and the armed external flow survives
    // the hand-off: TODAY stays open under the OS surface
    // (受入4: 外部フロー復帰でパネル状態が維持される).
    @Test
    fun mediaOutputRowOpensOsPanelAndKeepsTheArm() {
        val monitor = instrumentation.addMonitor(
            IntentFilter("com.android.settings.panel.action.MEDIA_OUTPUT"),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            true,
        )
        try {
            rule.runOnUiThread { activity.viewModel.openTodayPanel() }
            rule.waitUntil(timeoutMillis = 5_000) {
                activity.viewModel.overlay.value == HomeOverlay.Today
            }
            rule.waitForIdle()
            rule.onNodeWithText(activity.getString(R.string.volume_output))
                .performScrollTo()
                .performClick()

            rule.waitUntil(timeoutMillis = 5_000) { monitor.hits >= 1 }

            // The arm that beginExternalFlow set before the request is
            // still set — the rollback in launchExternalOrToast did not
            // run because the intent really reached OS dispatch.
            assertTrue(activity.viewModel.nav.externalFlowArmed)
            // The OS surface is dialog-type: this activity never stopped,
            // so the TODAY overlay is still in place underneath.
            assertEquals(
                HomeOverlay.Today,
                activity.viewModel.overlay.value,
            )
            // Foregrounding consumes the arm exactly once.
            rule.runOnUiThread { activity.viewModel.nav.onForegrounded() }
            assertFalse(activity.viewModel.nav.externalFlowArmed)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    // I49-T02 (Issue #49): a media-output intent nobody resolves rolls the
    // arm back — the fallback covering the same launch is bogus too, so
    // the shared guard disarms and the next backgrounding resets to Quiet.
    @Test
    fun unresolvedMediaOutputLaunchRollsBackArmedFlow() {
        rule.runOnUiThread {
            activity.viewModel.nav.navigateTo(HomeScreen.Settings)
            activity.viewModel.nav.beginExternalFlow()
            activity.openMediaOutputPanel(bogusIntent(), bogusIntent())
        }
        rule.waitForIdle()
        assertFalse(activity.viewModel.nav.externalFlowArmed)

        rule.runOnUiThread { activity.viewModel.nav.onBackgrounded() }
        assertEquals(HomeScreen.Quiet, activity.viewModel.screen.value)
    }

    // I49-T03: the ACTION_SOUND_SETTINGS fallback still counts as a real
    // hand-off — the arm survives while the OS dispatches it (intercepted
    // by the monitor so the app never actually leaves).
    @Test
    fun dispatchedSoundSettingsFallbackKeepsTheArmedFlow() {
        val monitor = instrumentation.addMonitor(
            IntentFilter(Settings.ACTION_SOUND_SETTINGS),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            true,
        )
        try {
            rule.runOnUiThread {
                activity.viewModel.nav.navigateTo(HomeScreen.Settings)
                activity.viewModel.nav.beginExternalFlow()
                // The primary resolves to nothing; the fallback reaches
                // OS dispatch (intercepted above).
                activity.openMediaOutputPanel(bogusIntent())
            }
            rule.waitUntil(timeoutMillis = 5_000) { monitor.hits >= 1 }
            assertTrue(activity.viewModel.nav.externalFlowArmed)

            rule.runOnUiThread { activity.viewModel.nav.onBackgrounded() }
            assertEquals(HomeScreen.Settings, activity.viewModel.screen.value)
            rule.runOnUiThread { activity.viewModel.nav.onForegrounded() }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    // I40-T06 (companion): a dispatched fallback still counts as a real
    // external hand-off — the flag stays armed and the screen survives an
    // ordinary backgrounding. The blocking monitor intercepts the
    // ACTION_SETTINGS fallback so the app never actually leaves.
    @Test
    fun dispatchedFallbackKeepsTheArmedFlow() {
        val monitor = instrumentation.addMonitor(
            IntentFilter(Settings.ACTION_SETTINGS),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            true,
        )
        try {
            rule.runOnUiThread {
                activity.viewModel.nav.navigateTo(HomeScreen.Settings)
                activity.viewModel.nav.beginExternalFlow()
                // The primary resolves to nothing; the ACTION_SETTINGS
                // fallback reaches OS dispatch (intercepted above).
                activity.openDefaultHomeSettings(bogusIntent())
            }
            rule.waitUntil(timeoutMillis = 5_000) { monitor.hits >= 1 }

            rule.runOnUiThread { activity.viewModel.nav.onBackgrounded() }
            assertEquals(HomeScreen.Settings, activity.viewModel.screen.value)
            rule.runOnUiThread { activity.viewModel.nav.onForegrounded() }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun assertRollsBackToQuiet(launch: () -> Unit) {
        rule.runOnUiThread {
            activity.viewModel.nav.navigateTo(HomeScreen.Settings)
            activity.viewModel.nav.beginExternalFlow()
            launch()
        }
        rule.waitForIdle()

        rule.runOnUiThread { activity.viewModel.nav.onBackgrounded() }
        assertEquals(HomeScreen.Quiet, activity.viewModel.screen.value)
    }
}
