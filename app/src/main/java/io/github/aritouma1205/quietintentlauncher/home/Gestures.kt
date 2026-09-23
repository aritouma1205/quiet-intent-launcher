package io.github.aritouma1205.quietintentlauncher.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * Per-gesture suppression shared by the free-area detectors. Once a
 * long-press has fired, a late vertical move of the same input must not turn
 * into a swipe (design 5: one input never produces two actions).
 */
class FreeAreaGate {
    var longPressActive: Boolean = false
}

/**
 * Whole-input classification of one free-area gesture (design 5), pure and
 * testable. The caller feeds the pointer-down position, the cumulative
 * displacement since that down, every pointer-count change and the final
 * release/cancel, so decisions are always made on the same input — never on
 * a later drag start or a different coordinate.
 *
 * - A swipe needs at least 48dp of cumulative travel and |dy| >= 1.5 * |dx|;
 *   diagonal input stays pending until a direction emerges. A horizontal
 *   first leg keeps accumulating, so bending upward late does not become a
 *   swipe unless the totals still qualify.
 * - Horizontal-dominant travel, a second pointer or a cancel locks the
 *   gesture out entirely — nothing is assigned to horizontal swipes.
 * - Tap / long-press / double-tap die as soon as displacement passes touch
 *   slop in any direction ("移動開始後は発火しない"); a release after
 *   moved-out input is not a tap.
 */
