package io.github.aritouma1205.quietintentlauncher.settings

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.apps.ShortcutEntry
import io.github.aritouma1205.quietintentlauncher.context.ContextRules
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
    focusToolId: String? = null,
    onEditFocusConsumed: () -> Unit = {},
    apps: List<AppEntry>?,
    recents: List<AppEntry> = emptyList(),
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
    var exitConfirm by rememberSaveable { mutableStateOf(false) }

    // Expanded target picker: "actionId" for the action target,
    // "actionId|opId" for a derived op target, "tool:toolId" for a tool.
    // Deliberately NOT rememberSaveable: a picker is transient UI, and a
    // restored slot would resurrect a stale picker on the next plain visit
    // (editor focus requests are one-shot, consumed on entry).
    var pickerSlot by remember { mutableStateOf<String?>(null) }

    // Issue 35 accordion: at most one editor is expanded at a time. The key
    // space is shared — "tool:<id>" keys stay disjoint from action UUIDs.
    // A focus entry auto-expands its row so the picker inside it can mount.
    var expandedId by rememberSaveable {
        mutableStateOf(focusActionId ?: focusToolId?.let { "tool:$it" })
    }
    // Deferred scroll target: the row must lay out before its offset is
    // known, so the effect waits for the anchor via snapshotFlow.
    var scrollTargetKey by remember { mutableStateOf<String?>(null) }

    // The open link editor reports an invalid non-blank input here; saving
    // while one is pending would silently keep the previous target.
    var pendingInvalidLink by rememberSaveable { mutableStateOf(false) }
    var linkPendingError by rememberSaveable { mutableStateOf(false) }

    fun attemptSave() {
        when {
            pendingInvalidLink -> {
                linkPendingError = true
                validationError = null
                saveFailed = false
            }
            else -> {
                val error = ActionRules.validateActions(draft.actions)
                if (error != null) {
                    validationError = error.name
                    linkPendingError = false
                    saveFailed = false
                } else {
                    validationError = null
                    linkPendingError = false
                    onSave(draft) { ok ->
                        if (ok) onBack() else saveFailed = true
                    }
                }
            }
        }
    }

    // Design 3: leaving with unsaved changes asks 保存 / 破棄 / 編集に戻る.
    // This inner handler also intercepts the OS back before HomeUi's outer
    // one discards the draft.
    fun requestExit() {
        // Read the states at call time: a back press can arrive between a
        // draft write and the recomposition that would refresh a captured
        // dirty flag. A typed-but-unpicked link input counts as dirty too.
        val dirty = draft != initial || pendingInvalidLink
        if (dirty) exitConfirm = true else onBack()
    }
    BackHandler { requestExit() }

    val scrollState = rememberScrollState()
    val itemOffsets = remember { mutableStateMapOf<String, Int>() }

    LaunchedEffect(Unit) {
        val focus = focusActionId
        val toolFocus = focusToolId
        val slot = when {
            focus != null -> focus
            // A tool entry scrolls to the TOOLS heading and opens that
            // tool's target picker when the tool accepts one.
            toolFocus != null -> "tool:$toolFocus"
            else -> null
        }
        if (slot != null) pickerSlot = slot
        // Focus requests are one-shot: whichever picker claimed the entry
        // — or none — the request is cleared so a later plain visit never
        // re-opens a stale picker (design 7 代替導線).
        onEditFocusConsumed()
        if (slot != null) {
            scrollTargetKey = if (focus != null) focus else TOOLS_OFFSET_KEY
        }
    }

    // Scroll after the target row reports its position: expanding a row
    // changes the layout, so the anchor arrives asynchronously.
    LaunchedEffect(scrollTargetKey) {
        val anchorKey = scrollTargetKey ?: return@LaunchedEffect
        val y = snapshotFlow { itemOffsets[anchorKey] }
            .filterNotNull().first()
        scrollState.animateScrollTo(y)
        scrollTargetKey = null
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
            TextButton(onClick = { requestExit() }) {
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
                    val expanded = expandedId == action.id
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned {
                                itemOffsets[action.id] =
                                    it.positionInParent().y.toInt()
                            },
                    ) {
                        CollapsedActionRow(
                            action = action,
                            expanded = expanded,
                            isFirst = index == 0,
                            isLast = index == draft.actions.lastIndex,
                            apps = apps,
                            onToggle = {
                                expandedId = if (expanded) null else action.id
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
                        )
                        if (expanded) {
                            ActionEditor(
                                action = action,
                                isFirst = index == 0,
                                isLast = index == draft.actions.lastIndex,
                                pickerSlot = pickerSlot,
                                onPickerSlot = { pickerSlot = it },
                                apps = apps,
                                recents = recents,
                                iconLoader = iconLoader,
                                isHomeRoleHeld = isHomeRoleHeld,
                                shortcutsFor = shortcutsFor,
                                onPendingInvalid = { pendingInvalidLink = it },
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
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            OutlinedButton(
                onClick = {
                    val added = ActionRules.newAction()
                    draft = draft.copy(
                        actions = draft.actions + added,
                    )
                    // Issue 35: the new row opens expanded and scrolls into
                    // view once it has laid out.
                    expandedId = added.id
                    scrollTargetKey = added.id
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

            // ---- TOOLS (design 7): fixed set — order, visibility and the
            // per-tool launch target. There is no add/delete; the five
            // tools always exist, hidden ones only leave the panel.
            Text(
                text = stringResource(R.string.tools_edit_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .onGloballyPositioned {
                        itemOffsets[TOOLS_OFFSET_KEY] =
                            it.positionInParent().y.toInt()
                    }
                    .padding(top = 24.dp),
            )
            Text(
                text = stringResource(R.string.tools_edit_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            draft.tools.items.forEachIndexed { index, item ->
                val tool = ToolItem.byId(item.id) ?: return@forEachIndexed
                key(item.id) {
                    val toolExpanded = expandedId == "tool:${tool.id}"
                    Column(Modifier.fillMaxWidth()) {
                        CollapsedToolRow(
                            tool = tool,
                            item = item,
                            expanded = toolExpanded,
                            isFirst = index == 0,
                            isLast = index == draft.tools.items.lastIndex,
                            apps = apps,
                            onToggle = {
                                expandedId =
                                    if (toolExpanded) null else "tool:${tool.id}"
                            },
                            onVisibleChange = { visible ->
                                draft = draft.copy(
                                    tools = draft.tools.copy(
                                        items = draft.tools.items.map {
                                            if (it.id == item.id) {
                                                it.copy(visible = visible)
                                            } else {
                                                it
                                            }
                                        },
                                    ),
                                )
                            },
                            onMoveUp = {
                                draft = draft.copy(
                                    tools = draft.tools.copy(
                                        items = ToolRules.moveUp(
                                            draft.tools.items,
                                            index,
                                        ),
                                    ),
                                )
                            },
                            onMoveDown = {
                                draft = draft.copy(
                                    tools = draft.tools.copy(
                                        items = ToolRules.moveDown(
                                            draft.tools.items,
                                            index,
                                        ),
                                    ),
                                )
                            },
                        )
                        if (toolExpanded) {
                            ToolEditor(
                                tool = tool,
                                item = item,
                                isFirst = index == 0,
                                isLast = index == draft.tools.items.lastIndex,
                                pickerSlot = pickerSlot,
                                onPickerSlot = { pickerSlot = it },
                                apps = apps,
                                recents = recents,
                                iconLoader = iconLoader,
                                isHomeRoleHeld = isHomeRoleHeld,
                                shortcutsFor = shortcutsFor,
                                onPendingInvalid = { pendingInvalidLink = it },
                                onChange = { updated ->
                                    draft = draft.copy(
                                        tools = draft.tools.copy(
                                            items = draft.tools.items.map {
                                                if (it.id == item.id) updated else it
                                            },
                                        ),
                                    )
                                },
                                onMoveUp = {
                                    draft = draft.copy(
                                        tools = draft.tools.copy(
                                            items = ToolRules.moveUp(
                                                draft.tools.items,
                                                index,
                                            ),
                                        ),
                                    )
                                },
                                onMoveDown = {
                                    draft = draft.copy(
                                        tools = draft.tools.copy(
                                            items = ToolRules.moveDown(
                                                draft.tools.items,
                                                index,
                                            ),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                HorizontalDivider()
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
            if (linkPendingError && pendingInvalidLink) {
                Text(
                    text = stringResource(R.string.action_error_link_pending),
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

    deleteCandidate?.let { candidate ->
        // Referenced by Context Slots? Say so, and clean the references in
        // the same draft write (design 10, 14.1: no dangling action ids).
        val referenced = ContextRules.referencesAction(draft.contextSlots, candidate.id)
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.action_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (referenced) {
                            R.string.action_delete_body_refs
                        } else {
                            R.string.action_delete_body
                        },
                        candidate.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = draft.copy(
                        actions = draft.actions.filter { it.id != candidate.id },
                        contextSlots = ContextRules.removingAction(
                            draft.contextSlots,
                            candidate.id,
                        ),
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

/**
 * One action card of the draft. Reorder uses explicit 上へ／下へ buttons —
 * the switch-accessible alternative to drag required by design 11.2.
 * Deleting an action that Context Slots reference also strips those
 * references inside the same draft (design 10).
 */
@Composable
private fun ActionEditor(
    action: DoAction,
    isFirst: Boolean,
    isLast: Boolean,
    pickerSlot: String?,
    onPickerSlot: (String?) -> Unit,
    apps: List<AppEntry>?,
    recents: List<AppEntry>,
    iconLoader: (AppEntry) -> Drawable?,
    isHomeRoleHeld: Boolean,
    shortcutsFor: suspend (String) -> List<ShortcutEntry>,
    onPendingInvalid: (Boolean) -> Unit,
    onChange: (DoAction) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                    recents = recents,
                    iconLoader = iconLoader,
                    isHomeRoleHeld = isHomeRoleHeld,
                    shortcutsFor = shortcutsFor,
                    onPick = { onChange(action.copy(target = it)) },
                    onPendingInvalid = onPendingInvalid,
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
                    recents = recents,
                    iconLoader = iconLoader,
                    isHomeRoleHeld = isHomeRoleHeld,
                    shortcutsFor = shortcutsFor,
                    onPendingInvalid = onPendingInvalid,
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
    recents: List<AppEntry>,
    iconLoader: (AppEntry) -> Drawable?,
    isHomeRoleHeld: Boolean,
    shortcutsFor: suspend (String) -> List<ShortcutEntry>,
    onPendingInvalid: (Boolean) -> Unit,
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
                    recents = recents,
                    iconLoader = iconLoader,
                    isHomeRoleHeld = isHomeRoleHeld,
                    shortcutsFor = shortcutsFor,
                    onPick = { onChange(op.copy(target = it)) },
                    onPendingInvalid = onPendingInvalid,
                )
            }
        }
    }
}

/** Scroll-anchor key of the TOOLS heading for the focus navigation. */
private const val TOOLS_OFFSET_KEY = "tools"

/**
 * One tool card of the draft (design 7): name, 表示 switch, 上へ/下へ
 * reorder, and — for the targetable tools — the launch-target row. Light
 * and screenshot are internal operations and carry no target; the QR tool
 * additionally accepts a public shortcut.
 */
@Composable
private fun ToolEditor(
    tool: ToolItem,
    item: ToolSetting,
    isFirst: Boolean,
    isLast: Boolean,
    pickerSlot: String?,
    onPickerSlot: (String?) -> Unit,
    apps: List<AppEntry>?,
    recents: List<AppEntry>,
    iconLoader: (AppEntry) -> Drawable?,
    isHomeRoleHeld: Boolean,
    shortcutsFor: suspend (String) -> List<ShortcutEntry>,
    onPendingInvalid: (Boolean) -> Unit,
    onChange: (ToolSetting) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val slotId = "tool:${tool.id}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        // The tool name lives on the collapsed row above; the expanded
        // editor starts directly with the fields.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.action_visible),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = item.visible,
                onCheckedChange = { onChange(item.copy(visible = it)) },
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
        }
        if (tool in ToolItem.TARGETABLE) {
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
                        text = if (tool == ToolItem.Timer && item.target == null) {
                            stringResource(R.string.tool_timer_list)
                        } else {
                            targetSummary(item.target, apps)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = {
                        onPickerSlot(if (pickerSlot == slotId) null else slotId)
                    },
                ) {
                    Text(stringResource(R.string.action_target_change))
                }
            }
            if (pickerSlot == slotId) {
                key(slotId) {
                    TargetPicker(
                        current = item.target,
                        apps = apps,
                        recents = recents,
                        iconLoader = iconLoader,
                        isHomeRoleHeld = isHomeRoleHeld,
                        shortcutsFor = shortcutsFor,
                        onPick = { onChange(item.copy(target = it)) },
                        onPendingInvalid = onPendingInvalid,
                        allowedModes = if (tool in ToolItem.SHORTCUT_CAPABLE) {
                            setOf(PickerMode.App, PickerMode.Shortcut)
                        } else {
                            setOf(PickerMode.App)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Collapsed action row (issue 35): icon, name and the launch-target summary
 * stay visible while the full editor below is folded away. The leading zone
 * is one merged clickable node so TalkBack reads name + summary + expand
 * state as a unit; the reorder buttons and chevron stay separate targets so
 * switch access can still move an action without expanding it.
 */
@Composable
private fun CollapsedActionRow(
    action: DoAction,
    expanded: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    apps: List<AppEntry>?,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val stateDesc = stringResource(
        if (expanded) R.string.action_state_expanded
        else R.string.action_state_collapsed,
    )
    val toggleLabel = stringResource(
        if (expanded) R.string.action_collapse else R.string.action_expand,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    stateDescription = stateDesc
                }
                .clickable(onClickLabel = toggleLabel, onClick = onToggle)
                .padding(vertical = 8.dp),
        ) {
            Icon(
                imageVector = action.icon.imageVector(),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = action.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = targetSummary(action.target, apps),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onMoveUp, enabled = !isFirst) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.action_move_up),
            )
        }
        IconButton(onClick = onMoveDown, enabled = !isLast) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.action_move_down),
            )
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = toggleLabel,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
    }
}

/**
 * Collapsed tool row (issue 35): name, launch-target summary for the
 * targetable tools, the visibility switch (kept reachable while folded),
 * reorder buttons and the expand chevron.
 */
@Composable
private fun CollapsedToolRow(
    tool: ToolItem,
    item: ToolSetting,
    expanded: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    apps: List<AppEntry>?,
    onToggle: () -> Unit,
    onVisibleChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val stateDesc = stringResource(
        if (expanded) R.string.action_state_expanded
        else R.string.action_state_collapsed,
    )
    val toggleLabel = stringResource(
        if (expanded) R.string.action_collapse else R.string.action_expand,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    stateDescription = stateDesc
                }
                .clickable(onClickLabel = toggleLabel, onClick = onToggle)
                .padding(vertical = 8.dp),
        ) {
            Column {
                Text(
                    text = stringResource(tool.labelRes),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tool in ToolItem.TARGETABLE) {
                    Text(
                        text = if (tool == ToolItem.Timer && item.target == null) {
                            stringResource(R.string.tool_timer_list)
                        } else {
                            targetSummary(item.target, apps)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Switch(
            checked = item.visible,
            onCheckedChange = onVisibleChange,
        )
        IconButton(onClick = onMoveUp, enabled = !isFirst) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.action_move_up),
            )
        }
        IconButton(onClick = onMoveDown, enabled = !isLast) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.action_move_down),
            )
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = toggleLabel,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
    }
}
