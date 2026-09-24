package io.github.aritouma1205.quietintentlauncher.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aritouma1205.quietintentlauncher.R

/**
 * Dirty-draft exit guard (design 3): leaving a settings editor with unsaved
 * changes offers 保存する / 破棄する / 編集を続ける. Shared by the DO and
 * edge settings screens.
 */
@Composable
fun UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.unsaved_title)) },
        text = { Text(stringResource(R.string.unsaved_body)) },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.unsaved_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.unsaved_discard))
                }
                TextButton(onClick = onKeepEditing) {
                    Text(stringResource(R.string.unsaved_keep))
                }
            }
        },
    )
}
