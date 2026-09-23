package io.github.aritouma1205.quietintentlauncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Free-area vertical-swipe classification (design 5). Pure state-machine
 * tests; taps, long-press and double-tap use framework detectors and are
 * covered by the emulator walkthrough.
 */
class GesturesTest {

    private val slop = 10f
    private val minSwipe = 100f

    private fun tracker() = FreeAreaSwipeTracker(slop, minSwipe).also {
        it.onDown(swipeAllowed = true)
    }

    @Test
    fun `clear up swipe fires once`() {
        val t = tracker()
        assertEquals(FreeAreaEvent.SwipeUp, t.onMove(0f, -150f))
        // No second event for the same input.
        assertNull(t.onMove(0f, -200f))
    }

    @Test
    fun `clear down swipe fires`() {
        val t = tracker()
        assertEquals(FreeAreaEvent.SwipeDown, t.onMove(0f, 150f))
    }

    @Test
    fun `short upward travel below the distance does not fire`() {
        val t = tracker()
        assertNull(t.onMove(0f, -99f))
    }

    @Test
    fun `diagonal below the ratio stays pending until a direction wins`() {
        val t = tracker()
        // dx = 100, dy = 120: neither dominates by 1.5x.
        assertNull(t.onMove(100f, -120f))
        // Vertical becomes dominant.
        assertEquals(FreeAreaEvent.SwipeUp, t.onMove(100f, -160f))
    }

    @Test
    fun `diagonal at the boundary fires`() {
        val t = tracker()
        // |dy| = 1.5 * |dx| exactly.
        assertEquals(FreeAreaEvent.SwipeUp, t.onMove(100f, -150f))
    }

    @Test
    fun `horizontal dominant move locks out and stays silent`() {
        val t = tracker()
        assertNull(t.onMove(60f, 10f))
        // Even turning vertical afterwards stays silent.
        assertNull(t.onMove(60f, -300f))
    }

    @Test
    fun `second pointer locks the gesture out`() {
        val t = tracker()
        t.onPointerCountChanged(2)
        assertNull(t.onMove(0f, -300f))
    }

    @Test
    fun `swipe start can be disallowed by the caller`() {
        val t = FreeAreaSwipeTracker(slop, minSwipe)
        t.onDown(swipeAllowed = false)
        // Bottom system area: vertical travel is ignored entirely.
        assertNull(t.onMove(0f, -300f))
    }

    @Test
    fun `cancel locks everything out`() {
        val t = tracker()
        t.onMove(0f, -50f)
        t.onCancel()
        assertNull(t.onMove(0f, -300f))
    }
}
