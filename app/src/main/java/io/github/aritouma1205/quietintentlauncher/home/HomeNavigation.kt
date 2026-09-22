package io.github.aritouma1205.quietintentlauncher.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Screens reachable in stage 1 (design 3). Deeper panels arrive later. */
enum class HomeScreen {
    Quiet,
    Intro,
    Search,
    AllApps,
    Settings,
}

/**
 * Quiet/Deep screen state machine (design 3).
 *
 * - One stack; [HomeScreen.Quiet] is the root.
 * - A HOME intent or leaving to an external app returns the stack to Quiet.
 * - Flows this app itself opened (role picker, wallpaper picker, app info)
 *   restore the screen that launched them instead of resetting to Quiet.
 * - A process restart always begins at Quiet again (no saved stack).
 */
class HomeNavigation(initial: HomeScreen = HomeScreen.Quiet) {
    private val stack = ArrayDeque<HomeScreen>().apply { add(initial) }
    private val _screen = MutableStateFlow(initial)

    /** Top of the stack. */
    val screen: StateFlow<HomeScreen> = _screen.asStateFlow()

    private var externalFlowActive = false

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
    }
}
