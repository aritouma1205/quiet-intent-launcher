package io.github.aritouma1205.quietintentlauncher.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.aritouma1205.quietintentlauncher.settings.EdgeBarColor
import io.github.aritouma1205.quietintentlauncher.settings.EdgeBarSettings
import io.github.aritouma1205.quietintentlauncher.settings.ToolsSettings
import kotlin.math.roundToInt

/**
 * One visible edge bar (design 4.1). The drawn rectangle is the hit area —
 * no invisible margin is added and no minimum touch target expansion is
 * applied, so [EdgeBarSettings] controls both appearance and detection.
 *
 * [Modifier.systemGestureExclusion] is limited to exactly this bar's bounds:
 * the OS back gesture is only suppressed while the bar is shown.
 */
@Composable
fun EdgeBar(
    side: EdgeSide,
    settings: EdgeBarSettings,
    containerWidthPx: Float,
    usableTopPx: Float,
    usableHeightPx: Float,
    insetSidePx: Float,
    panelWidthPx: Float,
    deepPullEnabled: Boolean,
    toolsThreshold: Float,
    hapticsEnabled: Boolean,
    openLabel: String,
    onEvent: (EdgeSide, EdgeDragEvent) -> Unit,
    onHaptic: () -> Unit,
) {
    val density = LocalDensity.current
    val lengthPx = with(density) { settings.lengthDp.dp.toPx() }
    val thicknessPx = with(density) { settings.thicknessDp.dp.toPx() }

    val barLengthPx = lengthPx.coerceAtMost(usableHeightPx)
    val centerY = usableTopPx +
        settings.verticalBias.coerceIn(0f, 1f) * (usableHeightPx - barLengthPx)
    val xPx = if (side == EdgeSide.Left) {
        insetSidePx
    } else {
        containerWidthPx - insetSidePx - thicknessPx
    }

    val color = when (settings.color) {
        EdgeBarColor.White -> Color.White
        EdgeBarColor.Black -> Color.Black
    }

    Box(
        Modifier
            .offset { IntOffset(xPx.roundToInt(), centerY.roundToInt()) }
            .size(
                width = settings.thicknessDp.dp,
                height = with(density) { barLengthPx.toDp() },
            )
            .systemGestureExclusion()
            .semantics {
                contentDescription = openLabel
                onClick(label = openLabel) {
                    onEvent(side, EdgeDragEvent.Tapped)
                    true
                }
            }
            .pointerInput(
                side,
                panelWidthPx,
                deepPullEnabled,
                toolsThreshold,
            ) {
                detectEdgeDrag(
                    side = side,
                    panelWidthPx = panelWidthPx,
                    deepPullEnabled = deepPullEnabled,
                    toolsThreshold = toolsThreshold,
                    toolsHysteresis = ToolsSettings.HYSTERESIS_FRACTION,
                    hapticsEnabled = hapticsEnabled,
                    onEvent = { event -> onEvent(side, event) },
                    onHaptic = onHaptic,
                )
            }
            .background(color.copy(alpha = settings.opacity), RoundedCornerShape(50)),
    )
}

/**
 * Pointer loop around [EdgeDragStateMachine]: one gesture is tracked from
 * pointer-down; the machine decides arming, direction, progress, the open
 * threshold and the deep-pull hysteresis.
 */
private suspend fun PointerInputScope.detectEdgeDrag(
    side: EdgeSide,
    panelWidthPx: Float,
    deepPullEnabled: Boolean,
    toolsThreshold: Float,
    toolsHysteresis: Float,
    hapticsEnabled: Boolean,
    onEvent: (EdgeDragEvent) -> Unit,
    onHaptic: () -> Unit,
) {
    val machine = EdgeDragStateMachine(
        side = side,
        touchSlop = viewConfiguration.touchSlop,
        panelWidth = panelWidthPx,
        deepPullEnabled = deepPullEnabled,
        toolsThreshold = toolsThreshold,
        toolsHysteresis = toolsHysteresis,
    )
    fun dispatch(event: EdgeDragEvent) {
        if (event is EdgeDragEvent.ToolsExpanded && hapticsEnabled) onHaptic()
        onEvent(event)
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // The hit test already confined the down to the bar rectangle.
        machine.onDown(startedOnBar = true)
        down.consume()
        var totalDx = 0f
        var totalDy = 0f
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) {
                machine.onPointerCountChanged(2)?.let(::dispatch)
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) {
                machine.onUp()?.let(::dispatch)
                break
            }
            totalDx += change.positionChange().x
            totalDy += change.positionChange().y
            if (change.positionChanged()) change.consume()
            machine.onMove(totalDx, totalDy)?.let(::dispatch)
        }
    }
}
