package io.github.aritouma1205.quietintentlauncher.home

import android.app.Activity
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.apps.AllAppsScreen
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.intro.IntroScreen
import io.github.aritouma1205.quietintentlauncher.search.SearchScreen
import io.github.aritouma1205.quietintentlauncher.settings.SettingsScreen
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import io.github.aritouma1205.quietintentlauncher.ui.QuietBadgeBackground
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun QuietLauncherRoot(
    viewModel: HomeViewModel,
    iconLoader: (AppEntry) -> Drawable?,
    onRequestHomeRole: () -> Unit,
    onRestoreHome: () -> Unit,
    onChangeWallpaper: () -> Unit,
    onOpenAppInfo: (String) -> Unit,
) {
    QuietLauncherTheme {
        val screen by viewModel.screen.collectAsState()
        val settingsState by viewModel.settingsState.collectAsState()
        val isDefaultHome by viewModel.isDefaultHome.collectAsState()
        val recoveryDismissed by viewModel.recoveryDismissed.collectAsState()
        val context = LocalContext.current
        val view = LocalView.current

        LaunchedEffect(screen) {
            val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            if (screen == HomeScreen.Quiet) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }

        LaunchedEffect(Unit) {
            viewModel.messages.collect { message ->
                val text = when (message) {
                    HomeMessage.LaunchFailed -> R.string.all_apps_launch_failed
                    HomeMessage.LaunchBusy -> R.string.all_apps_duplicate
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }

        val showRecovery = settingsState is SettingsState.Degraded && !recoveryDismissed
        var resetConfirm by remember { mutableStateOf(false) }
        BackHandler(enabled = showRecovery || screen != HomeScreen.Quiet) {
            if (showRecovery) viewModel.dismissRecovery() else viewModel.nav.back()
        }

        Box(Modifier.fillMaxSize()) {
            when {
                showRecovery -> RecoveryScreen(
                    message = (settingsState as SettingsState.Degraded).message,
                    onRetry = viewModel::retrySettings,
                    onReset = { resetConfirm = true },
                    onContinue = viewModel::dismissRecovery,
                )
                else -> when (screen) {
                    HomeScreen.Quiet -> QuietScreen(
                        isDefaultHome = isDefaultHome,
                        onOpenSearch = { viewModel.nav.navigateTo(HomeScreen.Search) },
                        onOpenAllApps = { viewModel.nav.navigateTo(HomeScreen.AllApps) },
                        onOpenSettings = { viewModel.nav.navigateTo(HomeScreen.Settings) },
                    )
                    HomeScreen.Intro -> IntroScreen(
                        isDefaultHome = isDefaultHome,
                        onSetHome = {
                            viewModel.nav.beginExternalFlow()
                            onRequestHomeRole()
                        },
                        onDone = viewModel::completeIntro,
                    )
                    HomeScreen.Search -> SearchScreen(
                        onAllApps = { viewModel.nav.navigateTo(HomeScreen.AllApps) },
                        onSettings = { viewModel.nav.navigateTo(HomeScreen.Settings) },
                        onBack = { viewModel.nav.back() },
                    )
                    HomeScreen.AllApps -> AllAppsScreen(
                        apps = viewModel.apps.collectAsState().value,
                        iconLoader = iconLoader,
                        onLaunch = viewModel::launchApp,
                        onAppInfo = { entry ->
                            viewModel.nav.beginExternalFlow()
                            onOpenAppInfo(entry.packageName)
                        },
                        onBack = { viewModel.nav.back() },
                    )
                    HomeScreen.Settings -> SettingsScreen(
                        isDefaultHome = isDefaultHome,
                        onSetHome = {
                            viewModel.nav.beginExternalFlow()
                            onRequestHomeRole()
                        },
                        onRestoreHome = {
                            viewModel.nav.beginExternalFlow()
                            onRestoreHome()
                        },
                        onChangeWallpaper = {
                            viewModel.nav.beginExternalFlow()
                            onChangeWallpaper()
                        },
                        onReplayIntro = { viewModel.nav.navigateTo(HomeScreen.Intro) },
                        onOpenAppInfo = {
                            viewModel.nav.beginExternalFlow()
                            onOpenAppInfo(context.packageName)
                        },
                        onBack = { viewModel.nav.back() },
                    )
                }
            }
        }

        if (resetConfirm) {
            AlertDialog(
                onDismissRequest = { resetConfirm = false },
                title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
                text = { Text(stringResource(R.string.settings_reset_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        resetConfirm = false
                        viewModel.resetSettings()
                    }) {
                        Text(stringResource(R.string.settings_reset_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { resetConfirm = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

/**
 * Quiet: wallpaper only. The up-swipe opens the search/deep entry, and the
 * same destinations are exposed as labelled accessibility actions so
 * TalkBack and switch access can reach them without the gesture.
 */
@Composable
private fun QuietScreen(
    isDefaultHome: Boolean,
    onOpenSearch: () -> Unit,
    onOpenAllApps: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val description = stringResource(R.string.quiet_preview_badge)
    val paneTitle = stringResource(R.string.quiet_pane_title)
    val openSearchLabel = stringResource(R.string.quiet_open_search)
    val allAppsLabel = stringResource(R.string.all_apps_entry)
    val settingsLabel = stringResource(R.string.settings_entry)
    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                this.paneTitle = paneTitle
                if (!isDefaultHome) contentDescription = description
                // Assistive-tech double-tap opens search; the other entries
                // are exposed as labelled custom actions.
                onClick(label = openSearchLabel) {
                    onOpenSearch()
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(allAppsLabel) {
                        onOpenAllApps()
                        true
                    },
                    CustomAccessibilityAction(settingsLabel) {
                        onOpenSettings()
                        true
                    },
                )
            }
            .pointerInput(Unit) { detectUpSwipe(onOpenSearch) },
    ) {
        if (!isDefaultHome) {
            Text(
                text = description,
                color = androidx.compose.ui.graphics.Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
                    .background(QuietBadgeBackground, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Settings recovery surface shown while the stored file is unreadable. */
@Composable
private fun RecoveryScreen(
    message: String,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepScrim)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_degraded_title),
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.settings_degraded_body),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = message,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_degraded_retry))
            }
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_degraded_reset))
            }
            OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_degraded_continue))
            }
        }
    }
}
