package io.github.aritouma1205.quietintentlauncher.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {

    private val min = 100f

    @Test
    fun `clear upward swipe fires`() {
        assertTrue(isUpSwipe(dx = 0f, dy = -150f, minDistancePx = min))
    }

    @Test
    fun `short upward drag does not fire`() {
        assertFalse(isUpSwipe(dx = 0f, dy = -99f, minDistancePx = min))
    }

    @Test
    fun `downward swipe does not fire`() {
        assertFalse(isUpSwipe(dx = 0f, dy = 300f, minDistancePx = min))
    }

    @Test
    fun `diagonal under the ratio does not fire`() {
        // |dx| must be <= |dy| / 1.5
        assertFalse(isUpSwipe(dx = 120f, dy = -150f, minDistancePx = min))
    }

    @Test
    fun `diagonal at the boundary fires`() {
        assertTrue(isUpSwipe(dx = 100f, dy = -150f, minDistancePx = min))
    }
}
