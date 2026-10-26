package io.github.aritouma1205.quietintentlauncher.home

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.AppContainer
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.system.VolumeUi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #49 end-to-end coverage for the TODAY 「音量」 block: the slider
 * write path through the stream seams, the open-scoped VOLUME_CHANGED
 * subscription that repaints the slider on hardware-key changes, the
 * per-open re-read, and — with the seams left real — the actual
 * STREAM_MUSIC change on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class VolumeIntegrationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: HomeViewModel
    private lateinit var container: AppContainer
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun res(id: Int): String = rule.activity.getString(id)

    @Before
    fun setUp() {
        val app = instrumentation.targetContext.applicationContext
            as QuietLauncherApp
        container = app.container
        runBlocking {
            container.settingsStore.state.first { it is SettingsState.Ready }
            container.settingsStore.update { it.copy(introCompleted = true) }
        }
        viewModel = HomeViewModel(container)
        rule.setContent {
            QuietLauncherRoot(
                viewModel = viewModel,
                iconLoader = { null },
                onRequestHomeRole = {},
                onRestoreHome = {},
                onChangeWallpaper = {},
                onOpenAppInfo = {},
                onUninstallApp = {},
                onOpenEvent = {},
                onOpenAccessibilitySettings = {},
                onOpenMediaOutput = {},
            )
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertNull(viewModel.overlay.value)
    }

    @After
    fun tearDown() {
        // Seams and the open-scoped receiver must not leak into the next
        // test class — closing the overlay drops the registration.
        container.mediaVolume.resetSeams()
        viewModel.onBackgrounded()
        viewModel.closeOverlay()
    }

    private fun openToday() {
        viewModel.openTodayPanel()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.overlay.value == HomeOverlay.Today
        }
        rule.waitForIdle()
    }

    /** The M3 Slider carries the SetProgress semantics action. */
    private val sliderNode
        get() = rule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
        )

    /**
     * Feeds the registered receiver the exact intent the system sends.
     * VOLUME_CHANGED is a protected broadcast only the OS can emit —
     * neither apps nor shell may send it — so the test drives the same
     * receiver path the OS would (the torch-callback-seam rule). The
     * real-broadcast delivery itself is covered by
     * [realStreamChangeRepaintsTheSliderViaTheOsBroadcast].
     */
    private fun deliverVolumeBroadcast(streamType: Int) {
        rule.runOnUiThread {
            container.mediaVolume.volumeReceiver.onReceive(
                instrumentation.targetContext,
                Intent("android.media.VOLUME_CHANGED_ACTION")
                    .putExtra(
                        "android.media.EXTRA_VOLUME_STREAM_TYPE",
                        streamType,
                    ),
            )
        }
    }

    // I49-L2a: a slider gesture maps the fraction onto the stream index and
    // reaches AudioManager exactly once with stream=MUSIC and flags=0.
    @Test
    fun sliderSetProgressWritesTheStreamIndex() {
        var level = 5
        val writes = CopyOnWriteArrayList<Triple<Int, Int, Int>>()
        container.mediaVolume.streamMaxReader = { 15 }
        container.mediaVolume.streamVolumeReader = { level }
        container.mediaVolume.streamVolumeWriter = { stream, index, flags ->
            writes += Triple(stream, index, flags)
            level = index
        }
        openToday()
        runBlocking {
            viewModel.volume.first { it == VolumeUi(5, 15) }
        }
        sliderNode.performScrollTo()
        // 0.6 lands exactly on a step of a 0..15 range — unambiguous both
        // before and after the slider's step snapping.
        sliderNode.performSemanticsAction(SemanticsActions.SetProgress) {
            it(0.6f)
        }
        rule.waitUntil(timeoutMillis = 5_000) { writes.isNotEmpty() }
        // 0.6 of 0..15 resolves to index 9 on MUSIC, no flags.
        assertEquals(Triple(AudioManager.STREAM_MUSIC, 9, 0), writes.last())
        // The state is re-read after the write — the slider shows the
        // OS-accepted level, not the asked-for one.
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.volume.value == VolumeUi(9, 15)
        }
    }

    // I49-L2b: a MUSIC VOLUME_CHANGED broadcast while the panel is open
    // re-reads the level into the StateFlow (hardware-key tracking).
    @Test
    fun volumeBroadcastWhileOpenRefreshesTheState() {
        var level = 4
        container.mediaVolume.streamMaxReader = { 15 }
        container.mediaVolume.streamVolumeReader = { level }
        openToday()
        runBlocking {
            viewModel.volume.first { it.volume == 4 }
        }

        level = 11
        deliverVolumeBroadcast(AudioManager.STREAM_MUSIC)
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.volume.value.volume == 11
        }

        // A broadcast for another stream must not touch the slider.
        level = 2
        deliverVolumeBroadcast(AudioManager.STREAM_RING)
        Thread.sleep(400)
        rule.waitForIdle()
        assertEquals(11, viewModel.volume.value.volume)
    }

    // I49-L2c: the receiver is open-scoped — a broadcast after close moves
    // nothing, and every open re-reads the reader (a device that never
    // broadcasts still shows the true level).
    @Test
    fun receiverIsOpenScopedAndEachOpenRereads() {
        var level = 6
        val reads = AtomicInteger(0)
        container.mediaVolume.streamMaxReader = { 15 }
        container.mediaVolume.streamVolumeReader = {
            reads.incrementAndGet()
            level
        }
        openToday()
        runBlocking {
            viewModel.volume.first { it.volume == 6 }
        }
        val readsOnFirstOpen = reads.get()
        assertTrue(readsOnFirstOpen >= 1)

        viewModel.closeOverlay()
        rule.waitUntil(timeoutMillis = 5_000) { viewModel.overlay.value == null }
        rule.waitForIdle()

        // Closed: the receiver is gone — a broadcast no longer re-reads.
        level = 9
        deliverVolumeBroadcast(AudioManager.STREAM_MUSIC)
        Thread.sleep(400)
        rule.waitForIdle()
        assertEquals(6, viewModel.volume.value.volume)

        // Reopening re-reads: the stale value is replaced immediately.
        viewModel.openTodayPanel()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.overlay.value == HomeOverlay.Today &&
                viewModel.volume.value.volume == 9
        }
        assertTrue(reads.get() > readsOnFirstOpen)
    }

    // I49-L2d/L3 (real path): no seams — the slider writes through to the
    // emulator's real STREAM_MUSIC and the StateFlow reports the level the
    // OS actually holds.
    @Test
    fun sliderSetProgressChangesTheRealStreamVolume() {
        val audioManager = instrumentation.targetContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val original = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // A target clearly different from the current level.
        val target = if (original < max) max else max - 1
        try {
            openToday()
            runBlocking {
                viewModel.volume.first { it.max == max }
            }
            sliderNode.performScrollTo()
            sliderNode.performSemanticsAction(SemanticsActions.SetProgress) {
                it(target.toFloat() / max)
            }
            rule.waitUntil(timeoutMillis = 5_000) {
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == target
            }
            assertEquals(target, viewModel.volume.value.volume)
            assertEquals(max, viewModel.volume.value.max)
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
        }
    }

    // I49-L3 (real path): a STREAM_MUSIC change made outside the slider —
    // what a hardware key does — emits the OS-protected broadcast, and the
    // open-scoped receiver repaints the StateFlow. This is the only test
    // here that exercises real broadcast delivery; the protected action
    // cannot be sent by tests or shell.
    @Test
    fun realStreamChangeRepaintsTheSliderViaTheOsBroadcast() {
        val audioManager = instrumentation.targetContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val original = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = if (original < max) max else max - 1
        try {
            openToday()
            runBlocking {
                viewModel.volume.first { it.volume == original }
            }
            // A direct OS write — stands in for the hardware-key path.
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            rule.waitUntil(timeoutMillis = 5_000) {
                viewModel.volume.value.volume == target
            }
        } finally {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
        }
    }

    // I49-L2e: the 「出力先」 row exists inside the real panel and its tap
    // reaches the ViewModel — the external-flow flag is armed before the
    // launch request is handed to the Activity (Issue #49).
    @Test
    fun outputRowTapArmsTheExternalFlow() {
        openToday()
        rule.onNodeWithText(res(R.string.today_volume)).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.volume_output))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
        assertTrue(viewModel.nav.externalFlowArmed)
        // The release runs in tearDown's onBackgrounded/onForegrounded
        // cycle; disarm explicitly here so nothing lingers either way.
        viewModel.nav.onForegrounded()
    }
}
