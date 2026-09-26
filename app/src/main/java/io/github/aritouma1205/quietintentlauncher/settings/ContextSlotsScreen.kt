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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.context.ContextRule
import io.github.aritouma1205.quietintentlauncher.context.ContextRules
import io.github.aritouma1205.quietintentlauncher.context.ContextSlot
import io.github.aritouma1205.quietintentlauncher.context.ContextSlotError
import io.github.aritouma1205.quietintentlauncher.ui.DeepScrim
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Context Slots editor (design 10, 11.2): two fixed slots, each with an
 * ordered rule list and an optional default action. Rules are
 * 「曜日＋時刻帯＋行動」; an empty day set means every day, 終日 means no
 * time bounds, and start > end is a cross-midnight range.
 *
 * Edits go into a JSON-backed draft and persist only on save, like the
 * other settings screens. Rules referencing a deleted DO action are shown
 * as 「行動を選ぶ」 and must be reassigned or removed before saving —
 * validateRule rejects them, which keeps dangling ids out of the file.
 */
@Composable
fun ContextSlotsScreen(
    initial: SettingsData,
    focusSlotIndex: Int?,
    onEditFocusConsumed: () -> Unit = {},
    onSave: (SettingsData, (Boolean) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    // Unsaved edits survive Activity recreation (design 3).
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
    var exitConfirm by rememberSaveable { mutableStateOf(false) }

    // Time fields keep raw text so partial input is not lost while typing;
    // the raw string is parsed into the draft on each change. Raw input is
    // unsaved work like the draft itself, so it survives Activity
    // recreation too (design 3) — an unparseable value must still be there
    // to fix after a config change.
    val timeInputs = rememberSaveable(
        saver = listSaver<SnapshotStateMap<String, Pair<String, String>>, String>(
            save = { map ->
                buildList {
                    map.forEach { (ruleId, pair) ->
                        add(ruleId)
                        add(pair.first)
                        add(pair.second)
                    }
                }
            },
            restore = { flat ->
                mutableStateMapOf<String, Pair<String, String>>().apply {
                    flat.chunked(3).forEach { chunk ->
                        if (chunk.size == 3) this[chunk[0]] = chunk[1] to chunk[2]
                    }
                }
            },
        ),
    ) { mutableStateMapOf() }

    fun updateSlot(index: Int, slot: ContextSlot) {
        draft = draft.copy(
            contextSlots = draft.contextSlots.mapIndexed { i, s ->
                if (i == index) slot else s
            },
        )
    }

    // Raw time fields are the source of truth for rules being edited:
    // unparseable or unsettled input is unsaved work too — it must neither
    // silently save the last parsed value nor be dropped without the exit
    // confirmation (e.g. typing "25:00" must not persist 9:00, and Back must
    // still ask whether to keep editing).
    fun hasStaleTimeInput(): Boolean = draft.contextSlots.any { slot ->
        slot.rules.any { rule ->
            timeInputs[rule.id]?.let { (startText, endText) ->
                parseMinute(startText) != rule.startMinuteOfDay ||
                    parseMinute(endText) != rule.endMinuteOfDay
            } == true
        }
    }

    fun attemptSave() {
        val staleTimeInput = hasStaleTimeInput()
        val actionIds = draft.actions.mapTo(HashSet()) { it.id }
        val dangling = draft.contextSlots.any { slot ->
            (slot.defaultActionId != null && slot.defaultActionId !in actionIds) ||
                slot.rules.any { it.actionId != null && it.actionId !in actionIds }
        }
        // A dangling reference never survives a save (design 14.1); the
        // user reassigns or drops the rule instead.
        val error = ContextRules.validateSlots(draft.contextSlots)
            ?: if (staleTimeInput) {
                ContextSlotError.InvalidTimeRange
            } else if (dangling) {
                ContextSlotError.MissingAction
            } else {
                null
            }
        if (error != null) {
            validationError = error.name
            saveFailed = false
        } else {
            validationError = null
            onSave(draft) { ok ->
                if (ok) onBack() else saveFailed = true
            }
        }
    }

    fun requestExit() {
        if (draft != initial || hasStaleTimeInput()) exitConfirm = true else onBack()
    }
    BackHandler { requestExit() }

    val scrollState = rememberScrollState()
    val slotOffsets = remember { mutableStateMapOf<Int, Int>() }

    LaunchedEffect(Unit) {
        val focus = focusSlotIndex
        // Focus requests are one-shot: consumed whether or not a slot was
        // requested, so a later plain visit never re-applies a stale
        // scroll (same contract as DoSettingsScreen).
        onEditFocusConsumed()
        if (focus != null) {
            val y = snapshotFlow { slotOffsets[focus] }.filterNotNull().first()
            scrollState.animateScrollTo(y)
        }
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
                text = stringResource(R.string.context_settings_title),
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
            draft.contextSlots.forEachIndexed { index, slot ->
                key(slot.id) {
                    SlotEditor(
                        index = index,
                        slot = slot,
                        actions = draft.actions,
                        timeInputs = timeInputs,
                        onChange = { updateSlot(index, it) },
                        onPositioned = { slotOffsets[index] = it },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
            }

            validationError?.let { errorName ->
                Text(
                    text = stringResource(
                        when (ContextSlotError.valueOf(errorName)) {
                            ContextSlotError.TooManySlots ->
                                R.string.context_error_toomany
                            ContextSlotError.TooManyRules ->
                                R.string.context_error_toomany
                            ContextSlotError.InvalidDaySet ->
                                R.string.context_error_time
                            ContextSlotError.InvalidTimeRange ->
                                R.string.context_error_time
                            ContextSlotError.MissingAction ->
                                R.string.context_error_action
                            ContextSlotError.InvalidLabel ->
                                R.string.context_error_label
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

/** One slot card: label, ordered rules, default action. */
@Composable
private fun SlotEditor(
    index: Int,
    slot: ContextSlot,
    actions: List<DoAction>,
    timeInputs: MutableMap<String, Pair<String, String>>,
    onChange: (ContextSlot) -> Unit,
    onPositioned: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onPositioned(it.positionInParent().y.toInt()) }
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.context_slot_title, index + 1),
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
            value = slot.label,
            onValueChange = {
                onChange(slot.copy(label = it.take(ContextRules.LABEL_MAX_LENGTH)))
            },
            label = { Text(stringResource(R.string.context_slot_label)) },
            isError = !ContextRules.isValidLabel(slot.label),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        Text(
            text = stringResource(R.string.context_rules_label),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        slot.rules.forEachIndexed { ruleIndex, rule ->
            key(rule.id) {
                RuleEditor(
                    rule = rule,
                    isFirst = ruleIndex == 0,
                    isLast = ruleIndex == slot.rules.lastIndex,
                    actions = actions,
                    timeInputs = timeInputs,
                    onChange = { updated ->
                        onChange(
                            slot.copy(
                                rules = slot.rules.map {
                                    if (it.id == rule.id) updated else it
                                },
                            ),
                        )
                    },
                    onMoveUp = {
                        onChange(
                            slot.copy(
                                rules = slot.rules.toMutableList().apply {
                                    add(ruleIndex - 1, removeAt(ruleIndex))
                                },
                            ),
                        )
                    },
                    onMoveDown = {
                        onChange(
                            slot.copy(
                                rules = slot.rules.toMutableList().apply {
                                    add(ruleIndex + 1, removeAt(ruleIndex))
                                },
                            ),
                        )
                    },
                    onDelete = {
                        onChange(
                            slot.copy(
                                rules = slot.rules.filter { it.id != rule.id },
                            ),
                        )
                    },
                )
            }
        }
        OutlinedButton(
            onClick = { onChange(slot.copy(rules = slot.rules + ContextRule())) },
            enabled = slot.rules.size < ContextRules.MAX_RULES_PER_SLOT,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.context_rule_add))
        }
        if (slot.rules.size >= ContextRules.MAX_RULES_PER_SLOT) {
            Text(
                text = stringResource(R.string.context_rules_full),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        ActionPicker(
            title = stringResource(R.string.context_default_action),
            selectedId = slot.defaultActionId,
            actions = actions,
            allowNone = true,
            onSelect = { onChange(slot.copy(defaultActionId = it)) },
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** One rule row: day chips, 終日/time range, action, order, delete. */
@Composable
private fun RuleEditor(
    rule: ContextRule,
    isFirst: Boolean,
    isLast: Boolean,
    actions: List<DoAction>,
    timeInputs: MutableMap<String, Pair<String, String>>,
    onChange: (ContextRule) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val allDay = rule.startMinuteOfDay == null && rule.endMinuteOfDay == null
    val input = timeInputs[rule.id] ?: (
        (rule.startMinuteOfDay?.let(::minuteText) ?: "") to
            (rule.endMinuteOfDay?.let(::minuteText) ?: "")
        )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.context_rule_days),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            (1..7).forEach { day ->
                FilterChip(
                    selected = day in rule.daysOfWeek,
                    onClick = {
                        onChange(
                            rule.copy(
                                daysOfWeek = if (day in rule.daysOfWeek) {
                                    rule.daysOfWeek - day
                                } else {
                                    rule.daysOfWeek + day
                                },
                            ),
                        )
                    },
                    label = { Text(stringResource(dayLabelRes(day))) },
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.context_rule_allday),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = allDay,
                onCheckedChange = { checked ->
                    if (checked) {
                        timeInputs.remove(rule.id)
                        onChange(
                            rule.copy(
                                startMinuteOfDay = null,
                                endMinuteOfDay = null,
                            ),
                        )
                    } else {
                        // Seed editable defaults; validation catches equal
                        // bounds until the user adjusts them.
                        val seeded = rule.copy(
                            startMinuteOfDay = 9 * 60,
                            endMinuteOfDay = 18 * 60,
                        )
                        timeInputs[rule.id] = minuteText(540) to minuteText(1080)
                        onChange(seeded)
                    }
                },
            )
        }
        if (!allDay) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input.first,
                    onValueChange = { text ->
                        val cleaned = text.filter { it.isDigit() || it == ':' }.take(5)
                        timeInputs[rule.id] = cleaned to input.second
                        parseMinute(cleaned)?.let { start ->
                            onChange(rule.copy(startMinuteOfDay = start))
                        }
                    },
                    label = { Text(stringResource(R.string.context_rule_start)) },
                    placeholder = {
                        Text(stringResource(R.string.context_rule_time_hint))
                    },
                    isError = parseMinute(input.first) == null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = input.second,
                    onValueChange = { text ->
                        val cleaned = text.filter { it.isDigit() || it == ':' }.take(5)
                        timeInputs[rule.id] = input.first to cleaned
                        parseMinute(cleaned)?.let { end ->
                            onChange(rule.copy(endMinuteOfDay = end))
                        }
                    },
                    label = { Text(stringResource(R.string.context_rule_end)) },
                    placeholder = {
                        Text(stringResource(R.string.context_rule_time_hint))
                    },
                    isError = parseMinute(input.second) == null ||
                        (parseMinute(input.first) != null &&
                            parseMinute(input.first) == parseMinute(input.second)),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ActionPicker(
            title = stringResource(R.string.context_rule_action),
            selectedId = rule.actionId,
            actions = actions,
            allowNone = false,
            onSelect = { onChange(rule.copy(actionId = it)) },
            modifier = Modifier.padding(top = 8.dp),
        )

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
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

/** Scrollable action chips; [allowNone] adds the 「なし」 choice. */
@Composable
private fun ActionPicker(
    title: String,
    selectedId: String?,
    actions: List<DoAction>,
    allowNone: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            if (allowNone) {
                FilterChip(
                    selected = selectedId == null,
                    onClick = { onSelect(null) },
                    label = { Text(stringResource(R.string.context_action_none)) },
                )
            }
            actions.forEach { action ->
                FilterChip(
                    selected = selectedId == action.id,
                    onClick = { onSelect(action.id) },
                    label = { Text(action.name.ifBlank { "…" }) },
                )
            }
            if (selectedId != null && actions.none { it.id == selectedId }) {
                // Referenced action was deleted; the stale id stays visible
                // until reassigned (save rejects it via MissingAction).
                FilterChip(
                    selected = true,
                    onClick = { },
                    label = { Text(stringResource(R.string.context_pick_action)) },
                )
            }
        }
    }
}

private fun minuteText(minuteOfDay: Int): String =
    "${minuteOfDay / 60}:${"%02d".format(minuteOfDay % 60)}"

/** Strict HH:mm (or H:mm) inside the day; anything else is unparseable. */
private fun parseMinute(text: String): Int? {
    val parts = text.split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    if (parts[0].isEmpty() || parts[1].length != 2) return null
    return h * 60 + m
}

private fun dayLabelRes(day: Int): Int = when (day) {
    1 -> R.string.day_mon
    2 -> R.string.day_tue
    3 -> R.string.day_wed
    4 -> R.string.day_thu
    5 -> R.string.day_fri
    6 -> R.string.day_sat
    7 -> R.string.day_sun
    else -> R.string.day_mon
}
