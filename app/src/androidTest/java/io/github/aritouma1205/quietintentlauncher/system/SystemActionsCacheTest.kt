package io.github.aritouma1205.quietintentlauncher.system

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.home.OsQuietServiceFixture
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I43-T08/T09 (design 15): the gesture path caches one Settings.Secure
 * read for a short TTL window so a gesture burst is not one binder call
 * per event. [SystemActions.refreshEnabled] and `setObserving(true)` are
 * the revoke-detection paths and always bypass the cache with a fresh
 * read — an OS-level revoke surfaced by the ContentObserver or by the
 * foreground refresh is the very next gesture's answer (design 13).
 */
@RunWith(AndroidJUnit4::class)
class SystemActionsCacheTest {

    private val app get() =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
    private val systemActions get() = app.container.systemActions

    private val reads = AtomicInteger(0)
    private var now = 0L
    private var enabledValue = false
    private lateinit var originalClock: () -> Long

    @Before
    fun setUp() {
        originalClock = systemActions.enabledCacheClock
        systemActions.enabledCacheClock = { now }
        systemActions.enabledOverride = {
            reads.incrementAndGet()
            enabledValue
        }
    }

    @After
    fun tearDown() {
        systemActions.setObserving(false)
        systemActions.enabledOverride = null
        systemActions.enabledCacheClock = originalClock
    }

    // ---- I43-T08: TTL window -----------------------------------------------

    @Test
    fun gesturePathReadsReuseOneQueryInsideTheTtl() {
        now = 1_000L
        enabledValue = true

        // First check: a real read arms the cache at t=1000.
        assertTrue(systemActions.isEnabledInOs())
        assertEquals(1, reads.get())

        // Inside the window the next gesture reuses the entry — no second
        // Settings.Secure read on the UI thread.
        now = 1_000L + SystemActions.ENABLED_CACHE_TTL_MS - 1
        assertTrue(systemActions.isEnabledInOs())
        assertEquals(1, reads.get())

        // Past the window the next check reads fresh and sees the revoke.
        now = 1_000L + SystemActions.ENABLED_CACHE_TTL_MS
        enabledValue = false
        assertFalse(systemActions.isEnabledInOs())
        assertEquals(2, reads.get())
        assertFalse(systemActions.enabled.value)
    }

    // ---- I43-T09: refresh / setObserving bypass ----------------------------

    @Test
    fun refreshAndSetObservingAlwaysReadFreshInsideTheTtl() {
        now = 0L
        enabledValue = true
        assertTrue(systemActions.isEnabledInOs())
        assertEquals(1, reads.get())

        // A revoke landing inside the window is invisible to the cached
        // gesture path — by design for the burst window only.
        enabledValue = false
        now = 500L
        assertTrue(systemActions.isEnabledInOs())
        assertEquals(1, reads.get())

        // refreshEnabled() is the observer path: it bypasses the live TTL
        // entry, publishes the fresh answer and re-arms the cache with it.
        systemActions.refreshEnabled()
        assertEquals(2, reads.get())
        assertFalse(systemActions.enabled.value)
        assertFalse(systemActions.isEnabledInOs())
        assertEquals(2, reads.get())

        // setObserving(true) is the foreground boundary: it always reads
        // fresh — including when the observer was already registered — so
        // a revoke that landed while backgrounded is the next answer.
        enabledValue = true
        systemActions.setObserving(true)
        assertEquals(3, reads.get())
        assertTrue(systemActions.enabled.value)

        enabledValue = false
        systemActions.setObserving(true)
        assertEquals(4, reads.get())
        assertFalse(systemActions.enabled.value)
        assertFalse(systemActions.isEnabledInOs())
        assertEquals(4, reads.get())
    }

    @Test
    fun osSettingsChangeReachesEnabledThroughTheObserver() {
        // End-to-end over the real ContentObserver: writing the OS secure
        // setting while observing must surface through refreshEnabled —
        // the TTL cache can never mask it. The override is dropped so the
        // read lands on the real Settings.Secure.
        systemActions.enabledOverride = null
        val fixture = OsQuietServiceFixture(
            InstrumentationRegistry.getInstrumentation()
                .targetContext.packageName,
        )
        try {
            systemActions.setObserving(true)
            fixture.enable()
            val deadline = System.currentTimeMillis() + 10_000
            while (!systemActions.enabled.value &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            assertTrue(
                "enabled did not observe the OS grant",
                systemActions.enabled.value,
            )
        } finally {
            fixture.restore()
        }
    }
}
