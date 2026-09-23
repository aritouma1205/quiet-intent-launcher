package io.github.aritouma1205.quietintentlauncher.launch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchGateTest {

    private var now = 0L
    private val gate = LaunchGate(holdMillis = 1_000L) { now }

    @Test
    fun `same target is blocked while held`() {
        assertTrue(gate.tryAcquire("a"))
        assertFalse(gate.tryAcquire("a"))
        assertTrue(gate.tryAcquire("b"))
    }

    @Test
    fun `held key expires after the window`() {
        gate.tryAcquire("a")
        now += 999
        assertFalse(gate.tryAcquire("a"))
        now += 2
        assertTrue(gate.tryAcquire("a"))
    }

    @Test
    fun `release frees the key immediately`() {
        gate.tryAcquire("a")
        gate.release("a")
        assertTrue(gate.tryAcquire("a"))
    }
}
