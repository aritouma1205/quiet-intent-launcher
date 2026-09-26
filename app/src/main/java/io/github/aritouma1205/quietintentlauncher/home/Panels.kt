package io.github.aritouma1205.quietintentlauncher.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.calendar.EventSection
import io.github.aritouma1205.quietintentlauncher.calendar.TodayEvent
import io.github.aritouma1205.quietintentlauncher.context.ContextRule
import io.github.aritouma1205.quietintentlauncher.settings.DerivedOp
import io.github.aritouma1205.quietintentlauncher.settings.DoAction
import io.github.aritouma1205.quietintentlauncher.settings.GlancePosition
import io.github.aritouma1205.quietintentlauncher.settings.ToolItem
import io.github.aritouma1205.quietintentlauncher.today.TodayUi
import io.github.aritouma1205.quietintentlauncher.ui.imageVector
import android.text.format.DateFormat
import java.time.Instant
import java.util.Date
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** Panel background: dark screen at the design's initial 72% (design 12). */
private val PanelScrim = Color.Black.copy(alpha = 0.72f)

// Hairline outline so the scrim edge reads as a boundary without turning
// the panel into a card (design 12).
private val PanelHairline = Color.White.copy(alpha = 0.08f)

/**
 * Sliding Reveal panel with an outside scrim (design 3, 4.3).
 *
 * [progress] drives a finger-following offset with no time-based easing while
 * dragging; callers animate it for the settle. Tapping the outside area or
 * dragging the header outward by 0.20W closes the whole panel.
 */
@Composable
fun PanelLayer(
    side: EdgeSide,
    progress: Float,
    panelWidthPx: Float,
    paneTitle: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val panelWidthDp = with(density) { panelWidthPx.toDp() }
    val direction = if (side == EdgeSide.Right) 1f else -1f
    Box(Modifier.fillMaxSize()) {
        // Outside area: tapping closes the panel; the scrim is invisible so
        // the wallpaper stays readable.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.close),
                    onClick = onClose,
                ),
        )
        Box(
            Modifier
                .fillMaxHeight()
                .width(panelWidthDp)
                .align(if (side == EdgeSide.Right) Alignment.CenterEnd else Alignment.CenterStart)
                .offset {
                    IntOffset(
                        x = ((1f - progress) * panelWidthPx * direction).roundToInt(),
                        y = 0,
                    )
                }
                .background(PanelScrim)
                .border(1.dp, PanelHairline)
                .semantics { this.paneTitle = paneTitle }
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            content()
        }
    }
}

/**
 * Header row for a panel: the pane title plus an explicit close affordance
 * (design 12: switch access must reach a close path). Dragging the header
 * outward by >= 0.20W closes the panel (design 4.3).
 */
@Composable
private fun PanelHeader(
    title: String,
    side: EdgeSide,
    panelWidthPx: Float,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val slopPx = with(density) { 8.dp.toPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(side, panelWidthPx) {
                val closeDistance = EdgeDragStateMachine.OPEN_THRESHOLD * panelWidthPx
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var outward = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: break
                        if (!change.pressed) break
                        val dx = change.positionChange().x
                        val outwardDelta =
                            if (side == EdgeSide.Right) dx else -dx
                        outward += outwardDelta
                        if (outwardDelta > 0) change.consume()
                        if (outward >= closeDistance.coerceAtLeast(slopPx)) {
                            onClose()
                            break
                        }
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.close))
        }
    }
}

