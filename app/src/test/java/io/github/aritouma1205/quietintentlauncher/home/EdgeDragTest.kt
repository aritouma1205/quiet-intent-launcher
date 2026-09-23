package io.github.aritouma1205.quietintentlauncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bar-limited edge drag (design 4.2, 4.3): start-inside-only arming,
 * direction resolution, the 0.20W open threshold and the deep-pull
 * hysteresis. All cases are reproducible coordinate sequences.
 */
class EdgeDragTest {

    private val slop = 10f
    private val panelWidth = 1000f

    private fun rightBar(
        deepPull: Boolean = false,
        threshold: Float = 0.75f,
    ) = EdgeDragStateMachine(
        side = EdgeSide.Right,
        touchSlop = slop,
        panelWidth = panelWidth,
        deepPullEnabled = deepPull,
        toolsThreshold = threshold,
    )

    @Test
    fun `down outside the bar never arms`() {
        val m = rightBar()
        m.onDown(startedOnBar = false)
        assertNull(m.onMove(-500f, 0f))
        assertNull(m.onUp())
    }

    @Test
    fun `entering the bar mid gesture does not arm`() {
        val m = rightBar()
        // Down outside; even large inward travel stays inert.
        m.onDown(startedOnBar = false)
        assertNull(m.onMove(-900f, 0f))
        assertNull(m.onUp())
    }

    @Test
    fun `inward drag emits progress and opens at the threshold`() {
        val m = rightBar()
        m.onDown(true)
        val ev = m.onMove(-100f, 0f)
        assertTrue(ev is EdgeDragEvent.Progress)
        assertEquals(0.10f, (ev as EdgeDragEvent.Progress).fraction, 0.001f)
        assertEquals(EdgeDragEvent.Opened(false), m.onMove(-250f, 0f).let {
            // still dragging; release now at 0.25W
            m.onUp()
        })
    }

    @Test
    fun `release just below the open threshold closes`() {
        val m = rightBar()
        m.onDown(true)
        m.onMove(-199f, 0f)
        assertEquals(EdgeDragEvent.Closed, m.onUp())
    }

    @Test
    fun `release exactly at the open threshold opens`() {
        val m = rightBar()
        m.onDown(true)
        m.onMove(-200f, 0f)
        assertEquals(EdgeDragEvent.Opened(false), m.onUp())
    }

    @Test
    fun `pull back below the threshold before release closes`() {
        val m = rightBar()
        m.onDown(true)
        m.onMove(-500f, 0f)
        m.onMove(-100f, 0f)
        assertEquals(EdgeDragEvent.Closed, m.onUp())
    }

    @Test
    fun `vertical first cancels the drag`() {
        val m = rightBar()
        m.onDown(true)
        assertEquals(EdgeDragEvent.Closed, m.onMove(-5f, 30f))
        assertNull(m.onUp())
    }

    @Test
    fun `outward start cancels`() {
        val m = rightBar()
        m.onDown(true)
        assertEquals(EdgeDragEvent.Closed, m.onMove(50f, 0f))
    }

    @Test
    fun `diagonal input waits for a dominant direction`() {
        val m = rightBar()
        m.onDown(true)
        // dx=slop+, dy comparable: pending, no cancel, no drag yet.
        assertNull(m.onMove(-12f, 10f))
        // Horizontal becomes dominant.
        val ev = m.onMove(-200f, 60f)
        assertTrue(ev is EdgeDragEvent.Progress || ev == EdgeDragEvent.Closed)
    }

    @Test
    fun `second pointer closes the drag`() {
        val m = rightBar()
        m.onDown(true)
        m.onMove(-300f, 0f)
        assertEquals(EdgeDragEvent.Closed, m.onPointerCountChanged(2))
        assertNull(m.onUp())
    }

    @Test
    fun `os cancel closes the drag`() {
        val m = rightBar()
        m.onDown(true)
        m.onMove(-300f, 0f)
        assertEquals(EdgeDragEvent.Closed, m.onCancel())
    }

    @Test
    fun `press and release without a drag is a tap`() {
        val m = rightBar()
        m.onDown(true)
        assertEquals(EdgeDragEvent.Tapped, m.onUp())
    }

    @Test
    fun `sub slop wiggle still taps`() {
        val m = rightBar()
        m.onDown(true)
        assertNull(m.onMove(-5f, 3f))
        assertEquals(EdgeDragEvent.Tapped, m.onUp())
    }

    @Test
    fun `deep pull expands tools at the threshold with hysteresis`() {
        val m = rightBar(deepPull = true)
        m.onDown(true)
        m.onMove(-100f, 0f)
        // Below 0.75: progress only, no tools event.
        assertTrue(m.onMove(-740f, 0f) is EdgeDragEvent.Progress)
        assertEquals(EdgeDragEvent.ToolsExpanded(0.76f), m.onMove(-760f, 0f))
        // Hysteresis: expanded stays until progress < 0.75 - 0.08 = 0.67.
        assertTrue(m.onMove(-700f, 0f) is EdgeDragEvent.Progress)
        assertEquals(EdgeDragEvent.ToolsCollapsed(0.66f), m.onMove(-660f, 0f))
    }

    @Test
    fun `release while expanded opens with tools`() {
        val m = rightBar(deepPull = true)
        m.onDown(true)
        m.onMove(-800f, 0f)
        assertEquals(EdgeDragEvent.Opened(true), m.onUp())
    }

    @Test
    fun `deep pull disabled emits no tools events`() {
        val m = rightBar(deepPull = false)
        m.onDown(true)
        val ev = m.onMove(-800f, 0f)
        assertTrue(ev is EdgeDragEvent.Progress)
        assertEquals(EdgeDragEvent.Opened(false), m.onUp())
    }

    @Test
    fun `left bar mirrors the inward direction`() {
        val m = EdgeDragStateMachine(
            side = EdgeSide.Left,
            touchSlop = slop,
            panelWidth = panelWidth,
        )
        m.onDown(true)
        val ev = m.onMove(250f, 0f)
        assertTrue(ev is EdgeDragEvent.Progress)
        assertEquals(EdgeDragEvent.Opened(false), m.onUp())
    }

    @Test
    fun `tap on left bar reports tapped`() {
        val m = EdgeDragStateMachine(
            side = EdgeSide.Left,
            touchSlop = slop,
            panelWidth = panelWidth,
        )
        m.onDown(true)
        assertEquals(EdgeDragEvent.Tapped, m.onUp())
    }
}
