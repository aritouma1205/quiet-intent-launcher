package io.github.aritouma1205.quietintentlauncher.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole-surface pointer latch (design 4.2, 5): down/move tracking feeds the
 * shared multi-pointer check. Reconciliation against each event's pressed
 * set keeps a lost release/cancel from poisoning later gestures (Issue #16).
 */
class PointerLatchTest {

    private val slop = 10f

    private fun latch() = PointerLatch(slop)

    private fun down(id: Long, x: Float = 0f, y: Float = 0f) =
        PointerSample(PointerId(id), Offset(x, y), pressed = true, previousPressed = false)

    private fun move(id: Long, x: Float, y: Float) =
        PointerSample(PointerId(id), Offset(x, y), pressed = true, previousPressed = true)

    private fun up(id: Long, x: Float = 0f, y: Float = 0f) =
        PointerSample(PointerId(id), Offset(x, y), pressed = false, previousPressed = true)

    @Test
    fun `single finger that moved does not count as a second input`() {
        val l = latch()
        l.onEvent(listOf(down(0)))
        l.onEvent(listOf(move(0, 50f, 0f)))
        assertTrue(l.anyActive)
        assertFalse(l.multiActive(PointerId(0)))
    }

    @Test
    fun `resting second contact is not a second input`() {
        val l = latch()
        l.onEvent(listOf(down(0)))
        l.onEvent(listOf(move(0, 5f, 0f), down(1, 500f, 900f)))
        l.onEvent(listOf(move(0, 20f, 0f), move(1, 500f, 902f)))
        assertFalse(l.multiActive(PointerId(0)))
    }

    @Test
    fun `a second finger that moved counts as a second input`() {
        val l = latch()
        l.onEvent(listOf(down(0)))
        l.onEvent(listOf(move(0, 5f, 0f), down(1, 500f, 900f)))
        l.onEvent(listOf(move(0, 6f, 0f), move(1, 540f, 900f)))
        assertTrue(l.multiActive(PointerId(0)))
    }

    @Test
    fun `released moved finger stops counting`() {
        val l = latch()
        l.onEvent(listOf(down(0), down(1, 500f, 900f)))
        l.onEvent(listOf(move(0, 1f, 0f), move(1, 560f, 900f)))
        assertTrue(l.multiActive(PointerId(0)))
        l.onEvent(listOf(move(0, 2f, 0f), up(1, 560f, 900f)))
        assertFalse(l.multiActive(PointerId(0)))
    }

    /**
     * Issue #16: the tracked release/cancel is lost (process frozen,
     * window re-attach). The stale "moved" id used to make multiActive()
     * true for every later gesture — each new touch was cancelled as
     * phantom multi-touch. The next event must drop the stale id.
     */
    @Test
    fun `a lost release cannot poison the next gesture`() {
        val l = latch()
        l.onEvent(listOf(down(0)))
        l.onEvent(listOf(move(0, 60f, 0f))) // id 0 moved; its up is lost
        // New gesture: only id 1 is present. The stale id 0 is pruned.
        l.onEvent(listOf(down(1, 900f, 400f)))
        assertFalse(l.multiActive(PointerId(1)))
        assertFalse(l.multiActive(PointerId(0)))
    }

    @Test
    fun `a lost release cannot leave anyActive stuck`() {
        val l = latch()
        l.onEvent(listOf(down(0)))
        // Lost release, then an unrelated new contact arrives alone.
        l.onEvent(listOf(down(1, 900f, 400f), up(1, 900f, 400f)))
        assertFalse(l.anyActive)
    }
}