/**
 * DO panel (right, design 6). Renders the configured actions in stored order
 * — auxiliary icon + name (the primary element) + target status — and the
 * TOOLS area. Tapping launches through the shared path; a long press shows
 * the derived ops and the edit entry.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DoPanel(
    rows: List<ActionRow>,
    contextRows: List<ContextRow>,
    toolRows: List<ToolRow>,
    toolsExpanded: Boolean,
    panelWidthPx: Float,
    onExpandTools: () -> Unit,
    onCollapseTools: () -> Unit,
    onClose: () -> Unit,
    onActionTap: (DoAction) -> Unit,
    onActionEdit: (DoAction) -> Unit,
    onDerivedOp: (DoAction, DerivedOp) -> Unit,
    onContextTap: (ContextRow) -> Unit,
    onContextEdit: (ContextRow) -> Unit,
    onToolTap: (ToolItem) -> Unit,
) {
    val scrollState = rememberScrollState()
    var toolsHeadingY by remember { mutableFloatStateOf(0f) }
    var menuAction by remember { mutableStateOf<DoAction?>(null) }
    var menuSlot by remember { mutableStateOf<ContextRow?>(null) }
    var reasonRow by remember { mutableStateOf<ContextRow?>(null) }

    LaunchedEffect(toolsExpanded) {
        if (toolsExpanded) {
            // The heading is composed with the same state change; wait for
            // its first layout before moving the viewport (design 4.3).
            snapshotFlow { toolsHeadingY }.first { it > 0f }
            scrollState.animateScrollTo(toolsHeadingY.toInt())
        }
    }

    Column(Modifier.fillMaxSize()) {
        PanelHeader(
            title = stringResource(R.string.panel_do_title),
            side = EdgeSide.Right,
            panelWidthPx = panelWidthPx,
            onClose = onClose,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
        ) {
            rows.forEach { row ->
                ActionRowItem(
                    row = row,
                    onTap = { onActionTap(row.action) },
                    onLongPress = { menuAction = row.action },
                    onChangeTarget = { onActionEdit(row.action) },
                )
            }

            // Context Slots sit below the normal action list and above the
            // TOOLS button (design 10). The rows are frozen while the panel
            // is open — the VM re-evaluates on open.
            if (contextRows.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.context_now_heading),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                contextRows.forEach { row ->
                    ContextRowItem(
                        row = row,
                        onTap = { onContextTap(row) },
                        onLongPress = { menuSlot = row },
                    )
                }
            }

            OutlinedButton(
                onClick = onExpandTools,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                Text(stringResource(R.string.tools_expand))
            }

            if (toolsExpanded) {
                Text(
                    text = stringResource(R.string.tools_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .onGloballyPositioned {
                            toolsHeadingY = it.positionInParent().y
                        }
                        .padding(top = 8.dp, bottom = 8.dp),
                )
                TextButton(
                    onClick = onCollapseTools,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.tools_back_to_do))
                }
                // Configured order, visible tools only (design 7). Each row
                // shows its live state; blocked rows explain why and the tap
                // routes to the right fallback via the ViewModel.
                toolRows.forEach { row ->
                    ToolRowItem(
                        row = row,
                        onTap = { onToolTap(row.tool) },
                    )
                }
            }
        }
    }

    // Long-press menu (design 6): the action's derived ops and the edit
    // entry. An op launches through the same shared path as a tap.
    menuAction?.let { action ->
        AlertDialog(
            onDismissRequest = { menuAction = null },
            title = { Text(action.name) },
            text = {
                Column {
                    action.derivedOps.forEach { op ->
                        TextButton(
                            onClick = {
                                menuAction = null
                                onDerivedOp(action, op)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(op.label.ifBlank { action.name })
                        }
                    }
                    TextButton(
                        onClick = {
                            menuAction = null
                            onActionEdit(action)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.do_edit_action))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuAction = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    // Context slot long-press (design 10): 条件を編集 / 表示理由.
    menuSlot?.let { row ->
        AlertDialog(
            onDismissRequest = { menuSlot = null },
            title = {
                Text(
                    row.slotLabel.ifBlank { row.action.name },
                )
            },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            menuSlot = null
                            onContextEdit(row)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.context_slot_edit))
                    }
                    TextButton(
                        onClick = {
                            reasonRow = row
                            menuSlot = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.context_slot_reason))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuSlot = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    // 「表示理由」: which rule (or the default) produced this row.
    reasonRow?.let { row ->
        AlertDialog(
            onDismissRequest = { reasonRow = null },
            title = { Text(stringResource(R.string.context_reason_title)) },
            text = { Text(contextReason(row.matchedRule)) },
            confirmButton = {
                TextButton(onClick = { reasonRow = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

/**
 * One TOOLS row (design 7): name + live status. Blocked rows explain the
 * reason — 起動先 未設定 / 削除済み / ハンドラなし / ライトなし / カメラ
 * 使用中 / サービス無効 / スイッチOFF — and the tap routes through the
 * ViewModel to the matching fallback (editor, settings or a message).
 */
