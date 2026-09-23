package io.github.aritouma1205.quietintentlauncher.home

import kotlin.math.abs

/** Which screen edge a bar sits on. */
enum class EdgeSide {
    Left,
    Right,
}

/** One-shot events emitted by [EdgeDragStateMachine]. */
sealed interface EdgeDragEvent {
    /** Finger is dragging; [fraction] is inward travel over panel width. */
    data class Progress(val fraction: Float) : EdgeDragEvent

    /** Deep-pull mode: the drag reached the TOOLS depth threshold. */
    data class ToolsExpanded(val fraction: Float) : EdgeDragEvent

    /** Deep-pull mode: pulled back below threshold minus hysteresis. */
    data class ToolsCollapsed(val fraction: Float) : EdgeDragEvent

    /** Released at or beyond the open threshold. */
    data class Opened(val toolsExpanded: Boolean) : EdgeDragEvent

    /** Released below the open threshold, cancelled, or interrupted. */
    data object Closed : EdgeDragEvent

    /** Press and release inside the bar without a qualifying drag. */
    data object Tapped : EdgeDragEvent
}

/**
 * Bar-limited edge drag (design 4.2).
 *
 * A drag can only arm on a pointer-down inside the visible bar — the Compose
 * hit test on the bar guarantees that, and [onDown] still takes the flag so
 * the state machine itself is decidable in tests. Entering the bar mid-gesture
 * never arms it.
 *
 * Direction resolution:
 * - vertical travel decided first (>|slop| and >= 1.5x horizontal) cancels;
 * - outward horizontal travel beyond slop cancels;
 * - inward travel beyond slop requires >= 1.5x vertical, otherwise the input
 *   stays pending (diagonal input waits for a direction to emerge);
 * - while dragging, progress follows the finger; release >= 0.20W opens,
 *   below closes; velocity never decides;
 * - a second pointer, an OS cancel or an interruption closes.
 *
 * In deep-pull mode [EdgeDragEvent.ToolsExpanded]/[EdgeDragEvent.ToolsCollapsed]
 * fire at [toolsThreshold] with [ToolsSettings-like] hysteresis so the UI can
 * show the heading and play a single haptic tick.
 */
class EdgeDragStateMachine(
    private val side: EdgeSide,
    private val touchSlop: Float,
    private val panelWidth: Float,
    private val deepPullEnabled: Boolean = false,
    private val toolsThreshold: Float = 0.75f,
    private val toolsHysteresis: Float = 0.08f,
) {
    private enum class Phase { Idle, Armed, Dragging, Done }

    private var phase = Phase.Idle

    /** Latest inward progress as a fraction of panel width (>= 0). */
    var progress: Float = 0f
        private set

    /** Deep-pull expansion latched by the current drag. */
    var toolsExpanded: Boolean = false
        private set

    fun onDown(startedOnBar: Boolean) {
        phase = if (startedOnBar) Phase.Armed else Phase.Idle
        progress = 0f
        toolsExpanded = false
    }

    /**
     * Feed the cumulative displacement since pointer-down. Returns at most
     * one event per call; callers render [Progress] continuously.
     */
    fun onMove(totalDx: Float, totalDy: Float): EdgeDragEvent? {
        val inward = if (side == EdgeSide.Right) -totalDx else totalDx
        return when (phase) {
            Phase.Armed -> resolveDirection(inward, totalDy)
            Phase.Dragging -> {
                progress = (inward / panelWidth).coerceAtLeast(0f)
                updateTools(progress) ?: EdgeDragEvent.Progress(progress)
            }
            else -> null
        }
    }

    private fun resolveDirection(inward: Float, totalDy: Float): EdgeDragEvent? {
        val dy = abs(totalDy)
        when {
            // Vertical decided first.
            dy > touchSlop && dy >= MIN_DOMINANT_RATIO * abs(inward) -> {
                phase = Phase.Done
                return EdgeDragEvent.Closed
            }
            // Outward beyond slop.
            inward < -touchSlop -> {
                phase = Phase.Done
                return EdgeDragEvent.Closed
            }
            // Inward beyond slop and clearly horizontal.
            inward > touchSlop && inward >= MIN_DOMINANT_RATIO * dy -> {
                phase = Phase.Dragging
                progress = inward / panelWidth
                val toolsEvent = updateTools(progress)
                return toolsEvent ?: EdgeDragEvent.Progress(progress)
            }
            // Diagonal or below slop: keep waiting (design 4.2, 5).
            else -> return null
        }
    }

    private fun updateTools(progressFraction: Float): EdgeDragEvent? {
        if (!deepPullEnabled) return null
        if (!toolsExpanded && progressFraction >= toolsThreshold) {
            toolsExpanded = true
            return EdgeDragEvent.ToolsExpanded(progressFraction)
        }
        if (toolsExpanded && progressFraction < toolsThreshold - toolsHysteresis) {
            toolsExpanded = false
            return EdgeDragEvent.ToolsCollapsed(progressFraction)
        }
        return null
    }

    /** A second pointer while armed or dragging closes (design 4.2.4). */
    fun onPointerCountChanged(count: Int): EdgeDragEvent? {
        if (count <= 1) return null
        return finish()
    }

    fun onCancel(): EdgeDragEvent? = finish()

    fun onUp(): EdgeDragEvent? = when (phase) {
        Phase.Armed -> {
            phase = Phase.Done
            EdgeDragEvent.Tapped
        }
        Phase.Dragging -> {
            phase = Phase.Done
            if (progress >= OPEN_THRESHOLD) {
                EdgeDragEvent.Opened(toolsExpanded)
            } else {
                EdgeDragEvent.Closed
            }
        }
        else -> null
    }

    private fun finish(): EdgeDragEvent? = when (phase) {
        Phase.Armed, Phase.Dragging -> {
            phase = Phase.Done
            EdgeDragEvent.Closed
        }
        else -> null
    }

    companion object {
        /** Dominant-direction ratio shared with the free-area rules. */
        const val MIN_DOMINANT_RATIO = 1.5f

        /** Open when inward travel reaches this fraction of panel width. */
        const val OPEN_THRESHOLD = 0.20f
    }
}
