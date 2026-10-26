package io.github.aritouma1205.quietintentlauncher.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stack screens (design 3). Reveal panels are overlays, not stack entries. */
enum class HomeScreen {
    Quiet,
    Intro,
    Search,
    AllApps,
    Settings,
    EdgeSettings,
    DoSettings,
    ContextSettings,
    SearchSettings,
    InfoSettings,
    SystemSettings,
    Edit,
}

/**
 * Quiet/Deep screen state machine (design 3).
 *
 * - One stack; [HomeScreen.Quiet] is the root.
 * - A HOME intent or leaving to an external app returns the stack to Quiet.
 * - Flows this app itself opened (role picker, wallpaper picker, app info)
 *   restore the screen that launched them instead of resetting to Quiet.
 * - A process restart always begins at Quiet again (no saved stack).
 * - A screen that leaves the stack top loses its unsaved drafts for good
 *   (Issue #41): every [publish] bumps [draftEpoch], which the UI mixes
 *   into the saveable-state key wrapping the screen dispatch, so the key a
 *   discarded draft was stored under is never generated again. Screens
 *   kept by an armed external flow publish nothing and keep their drafts;
 *   configuration changes keep both the epoch and the process nonce.
 */
class HomeNavigation(initial: HomeScreen = HomeScreen.Quiet) {
    private val stack = ArrayDeque<HomeScreen>().apply { add(initial) }
    private val _screen = MutableStateFlow(initial)

    /** Top of the stack. */
    val screen: StateFlow<HomeScreen> = _screen.asStateFlow()

    private val _draftEpoch = MutableStateFlow(0L)

    /**
     * Monotonic generation key for unsaved-draft discarding (Issue #41).
     * Incremented once per [publish] — the single funnel used by
     * [navigateTo], [back], [resetToQuiet] and the reset paths in
     * [onBackgrounded]/[onHomeInvoked] — so a screen's composite key can
     * never repeat. Non-publishing operations (external-flow arm/disarm/
     * return, a root-level [back] that pops nothing) leave it unchanged,
     * which is exactly what keeps drafts alive under an armed OS flow and
     * across configuration changes.
     */
    val draftEpoch: StateFlow<Long> = _draftEpoch.asStateFlow()

    private var externalFlowActive = false

    /**
     * Test seam: whether an external-flow hand-off is currently armed
     * (Issue #49). The flag itself drives [onBackgrounded]; this probe
     * lets instrumentation assert the arm without a state transition.
     */
    internal val externalFlowArmed: Boolean
        get() = externalFlowActive

    fun navigateTo(target: HomeScreen) {
        stack.addLast(target)
        publish()
    }

    /** Pops one screen. Returns false when already at the root. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        publish()
        return true
    }

    fun resetToQuiet() {
        stack.clear()
        stack.addLast(HomeScreen.Quiet)
        publish()
    }

    /** Call before starting an OS flow the user is expected to return from. */
    fun beginExternalFlow() {
        externalFlowActive = true
    }

    /**
     * The external hand-off never happened (e.g. no activity resolved the
     * intent): disarm the flag so the next backgrounding resets to Quiet
     * instead of resurrecting a screen that launched nothing.
     */
    fun cancelExternalFlow() {
        externalFlowActive = false
    }

    /** Activity foregrounded: an external flow (if any) has returned. */
    fun onForegrounded() {
        externalFlowActive = false
    }

    /**
     * Activity backgrounded without an external flow: next time the home UI
     * is shown it must start from Quiet (design 3: home-return and unlock
     * resume never resurrect a panel or a typed query).
     */
    fun onBackgrounded() {
        if (!externalFlowActive) resetToQuiet()
    }

    /** HOME pressed while we were visible: always land on Quiet. */
    fun onHomeInvoked() {
        externalFlowActive = false
        resetToQuiet()
    }

    private fun publish() {
        _screen.value = stack.last()
        // One funnel, one bump: whichever operation moved the top also
        // retires every draft a previous top left behind (Issue #41).
        _draftEpoch.value += 1
    }
}
