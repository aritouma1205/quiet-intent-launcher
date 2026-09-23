package io.github.aritouma1205.quietintentlauncher.settings

import android.graphics.drawable.Drawable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.apps.ShortcutEntry
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import io.github.aritouma1205.quietintentlauncher.ui.imageVector
import io.github.aritouma1205.quietintentlauncher.ui.labelRes
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 「行動・道具」 settings (design 6, 11.2): name, icon, visibility, order,
 * launch target, search aliases and derived ops for every DO action.
 * Edits go into a JSON-backed draft and are only persisted on save; a failed
 * save keeps the draft and leaves the stored settings untouched.
 *
 * [focusActionId] opens the target picker of one action directly — the DO
 * panel's unset-tap and この行動を編集 paths land here.
 */
@Composable
fun DoSettingsScreen(
    initial: SettingsData,
    focusActionId: String?,
    apps: List<AppEntry>?,
    iconLoader: (AppEntry) -> Drawable?,
    isHomeRoleHeld: Boolean,
    shortcutsFor: suspend (String) -> List<ShortcutEntry>,
    onSave: (SettingsData, (Boolean) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    // Unsaved edits survive Activity recreation (design 3): the draft is a
    // JSON round-trip in the saved-state bundle, independent of the file.
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
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<DoAction?>(null) }

    // Expanded target picker: "actionId" for the action target,
    // "actionId|opId" for a derived op target.
    var pickerSlot by rememberSaveable { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    val itemOffsets = remember { mutableStateMapOf<String, Int>() }

    LaunchedEffect(Unit) {
        val focus = focusActionId ?: return@LaunchedEffect
        pickerSlot = focus
        val y = snapshotFlow { itemOffsets[focus] }.filterNotNull().first()
        scrollState.animateScrollTo(y)
    }

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
                text = stringResource(R.string.do_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp),
        ) {
            draft.actions.forEachIndexed { index, action ->
                key(action.id) {
                    ActionEditor(
                        action = action,
                        isFirst = index == 0,
                        isLast = index == draft.actions.lastIndex,
                        pickerSlot = pickerSlot,
                        onPickerSlot = { pickerSlot = it },
                        apps = apps,
                        iconLoader = iconLoader,
                        isHomeRoleHeld = isHomeRoleHeld,
                        shortcutsFor = shortcutsFor,
                        onChange = { updated ->
                            draft = draft.copy(
                                actions = draft.actions.map {
                                    if (it.id == action.id) updated else it
                                },
                            )
                        },
                        onMoveUp = {
                            draft = draft.copy(
                                actions = ActionRules.moveUp(draft.actions, index),
                            )
                        },
                        onMoveDown = {
                            draft = draft.copy(
                                actions = ActionRules.moveDown(draft.actions, index),
                            )
                        },
                        onDelete = { deleteCandidate = action },
                        onPositioned = { itemOffsets[action.id] = it },
                    )
                }
                HorizontalDivider()
            }

            OutlinedButton(
                onClick = {
                    draft = draft.copy(
                        actions = draft.actions + ActionRules.newAction(),
                    )
                },
                enabled = ActionRules.canAdd(draft.actions),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.action_add))
            }
            if (!ActionRules.canAdd(draft.actions)) {
                Text(
                    text = stringResource(R.string.action_count_full),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            validationError?.let { errorName ->
                Text(
                    text = stringResource(
                        when (ActionValidationError.valueOf(errorName)) {
                            ActionValidationError.TooManyActions ->
                                R.string.action_error_count
                            ActionValidationError.InvalidName ->
                                R.string.action_error_name
                            ActionValidationError.InvalidAlias ->
                                R.string.action_error_alias
                            ActionValidationError.TooManyDerivedOps ->
                                R.string.action_error_ops
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
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
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        val error = ActionRules.validateActions(draft.actions)
                        if (error != null) {
                            validationError = error.name
                            saveFailed = false
                        } else {
                            validationError = null
                            onSave(draft) { ok ->
                                if (ok) onBack() else saveFailed = true
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.action_delete_title)) },
            text = {
                Text(stringResource(R.string.action_delete_body, candidate.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = draft.copy(
                        actions = draft.actions.filter { it.id != candidate.id },
                    )
                    deleteCandidate = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * One action card of the draft. Reorder uses explicit 上へ／下へ buttons —
 * the switch-accessible alternative to drag required by design 11.2.
 * Deleting an action that Context Slots reference needs slot cleanup; slots
 * do not exist yet, so a comment marks the stage-4 follow-up.
 */
@Composable
private fun ActionEditor(
    action: DoAction,
    isFirst: Boolean,
    isLast: Boolean,
    pickerSlot: String?,
    onPickerSlot: (String?) -> Unit,
    apps: List<AppEntry>?,
    iconLoader: (AppEntry) -> Drawable?,
    isHomeRoleHeld: Boolean,
    shortcutsFor: suspend (String) -> List<ShortcutEntry>,
    onChange: (DoAction) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onPositioned: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                onPositioned(it.positionInParent().y.toInt())
            }
            .padding(vertical = 12.dp),
    ) {
        OutlinedTextField(
            value = action.name,
            onValueChange = { onChange(action.copy(name = it)) },
            label = { Text(stringResource(R.string.action_name_label)) },
            isError = !ActionRules.isValidName(action.name),
            supportingText = {
                Text(stringResource(R.string.action_name_rule))
            },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = action.icon.imageVector(),
                    contentDescription = null,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(R.string.action_icon_label),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            ActionIcon.entries.forEach { icon ->
                FilterChip(
                    selected = action.icon == icon,
                    onClick = { onChange(action.copy(icon = icon)) },
                    label = {
                        Text(stringResource(icon.labelRes))
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = icon.imageVector(),
                            contentDescription = null,
                        )
                    },
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.action_visible),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = action.visible,
                onCheckedChange = { onChange(action.copy(visible = it)) },
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            OutlinedButton(onClick = onMoveUp, enabled = !isFirst) {
                Text(stringResource(R.string.action_move_up))
            }
            OutlinedButton(onClick = onMoveDown, enabled = !isLast) {
                Text(stringResource(R.string.action_move_down))
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.action_delete))
            }
        }

        // ---- Launch target -------------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.action_target_label),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = targetSummary(action.target, apps),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = {
                    onPickerSlot(if (pickerSlot == action.id) null else action.id)
                },
            ) {
                Text(stringResource(R.string.action_target_change))
            }
        }
        if (pickerSlot == action.id) {
            key(pickerSlot) {
                TargetPicker(
                    current = action.target,
                    apps = apps,
                    iconLoader = iconLoader,
                    isHomeRoleHeld = isHomeRoleHeld,
                    shortcutsFor = shortcutsFor,
                    onPick = { onChange(action.copy(target = it)) },
                )
            }
        }

        // ---- Search aliases --------------------------------------------------
        Text(
            text = stringResource(R.string.action_aliases_label),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.action_alias_rule),
            style = MaterialTheme.typography.bodySmall,
        )
        action.aliases.forEach { alias ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = alias,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    onChange(
                        action.copy(
                            aliases = action.aliases - alias,
                        ),
                    )
                }) {
                    Text(stringResource(R.string.action_alias_remove))
                }
            }
        }
        AliasAdder(
            enabled = action.aliases.size < ActionRules.MAX_ALIASES,
            onAdd = { alias ->
                onChange(action.copy(aliases = action.aliases + alias))
            },
        )

        // ---- Derived ops -----------------------------------------------------
        Text(
            text = stringResource(R.string.action_ops_label),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        action.derivedOps.forEach { op ->
            key(op.id) {
                DerivedOpEditor(
                    op = op,
                    pickerSlot = pickerSlot,
                    slotId = "${action.id}|${op.id}",
                    onPickerSlot = onPickerSlot,
                    apps = apps,
                    iconLoader = iconLoader,
                    isHomeRoleHeld = isHomeRoleHeld,
                    shortcutsFor = shortcutsFor,
                    onChange = { updated ->
                        onChange(
                            action.copy(
                                derivedOps = action.derivedOps.map {
                                    if (it.id == op.id) updated else it
                                },
                            ),
                        )
                    },
                    onDelete = {
                        onChange(
                            action.copy(
                                derivedOps = action.derivedOps
                                    .filter { it.id != op.id },
                            ),
                        )
                    },
                )
            }
        }
        TextButton(
            onClick = {
                onChange(
                    action.copy(
                        derivedOps = action.derivedOps +
                            DerivedOp(label = "操作"),
                    ),
                )
            },
            enabled = action.derivedOps.size < ActionRules.MAX_DERIVED_OPS,
        ) {
            Text(stringResource(R.string.action_op_add))
        }
        if (action.derivedOps.size >= ActionRules.MAX_DERIVED_OPS) {
            Text(
                text = stringResource(R.string.action_ops_full),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AliasAdder(
    enabled: Boolean,
    onAdd: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var rejected by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; rejected = false },
            label = { Text(stringResource(R.string.action_alias_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                val alias = input.trim()
                if (enabled && ActionRules.isValidAlias(alias)) {
                    onAdd(alias)
                    input = ""
                    rejected = false
                } else {
                    rejected = true
                }
            },
        ) {
            Text(stringResource(R.string.action_alias_add))
        }
    }
    if (rejected) {
        Text(
            text = stringResource(R.string.action_alias_rule),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DerivedOpEditor(
    op: DerivedOp,
    pickerSlot: String?,
    slotId: String,
    onPickerSlot: (String?) -> Unit,
    apps: List<AppEntry>?,
    iconLoader: (AppEntry) -> Drawable?,
    isHomeRoleHeld: Boolean,
    shortcutsFor: suspend (String) -> List<ShortcutEntry>,
    onChange: (DerivedOp) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = op.label,
            onValueChange = { onChange(op.copy(label = it)) },
            label = { Text(stringResource(R.string.action_op_label_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = targetSummary(op.target, apps),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    onPickerSlot(if (pickerSlot == slotId) null else slotId)
                },
            ) {
                Text(stringResource(R.string.action_target_change))
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.action_delete))
            }
        }
        if (pickerSlot == slotId) {
            key(slotId) {
                TargetPicker(
                    current = op.target,
                    apps = apps,
                    iconLoader = iconLoader,
                    isHomeRoleHeld = isHomeRoleHeld,
                    shortcutsFor = shortcutsFor,
                    onPick = { onChange(op.copy(target = it)) },
                )
            }
        }
    }
}
