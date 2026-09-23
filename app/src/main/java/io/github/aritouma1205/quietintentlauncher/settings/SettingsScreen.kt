package io.github.aritouma1205.quietintentlauncher.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.BuildConfig
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim

/**
 * Minimal settings for stage 1 (design 11.2 subset): HOME role status and
 * the way back to the previous home, wallpaper via the OS picker, intro
 * replay and app info. Later stages add the remaining sections.
 */
@Composable
fun SettingsScreen(
    isDefaultHome: Boolean,
    edgeSettingsEnabled: Boolean,
    doSettingsEnabled: Boolean,
    onSetHome: () -> Unit,
    onRestoreHome: () -> Unit,
    onChangeWallpaper: () -> Unit,
    onEdgeSettings: () -> Unit,
    onDoSettings: () -> Unit,
    onReplayIntro: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepScrim)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            SettingsSection(stringResource(R.string.settings_section_home)) {
                Text(
                    text = stringResource(
                        if (isDefaultHome) {
                            R.string.settings_home_state_default
                        } else {
                            R.string.settings_home_state_not_default
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isDefaultHome) {
                    SettingsButton(
                        label = stringResource(R.string.settings_restore_home),
                        note = stringResource(R.string.settings_restore_home_note),
                        onClick = onRestoreHome,
                    )
                } else {
                    SettingsButton(
                        label = stringResource(R.string.settings_set_home),
                        note = null,
                        onClick = onSetHome,
                    )
                }
            }

            SettingsSection(stringResource(R.string.settings_section_display)) {
                SettingsButton(
                    label = stringResource(R.string.settings_change_wallpaper),
                    note = stringResource(R.string.settings_change_wallpaper_note),
                    onClick = onChangeWallpaper,
                )
            }

            SettingsSection(stringResource(R.string.settings_section_edge)) {
                SettingsButton(
                    label = stringResource(R.string.settings_edge_entry),
                    note = if (edgeSettingsEnabled) {
                        stringResource(R.string.settings_edge_note)
                    } else {
                        stringResource(R.string.edge_settings_unavailable)
                    },
                    onClick = onEdgeSettings,
                    enabled = edgeSettingsEnabled,
                )
            }

            SettingsSection(stringResource(R.string.settings_section_actions)) {
                SettingsButton(
                    label = stringResource(R.string.settings_actions_entry),
                    note = if (doSettingsEnabled) {
                        stringResource(R.string.settings_actions_note)
                    } else {
                        stringResource(R.string.edge_settings_unavailable)
                    },
                    onClick = onDoSettings,
                    enabled = doSettingsEnabled,
                )
            }

            SettingsSection(stringResource(R.string.settings_section_about)) {
                SettingsButton(
                    label = stringResource(R.string.settings_replay_intro),
                    note = null,
                    onClick = onReplayIntro,
                )
                SettingsButton(
                    label = stringResource(R.string.settings_app_info),
                    note = null,
                    onClick = onOpenAppInfo,
                )
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun SettingsButton(
    label: String,
    note: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label)
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
