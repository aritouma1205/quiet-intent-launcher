package io.github.aritouma1205.quietintentlauncher.home

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.MainActivity
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