@Composable
private fun ToolRowItem(
    row: ToolRow,
    onTap: () -> Unit,
) {
    val name = stringResource(row.tool.labelRes)
    val statusText = when (val status = row.status) {
        is ToolStatus.Ready -> status.detail
        ToolStatus.LightOn -> stringResource(R.string.tool_state_on)
        ToolStatus.LightOff -> stringResource(R.string.tool_state_off)
        is ToolStatus.Blocked -> stringResource(
            when (status.reason) {
                ToolUnavailable.Unset -> R.string.do_target_unset
                ToolUnavailable.TargetGone -> R.string.tool_target_missing
                ToolUnavailable.NoTimerHandler -> R.string.tool_no_timer_handler
                ToolUnavailable.NoFlash -> R.string.tool_no_flash
                ToolUnavailable.PermissionMissing ->
                    R.string.tool_light_needs_permission
                ToolUnavailable.Busy -> R.string.tool_light_busy
                ToolUnavailable.ServiceInactive -> R.string.tool_service_off
                ToolUnavailable.SwitchOff -> R.string.tool_switch_off_hint
            },
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                // TalkBack/Switch Access hear the state together with the
                // name, so 点灯中/未設定 is never visually implicit
                // (design 12).
                onClickLabel = if (statusText != null) {
                    "$name・$statusText"
                } else {
                    name
                },
                onClick = onTap,
            )
            .padding(vertical = 8.dp),
    ) {
        Column {
            Text(
                text = name,
                fontSize = 16.sp,
                style = MaterialTheme.typography.titleMedium,
            )
            if (statusText != null) {
                StatusLine(text = statusText)
            }
        }
    }
}

/**
 * One Context Slot row (design 10): icon + the resolved action's name, with
 * the slot label — or, unlabeled, a short reason — as the status line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContextRowItem(
    row: ContextRow,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .combinedClickable(
                onClickLabel = row.action.name,
                onClick = onTap,
                onLongClickLabel = stringResource(R.string.do_action_menu),
                onLongClick = onLongPress,
            )
            .padding(vertical = 8.dp),
    ) {
        Icon(
            imageVector = row.action.icon.imageVector(),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                text = row.action.name,
                fontSize = 20.sp,
                style = MaterialTheme.typography.titleMedium,
            )
            StatusLine(
                text = row.slotLabel.ifBlank { contextReason(row.matchedRule) },
            )
        }
    }
}

/**
 * Human-readable reason for a context row: the matched rule as
 * 「曜日・時刻帯」 or 「既定の行動」 when the slot fell back to its default.
 */
@Composable
private fun contextReason(rule: ContextRule?): String {
    if (rule == null) return stringResource(R.string.context_reason_default)
    val dayPart = if (rule.daysOfWeek.isEmpty()) {
        stringResource(R.string.context_reason_everyday)
    } else {
        rule.daysOfWeek.sorted()
            .map { stringResource(dayLabelRes(it)) }
            .joinToString("・")
    }
    val timed = rule.startMinuteOfDay != null && rule.endMinuteOfDay != null
    return if (timed) {
        stringResource(
            R.string.context_reason_rule,
            dayPart,
            "${formatMinute(rule.startMinuteOfDay)}〜" +
                formatMinute(rule.endMinuteOfDay),
        )
    } else if (rule.daysOfWeek.isEmpty()) {
        stringResource(R.string.context_reason_allday)
    } else {
        stringResource(
            R.string.context_reason_rule,
            dayPart,
            stringResource(R.string.context_reason_allday),
        )
    }
}

private fun formatMinute(minuteOfDay: Int): String =
    "${minuteOfDay / 60}:${"%02d".format(minuteOfDay % 60)}"

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

/**
 * One action row (design 6): auxiliary icon + name at 20sp, plus a status
 * line — the target name when set, 起動先 未設定 when unset, or an
 * explanation with a 変更する affordance when the target is gone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionRowItem(
    row: ActionRow,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onChangeTarget: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .combinedClickable(
                onClickLabel = row.action.name,
                onClick = onTap,
                onLongClickLabel = stringResource(R.string.do_action_menu),
                onLongClick = onLongPress,
            )
            .padding(vertical = 8.dp),
    ) {
        Icon(
            imageVector = row.action.icon.imageVector(),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                text = row.action.name,
                fontSize = 20.sp,
                style = MaterialTheme.typography.titleMedium,
            )
            when (val status = row.status) {
                ActionStatus.Unset -> StatusLine(
                    text = stringResource(R.string.do_target_unset),
                )
                is ActionStatus.Available -> StatusLine(text = status.targetLabel)
                is ActionStatus.Unavailable -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusLine(
                        text = stringResource(
                            when (status.reason) {
                                UnavailableReason.AppGone ->
                                    R.string.do_target_unavailable_app
                                UnavailableReason.ShortcutGone ->
                                    R.string.do_target_unavailable_shortcut
                                UnavailableReason.NoHandler ->
                                    R.string.do_target_no_handler
                            },
                        ),
                    )
                    TextButton(onClick = onChangeTarget) {
                        Text(stringResource(R.string.do_change_target))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.6f),
    )
}

/**
 * TODAY panel (left, design 8.1). Order: date/weekday, weather, the next
 * events, battery. Blocks without data are omitted entirely — the panel
 * never leaves empty placeholders.
 */
