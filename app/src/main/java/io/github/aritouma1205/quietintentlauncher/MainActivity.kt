package io.github.aritouma1205.quietintentlauncher

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import io.github.aritouma1205.quietintentlauncher.home.HomeViewModel
import io.github.aritouma1205.quietintentlauncher.home.QuietLauncherRoot

/**
 * The single HOME activity (design 14). Lifecycle forwarding keeps the
 * navigation state machine honest about home returns vs. settings flows.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as QuietLauncherApp).container

    // internal so instrumentation can observe the broadcast-driven
    // time-change path through the real activity (design 8.1, A08).
    internal val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(container)
    }

    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshHomeRole()
    }

    /**
     * Time/date/timezone changes repaint the clock faces and re-select the
     * calendar rows (design 8.1: 時刻・日付・タイムゾーンの変更に追従).
     * Both members are internal so instrumentation can assert the exact
     * filter registered and drive the receiver — TIME_SET is a protected
     * broadcast that even the shell cannot send on API 30+.
     */
    internal val timeChangeFilter = IntentFilter().apply {
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }

    internal val timeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.onTimeChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuietLauncherRoot(
                viewModel = viewModel,
                iconLoader = container.appCatalog::loadIcon,
                onRequestHomeRole = ::requestHomeRole,
                onRestoreHome = ::openDefaultHomeSettings,
                onChangeWallpaper = ::openWallpaperPicker,
                onOpenAppInfo = ::openAppInfo,
                onUninstallApp = ::openUninstall,
                onOpenEvent = ::openCalendarEvent,
                onOpenAccessibilitySettings = ::openAccessibilitySettings,
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed while we are visible always lands on Quiet (design 3).
        viewModel.onHomeInvoked()
    }

    override fun onStart() {
        super.onStart()
        // Time-change subscriptions live only while started — nothing runs
        // while backgrounded (design 15).
        ContextCompat.registerReceiver(
            this,
            timeChangeReceiver,
            timeChangeFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        viewModel.onForegrounded()
        viewModel.refreshHomeRole()
        // Consistency check for app installs/removals while away (design 15).
        container.appCatalog.reloadAll()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(timeChangeReceiver)
        // A recreation (rotation etc.) keeps the ViewModel and must not
        // collapse the navigation stack; only real backgrounding returns
        // the home UI to Quiet (design 3).
        if (!isChangingConfigurations) viewModel.onBackgrounded()
    }

    private fun requestHomeRole() {
        // A missing RoleManager, an unavailable role or a failed intent
        // build all surface as null — the OS default-home settings are
        // the designed fallback so the button can never crash.
        val intent = container.homeRole.requestIntent()
        if (intent == null) {
            openDefaultHomeSettings()
            return
        }
        try {
            roleRequest.launch(intent)
        } catch (e: ActivityNotFoundException) {
            openDefaultHomeSettings()
        }
    }

    private fun openDefaultHomeSettings() {
        try {
            startActivity(container.homeRole.defaultHomeSettingsIntent())
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openWallpaperPicker() {
        try {
            startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.all_apps_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCalendarEvent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.event_no_handler, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * OS accessibility settings — the only place the optional service can
     * be granted (design 13). The ViewModel already marked this an external
     * flow, so returning lands back on the system settings screen.
     */
    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * Shared guard for the external flows the ViewModel arms via
     * `nav.beginExternalFlow()`. When no activity resolves the intent the
     * armed flag is rolled back — otherwise the next ordinary backgrounding
     * would wrongly preserve the screen as if a flow were still running
     * (design 3). internal so instrumentation can exercise the rollback.
     */
    internal fun launchExternalOrToast(intent: Intent, @StringRes failureTextRes: Int) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            viewModel.nav.cancelExternalFlow()
            Toast.makeText(this, failureTextRes, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo(packageName: String) {
        launchExternalOrToast(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
            R.string.all_apps_launch_failed,
        )
    }

    /**
     * OS uninstall confirmation (design 9.3): ACTION_DELETE on a package:
     * URI. REQUEST_DELETE_PACKAGES must be declared or UninstallerActivity
     * finishes silently. No result callback — LauncherApps.Callback
     * .onPackageRemoved reports the actual removal.
     */
    private fun openUninstall(packageName: String) {
        launchExternalOrToast(
            Intent(
                Intent.ACTION_DELETE,
                Uri.fromParts("package", packageName, null),
            ),
            R.string.all_apps_uninstall_failed,
        )
    }
}
