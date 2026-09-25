package io.github.aritouma1205.quietintentlauncher.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import kotlinx.serialization.json.Json

/**
 * 「システム操作」 settings (design 11.2, 13): three individual switches —
 * notification shade, screen off, TOOLS screenshot — plus the optional
 * service state and the OS-settings entry point.
 *
 * The switches persist via the same draft contract as the other settings
 * screens: edits are in-memory until save, a failed save keeps the draft,
 * and nothing claims success that did not happen. The service row shows
 * live state; granting happens only in the OS accessibility settings.
 */
@Composable
fun SystemActionsSettingsScreen(
    initial: SettingsData,
    serviceEnabled: Boolean,
    serviceConnected: Boolean,
    onOpenServiceSettings: () -> Unit,
    onSave: (SettingsData, (Boolean) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val settingsSaver = remember {
        Saver<SettingsData, String>(
            save = { Json.encodeToString(SettingsData.serializer(), it) },
            restore = { Json.decodeFromString(SettingsData.serializer(), it) },
        )
    }
    var draft by rememberSaveable(stateSaver = settingsSaver) {
        mutableStateOf(initial)
    }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    var exitConfirm by rememberSaveable { mutableStateOf(false) }

    fun attemptSave() {
        onSave(draft) { ok ->
            if (ok) onBack() else saveFailed = true
        }
    }

    fun requestExit() {
        val dirty = draft != initial
        if (dirty) exitConfirm = true else onBack()
    }
    BackHandler { requestExit() }

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
            TextButton(onClick = { requestExit() }) {
                Text(stringResource(R.string.back))
            }
            Text(
                text = stringResource(R.string.system_settings_title),
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
            // Live service state: the OS flag AND a bound instance. The
            // label distinguishes "permitted" from "actually connected" so
            // a revoked or not-yet-bound service never looks usable
            // (design 13).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.system_service_state),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            if (serviceEnabled && serviceConnected) {
                                R.string.system_service_on
                            } else {
                                R.string.system_service_off_state
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(
                onClick = onOpenServiceSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.system_service_open_settings))
            }

            SystemSwitch(
                label = stringResource(R.string.system_notifications_switch),
                checked = draft.systemActions.notificationsEnabled,
                serviceReady = serviceEnabled,
                onChange = {
                    draft = draft.copy(
                        systemActions = draft.systemActions.copy(
                            notificationsEnabled = it,
                        ),
                    )
                },
            )
            SystemSwitch(
                label = stringResource(R.string.system_screen_off_switch),
                checked = draft.systemActions.screenOffEnabled,
                serviceReady = serviceEnabled,
                onChange = {
                    draft = draft.copy(
                        systemActions = draft.systemActions.copy(
                            screenOffEnabled = it,
                        ),
                    )
                },
            )
            SystemSwitch(
                label = stringResource(R.string.system_screenshot_switch),
                checked = draft.systemActions.screenshotEnabled,
                serviceReady = serviceEnabled,
                onChange = {
                    draft = draft.copy(
                        systemActions = draft.systemActions.copy(
                            screenshotEnabled = it,
                        ),
                    )
                },
            )

            Text(
                text = stringResource(R.string.system_service_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )

            if (saveFailed) {
                Text(
                    text = stringResource(R.string.settings_save_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            ) {
                OutlinedButton(
                    onClick = { requestExit() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { attemptSave() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    if (exitConfirm) {
        UnsavedChangesDialog(
            onSave = {
                exitConfirm = false
                attemptSave()
            },
            onDiscard = {
                exitConfirm = false
                onBack()
            },
            onKeepEditing = { exitConfirm = false },
        )
    }
}

@Composable
private fun SystemSwitch(
    label: String,
    checked: Boolean,
    serviceReady: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
        // A switch ON while the service is not permitted is saved but
        // inert — the note keeps the dependency visible (design 13).
        if (checked && !serviceReady) {
            Text(
                text = stringResource(R.string.system_switch_needs_service),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
