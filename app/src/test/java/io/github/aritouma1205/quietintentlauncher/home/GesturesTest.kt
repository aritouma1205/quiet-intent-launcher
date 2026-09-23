package io.github.aritouma1205.quietintentlauncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole-input classification of free-area gestures (design 5). The tracker
 * decides on the pointer-down position and the cumulative history of the
 * same input — tap, long-press and double-tap candidates die on any
 * beyond-slop move, and one input never produces two actions.
 */
class GesturesTest {

    private val slop = 10f
    private val minSwipe = 100f

    private fun tracker() = FreeAreaTracker(slop, minSwipe).also {
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
    fun `ambiguous start can still bend into a swipe on the totals`() {
        val t = tracker()
        // dx=12, dy=10: beyond slop but neither direction dominates.
        assertNull(t.onMove(12f, 10f))
        // Bending upward keeps the same cumulative totals -> qualifies.
        assertEquals(FreeAreaEvent.SwipeUp, t.onMove(12f, -160f))
    }

    @Test
    fun `large horizontal leg prevents a late vertical bend from firing`() {
        val t = tracker()
        // dx=120 is horizontal-dominant: locked out immediately.
        assertNull(t.onMove(120f, 10f))
        assertNull(t.onMove(120f, -300f))
    }

    @Test
    fun `second pointer locks the gesture out`() {
        val t = tracker()
        t.onPointerCountChanged(2)
        assertNull(t.onMove(0f, -300f))
    }

    @Test
    fun `second pointer added mid-gesture cancels a pending swipe and tap`() {
        val t = tracker()
        assertNull(t.onMove(0f, -60f))
        t.onPointerCountChanged(2)
        assertNull(t.onMove(0f, -300f))
        assertFalse(t.isTapEligible)
        assertNull(t.onUp())
    }

    @Test
    fun `swipe start can be disallowed by the caller`() {
        val t = FreeAreaTracker(slop, minSwipe)
        t.onDown(swipeAllowed = false)
        // Bottom system area: vertical travel is ignored entirely.
        assertNull(t.onMove(0f, -300f))
    }

    @Test
    fun `disallowed swipe start still permits a tap`() {
        val t = FreeAreaTracker(slop, minSwipe)
        t.onDown(swipeAllowed = false)
        assertTrue(t.isTapEligible)
        assertEquals(FreeAreaEvent.Tap, t.onUp())
    }

    @Test
    fun `cancel locks everything out`() {
        val t = tracker()
        t.onMove(0f, -50f)
        t.onCancel()
        assertNull(t.onMove(0f, -300f))
        assertNull(t.onUp())
    }

    // --- Tap / long-press eligibility (design 5: 移動開始後は発火しない) ---

    @Test
    fun `stationary release is a tap`() {
        val t = tracker()
        assertTrue(t.isTapEligible)
        assertEquals(FreeAreaEvent.Tap, t.onUp())
    }

    @Test
    fun `sub slop wiggle still ends as a tap`() {
        val t = tracker()
        assertNull(t.onMove(5f, -4f))
        assertTrue(t.isTapEligible)
        assertEquals(FreeAreaEvent.Tap, t.onUp())
    }

    @Test
    fun `move beyond slop then hold then up is neither long press nor tap`() {
        val t = tracker()
        // Reviewer repro: down -> move 350px -> hold 700ms -> up.
        assertNull(t.onMove(350f, 0f))
        assertFalse(t.isTapEligible)
        assertNull(t.onUp())
    }

    @Test
    fun `move beyond slop revokes long press eligibility`() {
        val t = tracker()
        assertTrue(t.isTapEligible)
        t.onMove(0f, 20f)
        assertFalse(t.isTapEligible)
    }

    @Test
    fun `fired swipe suppresses the release tap`() {
        val t = tracker()
        assertEquals(FreeAreaEvent.SwipeUp, t.onMove(0f, -150f))
        assertNull(t.onUp())
    }

    @Test
    fun `fired long press suppresses swipe and tap for the rest`() {
        val t = tracker()
        t.onActionFired()
        assertNull(t.onMove(0f, -300f))
        assertNull(t.onUp())
    }

    @Test
    fun `one input yields at most one action`() {
        val t = tracker()
        t.onActionFired() // long-press fired
        assertNull(t.onUp())
    }
}