@Composable
fun TodayPanel(
    state: TodayUi,
    panelWidthPx: Float,
    onClose: () -> Unit,
    onEventTap: (TodayEvent) -> Unit,
) {
    val context = LocalContext.current
    // Keyed to the default zone so a TIMEZONE_CHANGED refresh rebuilds the
    // formatter instead of showing stale-zone times (design 8.1).
    val timeFormat = remember(TimeZone.getDefault()) {
        DateFormat.getTimeFormat(context)
    }
    Column(Modifier.fillMaxSize()) {
        PanelHeader(
            title = stringResource(R.string.panel_today_title),
            side = EdgeSide.Left,
            panelWidthPx = panelWidthPx,
            onClose = onClose,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    text = state.dateText,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = state.weekdayText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.weather?.let { weather ->
                Column {
                    Text(
                        text = stringResource(
                            R.string.today_weather_line,
                            weather.temperatureCelsius.roundToInt(),
                            stringResource(weather.labelRes),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.weather_region_provider,
                            weather.regionName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    if (!weather.fresh) {
                        Text(
                            text = stringResource(
                                R.string.weather_updated_at,
                                timeFormat.format(Date(weather.fetchedAtWallMs)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            if (state.events.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.events.forEach { event ->
                        EventRowItem(
                            event = event,
                            nowMs = state.nowMs,
                            onClick = { onEventTap(event) },
                        )
                    }
                }
            }

            state.batteryPercent?.let { percent ->
                Text(
                    text = if (state.charging) {
                        stringResource(R.string.today_battery_charging, percent)
                    } else {
                        stringResource(R.string.today_battery, percent)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/**
 * One event row (design 8.1): a small section label (開催中 / 時刻 /
 * 明日+時刻 / 終日) above the title, tap opens it in a calendar app.
 * An empty title falls back to 「予定」.
 */
@Composable
private fun EventRowItem(
    event: TodayEvent,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val timeFormat = remember(TimeZone.getDefault()) {
        DateFormat.getTimeFormat(context)
    }
    val zone = TimeZone.getDefault().toZoneId()
    val title = event.title.ifBlank { stringResource(R.string.event_no_title) }
    val label = when (event.section) {
        EventSection.Ongoing -> stringResource(
            R.string.event_ongoing,
            timeFormat.format(Date(event.beginMs)),
            timeFormat.format(Date(event.endMs)),
        )
        EventSection.Upcoming -> {
            val tomorrow =
                Instant.ofEpochMilli(event.beginMs).atZone(zone).toLocalDate() >
                    Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            if (tomorrow) {
                stringResource(
                    R.string.event_tomorrow,
                    timeFormat.format(Date(event.beginMs)),
                )
            } else {
                timeFormat.format(Date(event.beginMs))
            }
        }
        EventSection.AllDay -> stringResource(R.string.event_allday)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .semantics { contentDescription = "$label $title" },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * GLANCE strip over Quiet (design 8.3): time, date, fresh weather and
 * battery at the configured position. Non-interactive — touches pass
 * through to the bars and the free area.
 */
@Composable
fun GlanceOverlay(state: TodayUi, position: GlancePosition) {
    Box(Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(
                    when (position) {
                        GlancePosition.Top -> Alignment.TopCenter
                        GlancePosition.Center -> Alignment.Center
                        GlancePosition.Bottom -> Alignment.BottomCenter
                    },
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(vertical = 48.dp)
                .semantics { paneTitle = "GLANCE" },
        ) {
            Text(
                text = state.timeText,
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = "${state.weekdayText} ${state.dateText}",
                style = MaterialTheme.typography.bodyMedium,
            )
            // GLANCE omits stale weather entirely (design 8.2).
            state.weather?.takeIf { it.fresh }?.let { weather ->
                Text(
                    text = stringResource(
                        R.string.today_weather_line,
                        weather.temperatureCelsius.roundToInt(),
                        stringResource(weather.labelRes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.batteryPercent?.let { percent ->
                Text(
                    text = if (state.charging) {
                        stringResource(R.string.today_battery_charging, percent)
                    } else {
                        stringResource(R.string.today_battery, percent)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