class FreeAreaTracker(
    private val touchSlop: Float,
    private val minSwipeDistance: Float,
) {
    private var swipeArmed = false
    private var movedBeyondSlop = false
    private var lockedOut = false
    private var done = false

    /** Tap, long-press and double-tap are all still possible for this input. */
    val isTapEligible: Boolean
        get() = !movedBeyondSlop && !lockedOut && !done

    fun onDown(swipeAllowed: Boolean) {
        swipeArmed = swipeAllowed
        movedBeyondSlop = false
        lockedOut = false
        done = false
    }

    /**
     * Feed the cumulative displacement since pointer-down. Emits
     * [FreeAreaEvent.SwipeUp]/[FreeAreaEvent.SwipeDown] exactly once when the
     * swipe qualifies.
     */
    fun onMove(totalDx: Float, totalDy: Float): FreeAreaEvent? {
        val adx = abs(totalDx)
        val ady = abs(totalDy)
        if (adx > touchSlop || ady > touchSlop) movedBeyondSlop = true
        if (done || lockedOut) return null
        if (
            swipeArmed && ady >= minSwipeDistance &&
            ady >= EdgeDragStateMachine.MIN_DOMINANT_RATIO * adx
        ) {
            done = true
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

    /** OS cancel / interruption: kills every pending candidate. */
    fun onCancel() {
        lockedOut = true
        done = true
    }

    /** The tracked pointer went up: a tap only while [isTapEligible] held. */
    fun onUp(): FreeAreaEvent? {
        if (!isTapEligible) {
            done = true
            return null
        }
        done = true
        return FreeAreaEvent.Tap
    }

    /** Long-press fired from the input-side timer: ends the gesture. */
    fun onActionFired() {
        done = true
    }
}

/**
 * Attaches the free-area gestures to this surface (design 5).
 *
 * One pointer loop tracks the down position, cumulative travel and pointer
 * count for the whole input, so classification always uses the original
 * start position and the same gesture history. The long-press is a timer in
 * the input layer that only fires while [FreeAreaTracker.isTapEligible]
 * holds — movement beyond slop before the deadline keeps it silent.
 *
 * [isSwipeStartAllowed] gates swipes on the down position — callers exclude
 * the bottom system gesture area so an up-swipe never fights the OS home
 * gesture (design 4.1).
 *
 * When [doubleTapEnabled] is false a tap fires immediately; when true the
 * first tap is held for the double-tap window so a second tap becomes
 * [FreeAreaEvent.DoubleTap] instead of two single taps (design 5: only wait
 * for the double-tap decision while screen-off is enabled).
 */
fun Modifier.freeAreaGestures(
    gate: FreeAreaGate,
    touchSlopPx: Float,
    isSwipeStartAllowed: (Offset) -> Boolean,
    doubleTapEnabled: Boolean,
    wasMultiPointer: () -> Boolean = { false },
    onHoldChange: (Boolean) -> Unit = {},
    onEvent: (FreeAreaEvent) -> Unit,
): Modifier = pointerInput(touchSlopPx, doubleTapEnabled) {
    // Timers (long-press deadline, double-tap window) run as children of
    // this input coroutine; PointerInputScope itself is not a CoroutineScope.
    val inputScope = CoroutineScope(coroutineContext)
    val minSwipePx = MIN_SWIPE_DISTANCE.toPx().coerceAtLeast(touchSlopPx)
    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
    val doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis

    // Double-tap window state survives across gestures: the first tap waits
    // here until a second tap or the timeout arrives.
    var secondTapArmed = false
    var pendingTapJob: Job? = null

    /**
     * The pending first tap resolves as a single tap. Called when the
     * second input turns out not to be a tap, or when the gesture ends.
     */
    fun flushPendingTap() {
        if (secondTapArmed) {
            secondTapArmed = false
            pendingTapJob?.cancel()
            pendingTapJob = null
            onEvent(FreeAreaEvent.Tap)
        }
    }

    fun settleTap() {
        if (!doubleTapEnabled) {
            onEvent(FreeAreaEvent.Tap)
            return
        }
        if (secondTapArmed) {
            secondTapArmed = false
            pendingTapJob?.cancel()
            pendingTapJob = null
            onEvent(FreeAreaEvent.DoubleTap)
        } else {
            secondTapArmed = true
            pendingTapJob = inputScope.launch {
                delay(doubleTapTimeoutMs)
                secondTapArmed = false
                onEvent(FreeAreaEvent.Tap)
            }
        }
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // A second down inside the double-tap window suspends the pending
        // tap's deadline — the pair is decided by how this press ends,
        // not by the wall clock while a finger is held.
        val pendingFromPrevious = secondTapArmed
        pendingTapJob?.cancel()
        pendingTapJob = null
        gate.longPressActive = false
        onHoldChange(true)

        val tracker = FreeAreaTracker(touchSlopPx, minSwipePx)
        tracker.onDown(isSwipeStartAllowed(down.position))
        var totalDx = 0f
        var totalDy = 0f

        val longPressJob = inputScope.launch {
            delay(longPressTimeoutMs)
            // A second finger resting elsewhere (e.g. on a bar) produces no
            // events on this surface, so the latch must be checked here too.
            if (tracker.isTapEligible && !wasMultiPointer()) {
                gate.longPressActive = true
                tracker.onActionFired()
                flushPendingTap()
                onEvent(FreeAreaEvent.LongPress)
            }
        }

        try {
            while (true) {
                val event = awaitPointerEvent()
                // A second finger may sit outside this surface's stream
                // (e.g. on a bar); the shared latch covers it too.
                if (
                    event.changes.count { it.pressed } > 1 ||
                    wasMultiPointer()
                ) {
                    tracker.onPointerCountChanged(2)
                }
                val change = event.changes.firstOrNull { it.id == down.id }
                when {
                    change == null -> {
                        // The tracked pointer vanished while others may still
                        // be down: the stream was interrupted — cancel.
                        if (event.changes.any { it.pressed }) {
                            tracker.onCancel()
                        }
                    }
                    change.pressed -> {
                        totalDx += change.positionChange().x
                        totalDy += change.positionChange().y
                        tracker.onMove(totalDx, totalDy)?.let {
                            flushPendingTap()
                            onEvent(it)
                        }
                    }
                    else -> {
                        // A synthetic consumed release means the OS stole the
                        // stream; a real finger-up is a Release event.
                        if (
                            event.type == PointerEventType.Release &&
                            !change.isConsumed
                        ) {
                            tracker.onUp()?.let { settleTap() }
                        } else {
                            tracker.onCancel()
                        }
                    }
                }
                if (event.changes.none { it.pressed }) break
            }
        } finally {
            longPressJob.cancel()
            // Only a pending tap armed by a PREVIOUS gesture resolves here:
            // this input ended without completing the pair, so it was a
            // single tap. A tap armed by this gesture keeps waiting on its
            // own deadline — flushing it now would turn every first tap
            // into an immediate single tap and break double-tap entirely.
            if (pendingFromPrevious) flushPendingTap()
            onHoldChange(false)
        }
    }
}
