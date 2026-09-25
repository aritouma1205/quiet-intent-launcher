package io.github.aritouma1205.quietintentlauncher.home

import android.hardware.camera2.CameraAccessException
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.settings.ToolItem
import io.github.aritouma1205.quietintentlauncher.settings.ToolSetting
import io.github.aritouma1205.quietintentlauncher.settings.ToolsOpenMode
import io.github.aritouma1205.quietintentlauncher.system.SystemAction
import io.github.aritouma1205.quietintentlauncher.torch.TorchState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * Issue #7 end-to-end coverage for TOOLS and the optional system service
 * (design 7, 13): tool order/visibility, torch state honesty, the one-shot
 * screenshot handshake and the per-feature switches for notifications and
 * screen-off. Service/torch/timer behaviour is driven through the seams so
 * every branch is deterministic.
 */
@RunWith(AndroidJUnit4::class)
class ToolsIntegrationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: HomeViewModel
    private lateinit var container: io.github.aritouma1205.quietintentlauncher.AppContainer
    private lateinit var originalA11yCheck: () -> Boolean

    private fun res(id: Int): String = rule.activity.getString(id)

    @Before
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        container = app.container
        originalA11yCheck = container.isAccessibilityActive
        runBlocking {
            container.settingsStore.state.first { it is SettingsState.Ready }
            container.settingsStore.update {
                it.copy(
                    introCompleted = true,
                    notificationHintShown = true,
                    tools = it.tools.copy(
                        openMode = ToolsOpenMode.Tap,
                        items = ToolItem.entries.map { t -> ToolSetting(t.id) },
                    ),
                    systemActions = it.systemActions.copy(
                        notificationsEnabled = false,
                        screenOffEnabled = false,
                        screenshotEnabled = false,
                    ),
                )
            }
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
                onOpenEvent = {},
                onOpenAccessibilitySettings = {},
            )
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertNull(viewModel.overlay.value)
    }

    @After
    fun tearDown() {
        // Every seam must return to its real implementation so the next
        // test class sees unmodified OS behaviour.
        container.torch.resetSeams()
        container.systemActions.enabledOverride = null
        container.systemActions.connectedOverride = null
        container.systemActions.actionRunner = null
        container.toolLauncher.timersAvailableCheck = null
        container.toolLauncher.timersLauncher = null
        container.isAccessibilityActive = originalA11yCheck
        viewModel.onBackgrounded()
        viewModel.closeOverlay()
    }

    private fun collectMessages(): Pair<CopyOnWriteArrayList<HomeMessage>, Job> {
        val received = CopyOnWriteArrayList<HomeMessage>()
        val job = CoroutineScope(Dispatchers.Main).launch {
            viewModel.messages.collect { received += it }
        }
        return received to job
    }

    /** Opens the DO panel and expands TOOLS so the tool rows are composed. */
    private fun openTools() {
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_do))
            .performTouchInput {
                down(center)
                up()
            }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.overlay.value == HomeOverlay.Do(toolsExpanded = false)
        }
        rule.onNodeWithText(res(R.string.tools_expand)).performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.overlay.value == HomeOverlay.Do(toolsExpanded = true)
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.toolRows.value.isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun setTools(items: List<ToolSetting>) {
        runBlocking {
            container.settingsStore.update {
                it.copy(tools = it.tools.copy(items = items))
            }
        }
        viewModel.refreshToolRows()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.toolRows.value.map { it.tool.id } ==
                items.filter { it.visible }.map { it.id }
        }
    }

    // ---- TOOLS rows --------------------------------------------------------

    @Test
    fun toolsRenderInConfiguredOrderAndSkipHiddenRows() {
        setTools(
            listOf(
                ToolSetting("timer"),
                ToolSetting("light", visible = false),
                ToolSetting("qr"),
                ToolSetting("calculator", visible = false),
                ToolSetting("screenshot", visible = false),
            ),
        )
        openTools()
        rule.onNodeWithText(res(R.string.tool_timer)).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.tool_qr)).assertIsDisplayed()
        rule.onAllNodesWithText(res(R.string.tool_light)).assertCountEquals(0)
        rule.onAllNodesWithText(res(R.string.tool_calculator)).assertCountEquals(0)
        assertEquals(
            listOf("timer", "qr"),
            viewModel.toolRows.value.map { it.tool.id },
        )
    }

    @Test
    fun unsetCalculatorTapOpensTheToolEditor() {
        openTools()
        rule.onNodeWithText(res(R.string.tool_calculator)).performClick()
        rule.waitForIdle()
        assertEquals(HomeScreen.DoSettings, viewModel.screen.value)
        assertEquals("calculator", viewModel.toolEditFocus.value)
    }

    @Test
    fun removedTargetMarksTheRowBlocked() {
        // Point the calculator at an app that does not exist: the row must
        // explain the vanished target instead of failing silently (design 7).
        setTools(
            ToolItem.entries.map { t ->
                if (t == ToolItem.Calculator) {
                    ToolSetting(
                        t.id,
                        target = io.github.aritouma1205.quietintentlauncher
                            .settings.StoredTarget.App("no.such.pkg/.Nope"),
                    )
                } else {
                    ToolSetting(t.id)
                }
            },
        )
        openTools()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.toolRows.value.first { it.tool == ToolItem.Calculator }
                .status == ToolStatus.Blocked(ToolUnavailable.TargetGone)
        }
        rule.onNodeWithText(res(R.string.tool_target_missing)).assertIsDisplayed()
    }

    // ---- Timer ---------------------------------------------------------------

    @Test
    fun timerOpensTheSystemTimerListAndNeverStartsOne() {
        val launches = AtomicInteger(0)
        container.toolLauncher.timersAvailableCheck = { true }
        container.toolLauncher.timersLauncher = {
            launches.incrementAndGet()
            true
        }
        openTools()
        rule.onNodeWithText(res(R.string.tool_timer)).performClick()
        rule.waitForIdle()
        // Only ACTION_SHOW_TIMERS ran — the launcher holds no timer of its
        // own, so nothing can start silently (design 7).
        assertEquals(1, launches.get())
        assertNull(viewModel.overlay.value)
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
    }

    @Test
    fun timerWithoutHandlerOffersTheEditor() {
        val (received, job) = collectMessages()
        try {
            container.toolLauncher.timersAvailableCheck = { false }
            openTools()
            rule.onNodeWithText(res(R.string.tool_timer)).performClick()
            rule.waitUntil(timeoutMillis = 5_000) {
                received.contains(HomeMessage.ToolNoTimerHandler)
            }
            assertEquals(HomeScreen.DoSettings, viewModel.screen.value)
            assertEquals("timer", viewModel.toolEditFocus.value)
        } finally {
            job.cancel()
        }
    }

    // ---- Light / torch ---------------------------------------------------------

    @Test
    fun lightRowMirrorsTheOsTorchCallback() {
        // The write path is stubbed but reports nothing; the row only
        // changes when the OS callback reports it (design 7: real state).
        container.torch.flashCameraIdProvider = { "cam0" }
        container.torch.permissionCheck = { true }
        val writes = CopyOnWriteArrayList<Boolean>()
        container.torch.torchModeWriter = { _, on -> writes += on }
        container.torch.callbackRegistration = { }

        openTools()
        // Hardware state differs per device; drive the OS callback to Off
        // so the starting point is deterministic.
        rule.runOnUiThread {
            container.torch.torchCallback.onTorchModeChanged("cam0", false)
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.toolRows.value.first { it.tool == ToolItem.Light }
                .status == ToolStatus.LightOff
        }
        // A successful write with no callback yet keeps the row at Off —
        // the UI never claims a state the OS has not confirmed.
        rule.onNodeWithText(res(R.string.tool_light)).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { writes == listOf(true) }
        assertEquals(
            ToolStatus.LightOff,
            viewModel.toolRows.value.first { it.tool == ToolItem.Light }.status,
        )
        // Now the OS reports the torch went on.
        rule.runOnUiThread {
            container.torch.torchCallback.onTorchModeChanged("cam0", true)
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.toolRows.value.first { it.tool == ToolItem.Light }
                .status == ToolStatus.LightOn
        }
        rule.onNodeWithText(res(R.string.tool_state_on)).assertIsDisplayed()
        // Turning it off goes through the same path.
        rule.onNodeWithText(res(R.string.tool_light)).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { writes == listOf(true, false) }
        rule.runOnUiThread {
            container.torch.torchCallback.onTorchModeChanged("cam0", false)
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.toolRows.value.first { it.tool == ToolItem.Light }
                .status == ToolStatus.LightOff
        }
    }

    @Test
    fun lightOnFlashlessDeviceIsBlocked() {
        val (received, job) = collectMessages()
        try {
            container.torch.flashCameraIdProvider = { null }
            container.torch.permissionCheck = { true }
            container.torch.callbackRegistration = { }
            openTools()
            // The first tap discovers the missing flash through setTorch —
            // that flips the row to its blocked state (design 7).
            rule.onNodeWithText(res(R.string.tool_light)).performClick()
            rule.waitUntil(timeoutMillis = 5_000) {
                received.contains(HomeMessage.ToolNoFlash)
            }
            rule.waitUntil(timeoutMillis = 5_000) {
                viewModel.toolRows.value.first { it.tool == ToolItem.Light }
                    .status == ToolStatus.Blocked(ToolUnavailable.NoFlash)
            }
            rule.onNodeWithText(res(R.string.tool_no_flash)).assertIsDisplayed()
        } finally {
            job.cancel()
        }
    }

    @Test
    fun cameraBusyMapsToTheBusyMessage() {
        val (received, job) = collectMessages()
        try {
            container.torch.flashCameraIdProvider = { "cam0" }
            container.torch.permissionCheck = { true }
            container.torch.torchModeWriter = { _, _ ->
                throw CameraAccessException(CameraAccessException.CAMERA_IN_USE)
            }
            container.torch.callbackRegistration = { }
            openTools()
            rule.onNodeWithText(res(R.string.tool_light)).performClick()
            rule.waitUntil(timeoutMillis = 5_000) {
                received.contains(HomeMessage.ToolLightBusy)
            }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun missingCameraPermissionRequestsItFirst() {
        val requested = CopyOnWriteArrayList<Unit>()
        val job = CoroutineScope(Dispatchers.Main).launch {
            viewModel.cameraPermissionRequests.collect { requested += Unit }
        }
        try {
            container.torch.flashCameraIdProvider = { "cam0" }
            container.torch.permissionCheck = { false }
            container.torch.callbackRegistration = { }
            val writes = CopyOnWriteArrayList<Boolean>()
            container.torch.torchModeWriter = { _, on -> writes += on }
            openTools()
            rule.onNodeWithText(res(R.string.tool_light)).performClick()
            rule.waitUntil(timeoutMillis = 5_000) { requested.isNotEmpty() }
            // The write never ran without the permission (design 13).
            assertTrue(writes.isEmpty())
            // Dismiss the system permission dialog the launcher opened so
            // it cannot intercept the next test's input.
            InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        } finally {
            job.cancel()
        }
    }

    // ---- Screenshot --------------------------------------------------------------

    private fun armScreenshotService(recorded: CopyOnWriteArrayList<SystemAction>) {
        container.systemActions.enabledOverride = { true }
        container.systemActions.connectedOverride = { true }
        container.systemActions.actionRunner = { action ->
            recorded += action
            true
        }
    }

    @Test
    fun screenshotFiresExactlyOnceAfterThePanelSettles() {
        val recorded = CopyOnWriteArrayList<SystemAction>()
        armScreenshotService(recorded)
        runBlocking {
            container.settingsStore.update {
                it.copy(
                    systemActions = it.systemActions.copy(
                        screenshotEnabled = true,
                    ),
                )
            }
        }
        openTools()
        rule.onNodeWithText(res(R.string.tool_screenshot)).performClick()
        // The panel closes first; the OS request lands once the reveal has
        // fully settled (design 7).
        rule.waitUntil(timeoutMillis = 5_000) {
            recorded.toList() == listOf(SystemAction.TakeScreenshot)
        }
        assertNull(viewModel.overlay.value)
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        // A late extra settle cannot produce a second OS screenshot.
        viewModel.onRevealSettled()
        rule.waitForIdle()
        assertEquals(listOf(SystemAction.TakeScreenshot), recorded.toList())
    }

    @Test
    fun aSecondTapWhileArmedNeverDoublesTheScreenshot() {
        val (received, job) = collectMessages()
        try {
            val recorded = CopyOnWriteArrayList<SystemAction>()
            armScreenshotService(recorded)
            runBlocking {
                container.settingsStore.update {
                    it.copy(
                        systemActions = it.systemActions.copy(
                            screenshotEnabled = true,
                        ),
                    )
                }
            }
            // ViewModel calls run on the main thread so a Toast emitted by
            // the UI collector has a Looper to post to.
            rule.runOnUiThread { viewModel.requestScreenshot() }
            // Second tap while the first is still armed: refused.
            rule.runOnUiThread { viewModel.requestScreenshot() }
            rule.runOnUiThread { viewModel.onRevealSettled() }
            rule.waitForIdle()
            assertEquals(listOf(SystemAction.TakeScreenshot), recorded.toList())
            assertTrue(received.contains(HomeMessage.LaunchBusy))
        } finally {
            job.cancel()
        }
    }

    @Test
    fun anArmedScreenshotDiesWithBackgrounding() {
        val recorded = CopyOnWriteArrayList<SystemAction>()
        armScreenshotService(recorded)
        runBlocking {
            container.settingsStore.update {
                it.copy(
                    systemActions = it.systemActions.copy(
                        screenshotEnabled = true,
                    ),
                )
            }
        }
        rule.runOnUiThread { viewModel.requestScreenshot() }
        rule.runOnUiThread { viewModel.onBackgrounded() }
        // The stale settle after coming back must not shoot another app's
        // screen (design 7).
        rule.runOnUiThread { viewModel.onForegrounded() }
        rule.runOnUiThread { viewModel.onRevealSettled() }
        rule.waitForIdle()
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun anArmedScreenshotDiesWhenLeavingQuiet() {
        val recorded = CopyOnWriteArrayList<SystemAction>()
        armScreenshotService(recorded)
        runBlocking {
            container.settingsStore.update {
                it.copy(
                    systemActions = it.systemActions.copy(
                        screenshotEnabled = true,
                    ),
                )
            }
        }
        rule.runOnUiThread { viewModel.requestScreenshot() }
        rule.runOnUiThread { viewModel.nav.navigateTo(HomeScreen.Search) }
        rule.runOnUiThread { viewModel.onRevealSettled() }
        rule.waitForIdle()
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun screenshotWithTheSwitchOffOpensSystemSettings() {
        val recorded = CopyOnWriteArrayList<SystemAction>()
        armScreenshotService(recorded)
        openTools()
        rule.onNodeWithText(res(R.string.tool_screenshot)).performClick()
        rule.waitForIdle()
        // The fallback route lands on システム操作 settings where the switch
        // and the service state live (design 7 代替導線).
        assertEquals(HomeScreen.SystemSettings, viewModel.screen.value)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun screenshotWithoutTheServiceReportsOff() {
        val (received, job) = collectMessages()
        try {
            container.systemActions.enabledOverride = { true }
            container.systemActions.connectedOverride = { false }
            runBlocking {
                container.settingsStore.update {
                    it.copy(
                        systemActions = it.systemActions.copy(
                            screenshotEnabled = true,
                        ),
                    )
                }
            }
            openTools()
            rule.onNodeWithText(res(R.string.tool_screenshot)).performClick()
            rule.waitUntil(timeoutMillis = 5_000) {
                received.contains(HomeMessage.SystemServiceOff)
            }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun serviceRevokedBetweenArmAndSettleCancelsTheScreenshot() {
        val (received, job) = collectMessages()
        try {
            val recorded = CopyOnWriteArrayList<SystemAction>()
            var connected = true
            container.systemActions.enabledOverride = { true }
            container.systemActions.connectedOverride = { connected }
            container.systemActions.actionRunner = { action ->
                recorded += action
                true
            }
            runBlocking {
                container.settingsStore.update {
                    it.copy(
                        systemActions = it.systemActions.copy(
                            screenshotEnabled = true,
                        ),
                    )
                }
            }
            rule.runOnUiThread { viewModel.requestScreenshot() }
            // The service is revoked after the request was armed; the
            // settle-time re-check must drop the OS call (design 13).
            connected = false
            rule.runOnUiThread { viewModel.onRevealSettled() }
            rule.waitForIdle()
            assertTrue(recorded.isEmpty())
            assertTrue(received.contains(HomeMessage.SystemActionFailed))
        } finally {
            job.cancel()
        }
    }

    // ---- Swipe down / double tap ---------------------------------------------------

    @Test
    fun swipeDownWithNotificationsOffShowsTheHintOnce() {
        val (received, job) = collectMessages()
        try {
            runBlocking {
                container.settingsStore.update {
                    it.copy(notificationHintShown = false)
                }
            }
            rule.waitUntil(timeoutMillis = 5_000) {
                (
                    (viewModel.settingsState.value as? SettingsState.Ready)
                        ?.data?.notificationHintShown
                ) == false
            }
            rule.onRoot().performTouchInput {
                val start = Offset(width * 0.5f, height * 0.4f)
                down(start)
                moveTo(start + Offset(0f, 400f))
                up()
            }
            rule.waitUntil(timeoutMillis = 5_000) {
                received.contains(HomeMessage.NotificationHint)
            }
            // The opt-in hint fires once only (design 5).
            val count = received.count { it == HomeMessage.NotificationHint }
            rule.onRoot().performTouchInput {
                val start = Offset(width * 0.5f, height * 0.4f)
                down(start)
                moveTo(start + Offset(0f, 400f))
                up()
            }
            rule.waitForIdle()
            assertEquals(count, received.count {
                it == HomeMessage.NotificationHint
            })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun swipeDownWithServiceOffReportsUnavailable() {
        val (received, job) = collectMessages()
        try {
            container.systemActions.enabledOverride = { true }
            container.systemActions.connectedOverride = { false }
            runBlocking {
                container.settingsStore.update {
                    it.copy(
                        systemActions = it.systemActions.copy(
                            notificationsEnabled = true,
                        ),
                    )
                }
            }
            rule.runOnUiThread {
                viewModel.onFreeAreaEvent(FreeAreaEvent.SwipeDown)
            }
            rule.waitUntil(timeoutMillis = 5_000) {
                received.contains(HomeMessage.SystemServiceOff)
            }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun swipeDownRunsTheNotificationActionThroughTheService() {
        val recorded = CopyOnWriteArrayList<SystemAction>()
        armScreenshotService(recorded)
        runBlocking {
            container.settingsStore.update {
                it.copy(
                    systemActions = it.systemActions.copy(
                        notificationsEnabled = true,
                    ),
                )
            }
        }
        rule.runOnUiThread {
            viewModel.onFreeAreaEvent(FreeAreaEvent.SwipeDown)
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            recorded.toList() == listOf(SystemAction.Notifications)
        }
    }

    @Test
    fun aFailedActionReportsTheFailureNotASuccess() {
        val (received, job) = collectMessages()
        try {
            container.systemActions.enabledOverride = { true }
            container.systemActions.connectedOverride = { true }
            // The service is connected but the OS refuses the global
            // action — the launcher must say so, never claim success.
            container.systemActions.actionRunner = { false }
            runBlocking {
                container.settingsStore.update {
                    it.copy(
                        systemActions = it.systemActions.copy(
                            notificationsEnabled = true,
                        ),
                    )
                }
            }
            rule.runOnUiThread {
                viewModel.onFreeAreaEvent(FreeAreaEvent.SwipeDown)
            }
            rule.waitUntil(timeoutMillis = 5_000) {
                received.contains(HomeMessage.SystemActionFailed)
            }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun doubleTapRunsScreenOffThroughTheService() {
        val recorded = CopyOnWriteArrayList<SystemAction>()
        armScreenshotService(recorded)
        runBlocking {
            container.settingsStore.update {
                it.copy(
                    systemActions = it.systemActions.copy(
                        screenOffEnabled = true,
                    ),
                )
            }
        }
        rule.runOnUiThread {
            viewModel.onFreeAreaEvent(FreeAreaEvent.DoubleTap)
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            recorded.toList() == listOf(SystemAction.LockScreen)
        }
    }

    @Test
    fun doubleTapWithScreenOffDisabledDoesNothing() {
        val recorded = CopyOnWriteArrayList<SystemAction>()
        armScreenshotService(recorded)
        rule.runOnUiThread {
            viewModel.onFreeAreaEvent(FreeAreaEvent.DoubleTap)
        }
        rule.waitForIdle()
        assertTrue(recorded.isEmpty())
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun screenReaderActiveDisablesTheDoubleTapHold() {
        // With a screen reader on, the first tap must fire immediately —
        // the home gesture never intercepts the assistive double tap
        // (design 12). Two taps then toggle GLANCE on and back off.
        container.isAccessibilityActive = { true }
        runBlocking {
            container.settingsStore.update {
                it.copy(
                    systemActions = it.systemActions.copy(
                        screenOffEnabled = true,
                    ),
                )
            }
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            (
                (viewModel.settingsState.value as? SettingsState.Ready)
                    ?.data?.systemActions?.screenOffEnabled
            ) == true
        }
        rule.onRoot().performTouchInput {
            val point = Offset(width * 0.4f, height * 0.5f)
            down(point)
            up()
        }
        // Tap fires without waiting for the double-tap window.
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.overlay.value == HomeOverlay.Glance
        }
        rule.onRoot().performTouchInput {
            val point = Offset(width * 0.4f, height * 0.5f)
            down(point)
            up()
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.overlay.value == null
        }
    }
}
