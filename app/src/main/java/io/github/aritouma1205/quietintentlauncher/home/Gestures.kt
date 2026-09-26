package io.github.aritouma1205.quietintentlauncher.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
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

/** One pointer observation handed to [PointerLatch]. */
internal data class PointerSample(
    val id: PointerId,
    val position: Offset,
    val pressed: Boolean,
    val previousPressed: Boolean,
)

/**
 * Whole-surface pointer latch (design 4.2, 5): remembers which pointers are
 * down and which have travelled past touch slop, so every detector can tell
 * a real second finger from a resting palm.
 *
 * The sets are reconciled against each event's pressed pointers before the
 * event is applied: a release or OS cancel that never reached this stream
 * — lost while the process was frozen or the window re-attached — must not
 * leave a stale entry that would keep canceling every later gesture
 * (Issue #16). Stale ids are dropped on the next event instead.
 */
internal class PointerLatch(private val slopPx: Float) {
    private val downAt = mutableMapOf<PointerId, Offset>()
    private val moved = mutableSetOf<PointerId>()

    /** Any pointer currently down on this surface. */
    val anyActive: Boolean get() = downAt.isNotEmpty()

    /** A *different* pointer that moved past slop — the real second input. */
    fun multiActive(own: PointerId): Boolean = moved.any { it != own }

    fun onEvent(changes: List<PointerSample>) {
        // Ids absent from this event's pressed set are gone even if their
        // release/cancel was never delivered; prune them first.
        val live = HashSet<PointerId>(changes.size)
        for (c in changes) if (c.pressed) live += c.id
        downAt.keys.retainAll(live)
        moved.retainAll(live)
        for (c in changes) {
            when {
                c.pressed && !c.previousPressed -> downAt[c.id] = c.position
                !c.pressed && c.previousPressed -> {
                    downAt.remove(c.id)
                    moved.remove(c.id)
                }
                c.pressed -> {
                    val origin = downAt.getOrPut(c.id) { c.position }
                    if ((c.position - origin).getDistance() > slopPx) {
                        moved += c.id
                    }
                }
            }
        }
    }
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
 * - Horizontal-dominant travel, a second pointer that actually moves, or
 *   a cancel locks the gesture out entirely — nothing is assigned to
 *   horizontal swipes. A resting second contact is not a lockout: a palm
 *   held at the screen edge must not kill one-handed input (design 4.2).
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
 * for the double-tap decision while screen-off is enabled). It is a
 * supplier, evaluated at tap-settle time, so TalkBack / Switch Access
 * turning on mid-session disables the hold immediately — the home double
 * tap must never intercept a screen reader's double tap (design 12).
 */
fun Modifier.freeAreaGestures(
    gate: FreeAreaGate,
    touchSlopPx: Float,
    isSwipeStartAllowed: (Offset) -> Boolean,
    doubleTapEnabled: () -> Boolean,
    multiPointerActive: (PointerId) -> Boolean = { false },
    onHoldChange: (Boolean) -> Unit = {},
    onEvent: (FreeAreaEvent) -> Unit,
): Modifier = pointerInput(touchSlopPx) {
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
        if (!doubleTapEnabled()) {
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

        // Secondary contacts on this surface are tracked from where they
        // landed: one only counts as a second input once it travels past
        // touch slop. A finger merely resting on the glass — a palm held
        // at the edge — must not kill the gesture (design 4.2).
        val otherDownAt = mutableMapOf<PointerId, Offset>()

        val longPressJob = inputScope.launch {
            delay(longPressTimeoutMs)
            // A second finger operating elsewhere (e.g. dragging on a bar)
            // produces no events on this surface, so the shared latch must
            // be checked here too; a resting finger does not set it.
            if (tracker.isTapEligible && !multiPointerActive(down.id)) {
                gate.longPressActive = true
                tracker.onActionFired()
                flushPendingTap()
                onEvent(FreeAreaEvent.LongPress)
            }
        }

        try {
            while (true) {
                val event = awaitPointerEvent()
                // A second contact suppresses the gesture once it behaves
                // as input — moved past slop — on this surface or, via the
                // shared latch, anywhere on the screen.
                var secondActive = multiPointerActive(down.id)
                for (other in event.changes) {
                    if (other.id == down.id) continue
                    if (!other.pressed) {
                        otherDownAt.remove(other.id)
                        continue
                    }
                    val origin =
                        otherDownAt.getOrPut(other.id) { other.position }
                    if (
                        (other.position - origin).getDistance() > touchSlopPx
                    ) {
                        secondActive = true
                    }
                }
                if (secondActive) tracker.onPointerCountChanged(2)
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
