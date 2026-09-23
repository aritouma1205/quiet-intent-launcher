package io.github.aritouma1205.quietintentlauncher.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal val MIN_SWIPE_DISTANCE = 48.dp

/** Free-area gestures on the wallpaper (design 5). */
enum class FreeAreaEvent {
    Tap,
    DoubleTap,
    LongPress,
    SwipeUp,
    SwipeDown,
}

/**
 * Per-gesture suppression shared by the two free-area detectors. Once a
 * long-press has fired, a late vertical move of the same input must not turn
 * into a swipe (design 5: one input never produces two actions).
 */
class FreeAreaGate {
    var longPressActive: Boolean = false
}

/**
 * Vertical-swipe classification of one free-area gesture (design 5), pure
 * and testable.
 *
 * - A swipe needs at least 48dp of travel and |dy| >= 1.5 * |dx| on the
 *   cumulative displacement; diagonal input stays pending until a direction
 *   is decided.
 * - A horizontal-dominant move locks the gesture out — the free area assigns
 *   nothing to horizontal swipes and the same input is never reinterpreted.
 * - A second pointer or a cancel locks the gesture out entirely.
 */
class FreeAreaSwipeTracker(
    private val touchSlop: Float,
    private val minSwipeDistance: Float,
) {
    private var swipeArmed = false
    private var lockedOut = false
    private var fired = false

    fun onDown(swipeAllowed: Boolean) {
        swipeArmed = swipeAllowed
        lockedOut = false
        fired = false
    }

    /**
     * Feed the cumulative displacement since pointer-down. Emits
     * [FreeAreaEvent.SwipeUp]/[FreeAreaEvent.SwipeDown] exactly once when the
     * swipe qualifies.
     */
    fun onMove(totalDx: Float, totalDy: Float): FreeAreaEvent? {
        if (fired || lockedOut) return null
        val adx = abs(totalDx)
        val ady = abs(totalDy)
        if (
            swipeArmed && ady >= minSwipeDistance &&
            ady >= EdgeDragStateMachine.MIN_DOMINANT_RATIO * adx
        ) {
            fired = true
            return if (totalDy < 0) FreeAreaEvent.SwipeUp else FreeAreaEvent.SwipeDown
        }
        if (adx > touchSlop && adx >= EdgeDragStateMachine.MIN_DOMINANT_RATIO * ady) {
            lockedOut = true
        }
        return null
    }

    fun onPointerCountChanged(count: Int) {
        if (count > 1) lockedOut = true
    }

    fun onCancel() {
        lockedOut = true
    }
}

/**
 * Attaches the free-area gestures to this surface (design 5).
 *
 * Tap / long-press / double-tap come from [detectTapGestures], which already
 * implements the slop, timeout and multi-pointer rules; vertical swipes are
 * classified by [FreeAreaSwipeTracker] on accumulated travel.
 *
 * [isSwipeStartAllowed] gates swipes on the down position — callers exclude
 * the bottom system gesture area so an up-swipe never fights the OS home
 * gesture (design 4.1).
 *
 * When [doubleTapEnabled] is false a tap fires immediately; when true the
 * first tap is held for the double-tap window so a second tap becomes
 * [FreeAreaEvent.DoubleTap] instead of two single taps.
 */
fun Modifier.freeAreaGestures(
    gate: FreeAreaGate,
    touchSlopPx: Float,
    isSwipeStartAllowed: (Offset) -> Boolean,
    doubleTapEnabled: Boolean,
    onHoldChange: (Boolean) -> Unit = {},
    onEvent: (FreeAreaEvent) -> Unit,
): Modifier =
    // Gesture counter-reset and hold tracking: a new gesture clears the
    // suppression the previous gesture may have left behind, and the surface
    // reports "held" for as long as a finger is down (design 8.3: touching
    // pauses the GLANCE auto-dismiss until release).
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            gate.longPressActive = false
            onHoldChange(true)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                }
            } finally {
                onHoldChange(false)
            }
        }
    }
        .pointerInput(doubleTapEnabled) {
            detectTapGestures(
                onTap = { onEvent(FreeAreaEvent.Tap) },
                onLongPress = {
                    gate.longPressActive = true
                    onEvent(FreeAreaEvent.LongPress)
                },
                onDoubleTap = if (doubleTapEnabled) {
                    { onEvent(FreeAreaEvent.DoubleTap) }
                } else {
                    null
                },
            )
        }
        .pointerInput(touchSlopPx) {
            detectVerticalSwipe(
                touchSlopPx = touchSlopPx,
                isSwipeStartAllowed = isSwipeStartAllowed,
                gate = gate,
                onEvent = onEvent,
            )
        }

private suspend fun PointerInputScope.detectVerticalSwipe(
    touchSlopPx: Float,
    isSwipeStartAllowed: (Offset) -> Boolean,
    gate: FreeAreaGate,
    onEvent: (FreeAreaEvent) -> Unit,
) {
    val tracker = FreeAreaSwipeTracker(
        touchSlop = touchSlopPx,
        minSwipeDistance = MIN_SWIPE_DISTANCE.toPx().coerceAtLeast(touchSlopPx),
    )
    var totalDx = 0f
    var totalDy = 0f
    detectVerticalDragGestures(
        onDragStart = { offset ->
            totalDx = 0f
            totalDy = 0f
            tracker.onDown(isSwipeStartAllowed(offset))
        },
        onDragEnd = {},
        onDragCancel = { tracker.onCancel() },
        onVerticalDrag = { change, _ ->
            totalDx += change.positionChange().x
            totalDy += change.positionChange().y
            if (!gate.longPressActive) {
                tracker.onMove(totalDx, totalDy)?.let {
                    change.consume()
                    onEvent(it)
                }
            }
        },
    )
}
