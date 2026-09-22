package io.github.aritouma1205.quietintentlauncher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.aritouma1205.quietintentlauncher.home.HomeViewModel
import io.github.aritouma1205.quietintentlauncher.home.QuietLauncherRoot

/**
 * The single HOME activity (design 14). Lifecycle forwarding keeps the
 * navigation state machine honest about home returns vs. settings flows.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as QuietLauncherApp).container

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(container)
    }

    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshHomeRole()
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
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed while we are visible always lands on Quiet (design 3).
        viewModel.nav.onHomeInvoked()
    }

    override fun onStart() {
        super.onStart()
        viewModel.nav.onForegrounded()
        viewModel.refreshHomeRole()
        // Consistency check for app installs/removals while away (design 15).
        container.appCatalog.reloadAll()
    }

    override fun onStop() {
        super.onStop()
        viewModel.nav.onBackgrounded()
    }

    private fun requestHomeRole() {
        try {
            roleRequest.launch(container.homeRole.requestIntent())
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

    private fun openAppInfo(packageName: String) {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.all_apps_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
