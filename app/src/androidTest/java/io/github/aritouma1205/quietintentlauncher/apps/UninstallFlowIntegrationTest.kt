package io.github.aritouma1205.quietintentlauncher.apps

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PatternMatcher
import android.os.Process
import android.util.Log
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.MainActivity
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.home.HomeScreen
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I38-T16/T17/T18/T30 end-to-end coverage (Issue #38): the real
 * MainActivity wiring — ACTION_DELETE intent for the long-press dialog,
 * the REQUEST_DELETE_PACKAGES declaration, and the external-flow flag
 * lifecycle (arm → OS confirmation → rollback on failure).
 *
 * The fixture package is deliberately NOT a real package: the in-app
 * dialog is verified with a blocked monitor (T16) and the OS confirmation
 * is exercised once without blocking (T30), where the return-side refresh
 * drops the fixture and closes the dialog.
 */
@RunWith(AndroidJUnit4::class)
class UninstallFlowIntegrationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val activity get() = rule.activity
    private val app get() =
        instrumentation.targetContext.applicationContext as QuietLauncherApp

    private fun res(id: Int): String = activity.getString(id)

    private fun fixtureEntry() = AppEntry(
        component = ComponentName(FIXTURE_PKG, "$FIXTURE_PKG.Main"),
        user = Process.myUserHandle(),
        label = "Fixture App",
        category = AppCategory.Other,
        canUninstall = true,
    )

    @Before
    fun setUp() {
        runBlocking {
            app.container.settingsStore.state.first { it is SettingsState.Ready }
            app.container.settingsStore.update { it.copy(introCompleted = true) }
        }
    }

    @After
    fun tearDown() {
        // The fixture-seeded catalog and any armed external-flow flag must
        // not leak into other classes sharing the process.
        rule.runOnUiThread { activity.viewModel.nav.onForegrounded() }
        app.container.appCatalog.reloadAll()
    }

    private fun deleteMonitor(block: Boolean) = instrumentation.addMonitor(
        IntentFilter(Intent.ACTION_DELETE).apply {
            addDataScheme("package")
            addDataSchemeSpecificPart(FIXTURE_PKG, PatternMatcher.PATTERN_LITERAL)
        },
        if (block) Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) else null,
        block,
    )

    private fun openDialogForFixture() {
        app.container.appCatalog.replaceAppsForTest(listOf(fixtureEntry()))
        rule.runOnUiThread {
            activity.viewModel.nav.navigateTo(HomeScreen.AllApps)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Fixture App").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText(FIXTURE_PKG).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.all_apps_uninstall)).assertIsDisplayed()
    }

    // I38-T16: the 「アンインストール」 row fires ACTION_DELETE with a
    // package: URI through the real MainActivity. The monitor blocks the
    // hand-off, so the screen, dialog and armed flag must stay.
    @Test
    fun uninstallRowFiresActionDeleteIntent() {
        val monitor = deleteMonitor(block = true)
        try {
            openDialogForFixture()

            rule.onNodeWithText(res(R.string.all_apps_uninstall)).performClick()

            // waitForActivityWithTimeout only tracks actually-created
            // activities; a blocking monitor never creates one and always
            // reports null. The hit counter is the reliable signal here —
            // it increments when the launch reaches the OS dispatch point
            // and matches this filter (action + package: URI).
            rule.waitUntil(timeoutMillis = 5_000) { monitor.hits >= 1 }
            assertEquals(1, monitor.hits)

            // Blocked hand-off: everything in-app is unchanged.
            assertEquals(HomeScreen.AllApps, activity.viewModel.screen.value)
            rule.onNodeWithText(FIXTURE_PKG).assertIsDisplayed()
            // The flow stays armed — an ordinary backgrounding keeps the
            // screen exactly as the design requires (design 3).
            rule.runOnUiThread { activity.viewModel.nav.onBackgrounded() }
            assertEquals(HomeScreen.AllApps, activity.viewModel.screen.value)
            rule.runOnUiThread { activity.viewModel.nav.onForegrounded() }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    // I38-T17: the manifest declares REQUEST_DELETE_PACKAGES — without it
    // UninstallerActivity finishes silently (spec v2).
    @Test
    fun manifestDeclaresRequestDeletePackages() {
        val info = activity.packageManager.getPackageInfo(
            activity.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        assertTrue(
            "REQUEST_DELETE_PACKAGES not in <uses-permission>",
            info.requestedPermissions?.contains(
                android.Manifest.permission.REQUEST_DELETE_PACKAGES,
            ) == true,
        )
    }

    // I38-T18: an intent nobody resolves must roll the armed external flow
    // back — otherwise the next ordinary backgrounding would preserve a
    // screen that launched nothing.
    @Test
    fun failedExternalLaunchRollsBackArmedFlow() {
        rule.runOnUiThread {
            activity.viewModel.nav.navigateTo(HomeScreen.AllApps)
            activity.viewModel.nav.beginExternalFlow()
            activity.launchExternalOrToast(
                Intent("io.github.aritouma1205.quietintentlauncher.NO_SUCH_ACTION"),
                R.string.all_apps_uninstall_failed,
            )
        }
        rule.waitForIdle()

        rule.runOnUiThread { activity.viewModel.nav.onBackgrounded() }
        assertEquals(HomeScreen.Quiet, activity.viewModel.screen.value)
        rule.runOnUiThread { activity.viewModel.nav.onForegrounded() }
    }

    // I38-T30 (L3): let the OS confirmation actually launch for the
    // fixture package, close it with BACK, and verify the return side —
    // All Apps kept by the armed flow, dialog closed by the catalog
    // refresh that no longer contains the fixture package.
    @Test
    fun osUninstallConfirmationReturnKeepsAllApps() {
        val monitor = deleteMonitor(block = false)
        try {
            openDialogForFixture()

            rule.onNodeWithText(res(R.string.all_apps_uninstall)).performClick()

            rule.waitUntil(timeoutMillis = 5_000) { monitor.hits >= 1 }
            instrumentation.waitForIdleSync()

            // If the OS surface already finished (nonexistent package),
            // our activity is resumed again and no BACK is needed; sending
            // one anyway would pop All Apps via the in-app BackHandler.
            if (rule.activityRule.scenario.state != Lifecycle.State.RESUMED) {
                Log.i(TAG, "OS confirmation visible — dismissing with BACK")
                instrumentation.uiAutomation
                    .executeShellCommand("input keyevent KEYCODE_BACK")
                    .close()
            } else {
                Log.i(TAG, "OS confirmation finished before BACK — recorded")
            }
            rule.waitUntil(timeoutMillis = 10_000) {
                rule.activityRule.scenario.state == Lifecycle.State.RESUMED
            }

            // Same consistency refresh onStart runs after a full leave —
            // harmless if it already ran on its own.
            app.container.appCatalog.reloadAll()
            rule.waitUntil(timeoutMillis = 10_000) {
                app.container.appCatalog.apps.value
                    ?.none { it.packageName == FIXTURE_PKG } == true
            }
            rule.waitForIdle()

            assertEquals(HomeScreen.AllApps, activity.viewModel.screen.value)
            rule.onAllNodesWithText(FIXTURE_PKG).assertCountEquals(0)
            rule.onAllNodesWithText(res(R.string.all_apps_uninstall))
                .assertCountEquals(0)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private companion object {
        const val TAG = "UninstallFlowInteg"
        const val FIXTURE_PKG = "pkg.uninstall.fixture"
    }
}
