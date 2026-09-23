package io.github.aritouma1205.quietintentlauncher.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Reveal layer above Quiet (design 3).
 *
 * At most one panel is open at a time; DO and TOOLS live inside the same
 * right panel. GLANCE is lighter: the bars and free-area gestures stay active
 * while it is shown, and any other operation dismisses it.
 */
sealed interface HomeOverlay {
    /** Transient info strip over Quiet (design 8.3). */
    data object Glance : HomeOverlay

    /** Right panel. [toolsExpanded] is the DO+TOOLS state of one panel. */
    data class Do(val toolsExpanded: Boolean = false) : HomeOverlay

    /** Left panel. */
    data object Today : HomeOverlay
}

/** Pure overlay state machine; the ViewModel owns one instance. */
class HomeOverlayController {
    private val _overlay = MutableStateFlow<HomeOverlay?>(null)
    val overlay: StateFlow<HomeOverlay?> = _overlay.asStateFlow()

    fun toggleGlance() {
        _overlay.value = if (_overlay.value == HomeOverlay.Glance) {
            null
        } else {
            HomeOverlay.Glance
        }
    }

    fun openDo(toolsExpanded: Boolean) {
        _overlay.value = HomeOverlay.Do(toolsExpanded)
    }

    fun openToday() {
        _overlay.value = HomeOverlay.Today
    }

    /** Collapses TOOLS inside the DO panel ("DOに戻る" / back). */
    fun collapseTools() {
        val current = _overlay.value
        if (current is HomeOverlay.Do && current.toolsExpanded) {
            _overlay.value = HomeOverlay.Do(toolsExpanded = false)
        }
    }

    fun expandTools() {
        val current = _overlay.value
        if (current is HomeOverlay.Do) {
            _overlay.value = HomeOverlay.Do(toolsExpanded = true)
        }
    }

    /** Outside tap / header drag: closes the whole panel at once. */
    fun close() {
        _overlay.value = null
    }

    /**
     * Back handling order (design 3, 4.3): TOOLS collapses first, then the
     * panel or GLANCE closes. Returns true when the press was consumed here.
     */
    fun back(): Boolean = when (val current = _overlay.value) {
        null -> false
        is HomeOverlay.Do -> {
            if (current.toolsExpanded) {
                _overlay.value = HomeOverlay.Do(toolsExpanded = false)
            } else {
                _overlay.value = null
            }
            true
        }
        else -> {
            _overlay.value = null
            true
        }
    }

    /** HOME / real backgrounding: drop every overlay. */
    fun clear() {
        _overlay.value = null
    }
}
