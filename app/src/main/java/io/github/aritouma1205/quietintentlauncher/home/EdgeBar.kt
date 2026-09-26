package io.github.aritouma1205.quietintentlauncher.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
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
import androidx.compose.ui.util.lerp
import io.github.aritouma1205.quietintentlauncher.settings.EdgeBarColor
import io.github.aritouma1205.quietintentlauncher.settings.EdgeBarSettings
import io.github.aritouma1205.quietintentlauncher.settings.ToolsSettings
import kotlin.math.roundToInt

/**
 * Draw state while a finger is on the bar (design 12): set on pointer-down,
 * fed by the same drag events the panel consumes, cleared when the touch
 * ends. Visuals only — detection never reads it.
 */
data class EdgeBarVisual(
    val pressed: Boolean = false,
    val progress: Float = 0f,
    val toolsExpanded: Boolean = false,
)

/** Test seam: observes the bar's draw state without touching gesture code. */
internal var edgeBarVisualProbe: ((EdgeBarVisual) -> Unit)? = null

private const val PRESS_TWEEN_MS = 120

/**
 * One visible edge bar (design 4.1). The drawn rectangle is the hit area —
 * no invisible margin is added and no minimum touch target expansion is
 * applied, so [EdgeBarSettings] controls both appearance and detection.
 * Press/drag feedback scales the paint only: the hit rectangle and every
 * threshold stay exactly as configured.
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
    multiPointerActive: (PointerId) -> Boolean,
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

    val visual = remember { mutableStateOf(EdgeBarVisual()) }
    // Only the press/release moments tween; drag visuals follow the finger
    // directly (design 12).
    val pressBlend by animateFloatAsState(
        targetValue = if (visual.value.pressed) 1f else 0f,
        animationSpec = tween(durationMillis = PRESS_TWEEN_MS),
        label = "edgeBarPress",
    )
    val toolsBlend by animateFloatAsState(
        targetValue = if (visual.value.toolsExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = PRESS_TWEEN_MS),
        label = "edgeBarTools",
    )
    // Drag amount saturates at the open threshold; past it the deep-pull
    // emphasis takes over.
    val armProgress = (visual.value.progress / EdgeDragStateMachine.OPEN_THRESHOLD)
        .coerceIn(0f, 1f)
    val barColor = lerp(color, MaterialTheme.colorScheme.primary, toolsBlend)

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
                    visual = visual,
                    multiPointerActive = multiPointerActive,
                    onEvent = { event -> onEvent(side, event) },
                    onHaptic = onHaptic,
                )
            }
            .graphicsLayer {
                transformOrigin = TransformOrigin(
                    if (side == EdgeSide.Right) 1f else 0f,
                    0.5f,
                )
                scaleX = 1f + 0.5f * pressBlend + 0.2f * toolsBlend
                scaleY = 1f + 0.2f * armProgress
            }
            .shadow(
                elevation = 12.dp * toolsBlend,
                shape = RoundedCornerShape(50),
                ambientColor = MaterialTheme.colorScheme.primary,
                spotColor = MaterialTheme.colorScheme.primary,
            )
            .background(
                barColor.copy(alpha = lerp(settings.opacity, 1f, pressBlend)),
                RoundedCornerShape(50),
            ),
    )
}

/**
 * Pointer loop around [EdgeDragStateMachine]: one gesture is tracked from
 * pointer-down; the machine decides arming, direction, progress, the open
 * threshold and the deep-pull hysteresis. [visual] is a write-only tap of
 * the same events so the bar can paint pressed/dragging/armed — the
 * detection rules are unchanged.
 */
private suspend fun PointerInputScope.detectEdgeDrag(
    side: EdgeSide,
    panelWidthPx: Float,
    deepPullEnabled: Boolean,
    toolsThreshold: Float,
    toolsHysteresis: Float,
    hapticsEnabled: Boolean,
    visual: MutableState<EdgeBarVisual>,
    multiPointerActive: (PointerId) -> Boolean,
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
    fun report(block: (EdgeBarVisual) -> EdgeBarVisual) {
        visual.value = block(visual.value)
        edgeBarVisualProbe?.invoke(visual.value)
    }
    fun dispatch(event: EdgeDragEvent) {
        if (event is EdgeDragEvent.ToolsExpanded && hapticsEnabled) onHaptic()
        when (event) {
            is EdgeDragEvent.Progress ->
                report { it.copy(progress = event.fraction) }
            is EdgeDragEvent.ToolsExpanded ->
                report { it.copy(progress = event.fraction, toolsExpanded = true) }
            is EdgeDragEvent.ToolsCollapsed ->
                report { it.copy(progress = event.fraction, toolsExpanded = false) }
            else -> {}
        }
        onEvent(event)
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // The hit test already confined the down to the bar rectangle.
        machine.onDown(startedOnBar = true)
        report { EdgeBarVisual(pressed = true) }
        down.consume()
        var totalDx = 0f
        var totalDy = 0f
        try {
            // Secondary contacts on this bar are tracked from where they
            // landed: one only counts as a second input once it travels past
            // touch slop. A palm resting at the edge keeps the drag alive.
            val otherDownAt = mutableMapOf<PointerId, Offset>()
            while (true) {
                val event = awaitPointerEvent()
                // A second finger is often outside this bar's bounds and never
                // reaches this event stream; the shared latch covers it. Either
                // way it only counts once it moves — a resting contact is not
                // a second input.
                var secondActive = multiPointerActive(down.id)
                for (other in event.changes) {
                    if (other.id == down.id) continue
                    if (!other.pressed) {
                        otherDownAt.remove(other.id)
                        continue
                    }
                    val origin = otherDownAt.getOrPut(other.id) { other.position }
                    if (
                        (other.position - origin).getDistance() >
                            viewConfiguration.touchSlop
                    ) {
                        secondActive = true
                    }
                }
                if (secondActive) {
                    machine.onPointerCountChanged(2)?.let(::dispatch)
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id }
                when {
                    // The tracked pointer vanished without a release: the OS
                    // interrupted the input stream — treat as cancel.
                    change == null -> {
                        machine.onCancel()?.let(::dispatch)
                        break
                    }
                    !change.pressed -> {
                        // A synthetic consumed release is the OS cancelling the
                        // stream (system gesture steal, focus loss); a real
                        // finger-up arrives unconsumed as a Release event.
                        if (event.type == PointerEventType.Release && !change.isConsumed) {
                            machine.onUp()?.let(::dispatch)
                        } else {
                            machine.onCancel()?.let(::dispatch)
                        }
                        break
                    }
                    else -> {
                        totalDx += change.positionChange().x
                        totalDy += change.positionChange().y
                        if (change.positionChanged()) change.consume()
                        machine.onMove(totalDx, totalDy)?.let(::dispatch)
                    }
                }
            }
        } finally {
            report { EdgeBarVisual() }
        }
    }
}
