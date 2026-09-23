package io.github.aritouma1205.quietintentlauncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reveal overlay ordering (design 3, 4.3): one panel at a time, TOOLS folds
 * before the panel closes, GLANCE is replaced by any other operation.
 */
class HomeOverlayTest {

    @Test
    fun `panels are exclusive`() {
        val c = HomeOverlayController()
        c.openDo(toolsExpanded = false)
        c.openToday()
        assertEquals(HomeOverlay.Today, c.overlay.value)
    }

    @Test
    fun `opening a panel dismisses glance`() {
        val c = HomeOverlayController()
        c.toggleGlance()
        c.openDo(toolsExpanded = false)
        assertEquals(HomeOverlay.Do(false), c.overlay.value)
    }

    @Test
    fun `glance toggles on tap`() {
        val c = HomeOverlayController()
        c.toggleGlance()
        assertEquals(HomeOverlay.Glance, c.overlay.value)
        c.toggleGlance()
        assertNull(c.overlay.value)
    }

    @Test
    fun `back folds tools before closing the panel`() {
        val c = HomeOverlayController()
        c.openDo(toolsExpanded = true)
        assertTrue(c.back())
        assertEquals(HomeOverlay.Do(false), c.overlay.value)
        assertTrue(c.back())
        assertNull(c.overlay.value)
        assertFalse(c.back())
    }

    @Test
    fun `back on today closes it`() {
        val c = HomeOverlayController()
        c.openToday()
        assertTrue(c.back())
        assertNull(c.overlay.value)
    }

    @Test
    fun `back on glance dismisses it`() {
        val c = HomeOverlayController()
        c.toggleGlance()
        assertTrue(c.back())
        assertNull(c.overlay.value)
    }

    @Test
    fun `collapse tools is a no-op outside an expanded do panel`() {
        val c = HomeOverlayController()
        c.collapseTools()
        assertNull(c.overlay.value)
        c.openDo(toolsExpanded = false)
        c.collapseTools()
        assertEquals(HomeOverlay.Do(false), c.overlay.value)
    }

    @Test
    fun `clear drops every overlay`() {
        val c = HomeOverlayController()
        c.openDo(toolsExpanded = true)
        c.clear()
        assertNull(c.overlay.value)
    }
}
