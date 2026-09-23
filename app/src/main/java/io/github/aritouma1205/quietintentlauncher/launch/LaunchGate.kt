package io.github.aritouma1205.quietintentlauncher.launch

/**
 * Blocks repeated launches of the same target while a launch is in flight
 * (design 15: rapid taps must not fire the same launch twice).
 *
 * A held key expires after [holdMillis] because the OS gives no synchronous
 * "launch finished" signal; a failed dispatch releases the key immediately.
 */
class LaunchGate(
    private val holdMillis: Long = DEFAULT_HOLD_MILLIS,
    private val clock: () -> Long,
) {
    private val inFlight = HashMap<String, Long>()

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val it = inFlight.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value >= holdMillis) it.remove()
        }
        if (inFlight.containsKey(key)) return false
        inFlight[key] = now
        return true
    }

    @Synchronized
    fun release(key: String) {
        inFlight.remove(key)
    }

    @Synchronized
    fun isHeld(key: String): Boolean {
        val now = clock()
        val heldAt = inFlight[key] ?: return false
        return now - heldAt < holdMillis
    }

    companion object {
        const val DEFAULT_HOLD_MILLIS: Long = 1_000L
    }
}
