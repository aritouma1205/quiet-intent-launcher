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
    fun `reset to Quiet clears deep stack`() {
        val nav = HomeNavigation()
        nav.navigateTo(HomeScreen.Search)
        nav.navigateTo(HomeScreen.Settings)
        nav.resetToQuiet()
        assertEquals(HomeScreen.Quiet, nav.screen.value)
        assertFalse(nav.back())
    }
}
