package io.github.aritouma1205.quietintentlauncher.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import kotlinx.serialization.json.Json

/**
 * Search settings (design 9, 11.2): the 「最近」 recording switch, a clear
 * action, and the fixed web-search engine choice. The history lives in its
 * own file; stopping recording deletes it, and 「履歴をすべて消去」 marks it
 * for deletion — both take effect on save, consistent with the draft
 * semantics of the other settings screens.
 */
@Composable
fun SearchSettingsScreen(
    initial: SettingsData,
    onSave: (SettingsData, Boolean, (Boolean) -> Unit) -> Unit,
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
    var clearHistory by rememberSaveable { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    var exitConfirm by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    fun attemptSave() {
        onSave(draft, clearHistory) { ok ->
            if (ok) onBack() else saveFailed = true
        }
    }

    fun requestExit() {
        if (draft != initial || clearHistory) exitConfirm = true else onBack()
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
                text = stringResource(R.string.search_settings_title),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.search_settings_recent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.search_settings_recent_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = draft.search.recentRecording,
                    onCheckedChange = { checked ->
                        if (!checked) {
                            // Stopping also deletes stored history — confirm
                            // first (design 9).
                            confirmStop = true
                        } else {
                            draft = draft.copy(
                                search = draft.search.copy(recentRecording = true),
                            )
                            clearHistory = false
                        }
                    },
                )
            }

            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.search_settings_clear))
            }
            if (clearHistory) {
                Text(
                    text = stringResource(R.string.search_settings_clear_marks),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Text(
                text = stringResource(R.string.search_settings_engine),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 24.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                WebSearchEngine.entries.forEach { engine ->
                    FilterChip(
                        selected = draft.search.webEngine == engine,
                        onClick = {
                            draft = draft.copy(
                                search = draft.search.copy(webEngine = engine),
                            )
                        },
                        label = { Text(engine.name) },
                    )
                }
            }

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

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.search_settings_recent)) },
            text = { Text(stringResource(R.string.search_settings_stop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    draft = draft.copy(
                        search = draft.search.copy(recentRecording = false),
                    )
                }) {
                    Text(stringResource(R.string.search_settings_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.search_settings_clear_title)) },
            text = { Text(stringResource(R.string.search_settings_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    clearHistory = true
                }) {
                    Text(stringResource(R.string.search_settings_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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
