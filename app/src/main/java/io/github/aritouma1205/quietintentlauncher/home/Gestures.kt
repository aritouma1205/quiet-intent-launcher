package io.github.aritouma1205.quietintentlauncher.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal const val MIN_VERTICAL_RATIO = 1.5f
internal val MIN_SWIPE_DISTANCE = 48.dp

/**
 * Up-swipe predicate (design 5): travel exceeds the larger of touch slop and
 * 48dp upward, and is clearly vertical (>= 1.5x the horizontal component),
 * so diagonal input never fires it.
 */
internal fun isUpSwipe(dx: Float, dy: Float, minDistancePx: Float): Boolean =
    dy <= -minDistancePx && -dy >= MIN_VERTICAL_RATIO * abs(dx)

suspend fun PointerInputScope.detectUpSwipe(onSwipe: () -> Unit) {
    val minDistancePx =
        MIN_SWIPE_DISTANCE.toPx().coerceAtLeast(viewConfiguration.touchSlop)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var drag = Offset.Zero
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            drag += change.positionChange()
            if (isUpSwipe(drag.x, drag.y, minDistancePx)) {
                change.consume()
                onSwipe()
                break
            }
        }
    }
}
