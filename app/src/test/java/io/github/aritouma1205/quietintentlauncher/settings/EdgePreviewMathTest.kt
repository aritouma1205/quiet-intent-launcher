package io.github.aritouma1205.quietintentlauncher.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I43-T01 (design 4.1): the shared bar-position formula used by both the
 * production edge bar and the settings preview. bias 0 = top edge,
 * bias 1 = bottom edge, the drawn length saturates at the track height,
 * and out-of-range inputs are clamped.
 */
class EdgePreviewMathTest {

    @Test
    fun `bias 0 pins the bar top to the track top`() {
        assertEquals(0f, edgeBarTopOffset(0f, 220f, 96f), 0.001f)
        assertEquals(0f, edgeBarTopOffset(0f, 1000f, 160f), 0.001f)
    }

    @Test
    fun `bias half centers the bar inside the track`() {
        // top = 0.5 * (220 - 96) = 62
        assertEquals(62f, edgeBarTopOffset(0.5f, 220f, 96f), 0.001f)
        // top = 0.5 * (500 - 100) = 200
        assertEquals(200f, edgeBarTopOffset(0.5f, 500f, 100f), 0.001f)
    }

    @Test
    fun `bias 1 puts the bar bottom on the track bottom`() {
        // top = 220 - 96 = 124
        assertEquals(124f, edgeBarTopOffset(1f, 220f, 96f), 0.001f)
        assertEquals(400f, edgeBarTopOffset(1f, 500f, 100f), 0.001f)
    }

    @Test
    fun `out of range bias is clamped to the track ends`() {
        assertEquals(0f, edgeBarTopOffset(-0.5f, 220f, 96f), 0.001f)
        assertEquals(124f, edgeBarTopOffset(1.5f, 220f, 96f), 0.001f)
    }

    @Test
    fun `a bar taller than the track fills it and cannot move`() {
        assertEquals(220f, edgeBarEffectiveLength(220f, 400f), 0.001f)
        // Clamped length equals the track height, so H - L = 0 for every bias.
        assertEquals(0f, edgeBarTopOffset(0f, 220f, 400f), 0.001f)
        assertEquals(0f, edgeBarTopOffset(0.5f, 220f, 400f), 0.001f)
        assertEquals(0f, edgeBarTopOffset(1f, 220f, 400f), 0.001f)
    }

    @Test
    fun `a zero-height track leaves no room to move`() {
        assertEquals(0f, edgeBarEffectiveLength(0f, 96f), 0.001f)
        assertEquals(0f, edgeBarTopOffset(0.5f, 0f, 96f), 0.001f)
        assertEquals(0f, edgeBarTopOffset(1f, 0f, 96f), 0.001f)
    }
}
