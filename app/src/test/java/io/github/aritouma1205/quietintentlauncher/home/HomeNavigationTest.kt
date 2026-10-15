package io.github.aritouma1205.quietintentlauncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavigationTest {

    @Test
    fun `starts at Quiet`() {
        val nav = HomeNavigation()
        assertEquals(HomeScreen.Quiet, nav.screen.value)
        assertFalse(nav.back())
    }

    @Test
    fun `back walks the stack to Quiet`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.Search)
        nav.navigateTo(HomeScreen.AllApps)
        assertEquals(HomeScreen.AllApps, nav.screen.value)

        assertTrue(nav.back())
        assertEquals(HomeScreen.Search, nav.screen.value)
        assertTrue(nav.back())
        assertEquals(HomeScreen.Quiet, nav.screen.value)
        assertFalse(nav.back())
    }

    @Test
    fun `backgrounding without external flow resets to Quiet`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.Search)
        nav.navigateTo(HomeScreen.AllApps)

        nav.onBackgrounded()
        assertEquals(HomeScreen.Quiet, nav.screen.value)
    }

    @Test
    fun `external flow keeps the originating screen`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.Settings)

        nav.beginExternalFlow()
        nav.onBackgrounded()
        assertEquals(HomeScreen.Settings, nav.screen.value)

        nav.onForegrounded()
        nav.onBackgrounded()
        // Flag consumed: a later plain backgrounding resets again.
        assertEquals(HomeScreen.Quiet, nav.screen.value)
    }

    @Test
    fun `foreground release is idempotent and a new flow can re-arm`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.Settings)
        nav.beginExternalFlow()

        // Release happens on both onStart and onResume returns (Issue #40):
        // a second release must be a no-op, and once released the next
        // backgrounding resets again.
        nav.onForegrounded()
        nav.onForegrounded()
        nav.onBackgrounded()
        assertEquals(HomeScreen.Quiet, nav.screen.value)

        nav.navigateTo(HomeScreen.Settings)
        nav.beginExternalFlow()
        nav.onBackgrounded()
        // A flow armed after the release still protects its screen.
        assertEquals(HomeScreen.Settings, nav.screen.value)
    }

    @Test
    fun `home intent resets even during external flow`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.Settings)
        nav.beginExternalFlow()

        nav.onHomeInvoked()
        assertEquals(HomeScreen.Quiet, nav.screen.value)

        nav.onBackgrounded()
        assertEquals(HomeScreen.Quiet, nav.screen.value)
    }

    @Test
    fun `cancel external flow restores plain backgrounding`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.AllApps)

        nav.beginExternalFlow()
        // The hand-off failed (ActivityNotFoundException): disarm, then the
        // next backgrounding must reset like any other (Issue #38).
        nav.cancelExternalFlow()
        nav.onBackgrounded()
        assertEquals(HomeScreen.Quiet, nav.screen.value)
    }

    @Test
    fun `cancel external flow is idempotent`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.AllApps)

        // Cancelling without an armed flow is a no-op…
        nav.cancelExternalFlow()
        assertEquals(HomeScreen.AllApps, nav.screen.value)

        nav.beginExternalFlow()
        nav.cancelExternalFlow()
        nav.cancelExternalFlow()
        // …and a double cancel cannot resurrect the armed state.
        nav.onBackgrounded()
        assertEquals(HomeScreen.Quiet, nav.screen.value)
    }

    @Test
    fun `reset to Quiet clears deep stack`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.Search)
        nav.navigateTo(HomeScreen.Settings)
        nav.resetToQuiet()
        assertEquals(HomeScreen.Quiet, nav.screen.value)
        assertFalse(nav.back())
    }

    // ---- draftEpoch (Issue #41) ---------------------------------------------
    // I41-T01: the draft-discard generation must strictly increase on every
    // stack publish and stay put on every path that does not publish.

    @Test
    fun `draft epoch increases on every stack publish`() {
        val nav = HomeNavigation()
        assertEquals(0L, nav.draftEpoch.value)

        nav.navigateTo(HomeScreen.Search)
        val afterNavigate = nav.draftEpoch.value
        nav.navigateTo(HomeScreen.Settings)
        val afterSecondNavigate = nav.draftEpoch.value
        nav.back()
        val afterBack = nav.draftEpoch.value
        nav.resetToQuiet()
        val afterReset = nav.draftEpoch.value
        nav.onHomeInvoked()
        val afterHome = nav.draftEpoch.value

        assertTrue(afterNavigate < afterSecondNavigate)
        assertTrue(afterSecondNavigate < afterBack)
        assertTrue(afterBack < afterReset)
        assertTrue(afterReset < afterHome)
    }

    @Test
    fun `draft epoch is stable without a publish`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.Settings)
        val epoch = nav.draftEpoch.value

        // External-flow arming/return never touches the stack top, so the
        // draft key must not move while an OS flow is on top (Issue #41).
        nav.beginExternalFlow()
        nav.onBackgrounded()
        nav.onForegrounded()
        nav.cancelExternalFlow()
        assertEquals(epoch, nav.draftEpoch.value)
        assertEquals(HomeScreen.Settings, nav.screen.value)
    }

    @Test
    fun `root-level back keeps the epoch`() {
        val nav = HomeNavigation()
        // back() at the root publishes nothing.
        assertFalse(nav.back())
        assertEquals(0L, nav.draftEpoch.value)
    }

    @Test
    fun `backgrounding bumps the epoch when it resets to Quiet`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.EdgeSettings)
        val epoch = nav.draftEpoch.value

        nav.onBackgrounded()
        assertEquals(HomeScreen.Quiet, nav.screen.value)
        assertTrue(nav.draftEpoch.value > epoch)
    }
}
