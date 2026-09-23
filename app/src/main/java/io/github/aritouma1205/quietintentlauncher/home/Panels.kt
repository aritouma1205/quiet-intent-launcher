package io.github.aritouma1205.quietintentlauncher.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.today.TodayInfo
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** Panel background: dark screen at the design's initial 72% (design 12). */
private val PanelScrim = Color.Black.copy(alpha = 0.72f)

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
 * DO panel (right). This stage renders the fixed action list and the TOOLS
 * area; assigning launch targets and running tools are later stages, so the
 * rows explain that instead of pretending to work.
 */
@Composable
fun DoPanel(
    toolsExpanded: Boolean,
    panelWidthPx: Float,
    onExpandTools: () -> Unit,
    onCollapseTools: () -> Unit,
    onClose: () -> Unit,
    onUnavailable: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var toolsHeadingY by remember { mutableFloatStateOf(0f) }

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
            DefaultAction.entries.forEach { action ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onUnavailable)
                        .padding(vertical = 14.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(action.labelRes),
                            fontSize = 20.sp,
                        )
                        Text(
                            text = stringResource(R.string.do_target_unset),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
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
                ToolItem.entries.forEach { tool ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onUnavailable)
                            .padding(vertical = 14.dp),
                    ) {
                        Text(
                            text = stringResource(tool.labelRes),
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

/** TODAY panel (left). Date / weekday / battery only; weather and events
 * are optional features of a later stage. */
@Composable
fun TodayPanel(
    info: TodayInfo,
    panelWidthPx: Float,
    onClose: () -> Unit,
) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = info.weekdayText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = info.dateText,
                style = MaterialTheme.typography.headlineMedium,
            )
            info.batteryPercent?.let { percent ->
                Text(
                    text = if (info.charging == true) {
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

/** GLANCE strip over Quiet (design 8.3): time, date and battery, top-centre.
 * Non-interactive: touches pass through to the bars and free area. */
@Composable
fun GlanceOverlay(info: TodayInfo) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 48.dp)
            .semantics { paneTitle = "GLANCE" },
    ) {
        Text(
            text = info.timeText,
            style = MaterialTheme.typography.displayMedium,
        )
        Text(
            text = "${info.weekdayText} ${info.dateText}",
            style = MaterialTheme.typography.bodyMedium,
        )
        info.batteryPercent?.let { percent ->
            Text(
                text = if (info.charging == true) {
                    stringResource(R.string.today_battery_charging, percent)
                } else {
                    stringResource(R.string.today_battery, percent)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
