package io.github.aritouma1205.quietintentlauncher.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.context.ContextRules
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.settings.ToolsOpenMode
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end input path: real MotionEvents injected through Compose's
 * pointer-input pipeline must reach the gesture state machines and produce
 * the designed screen/overlay (design 4, 5). These are the regression cases
 * the state-machine unit tests cannot see.
 */
@RunWith(AndroidJUnit4::class)
class HomeInputIntegrationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: HomeViewModel

    private fun res(id: Int): String = rule.activity.getString(id)

    @Before
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        val container = app.container
        runBlocking {
            container.settingsStore.state.first { it is SettingsState.Ready }
            container.settingsStore.update {
                it.copy(
                    introCompleted = true,
                    notificationHintShown = true,
                    tools = it.tools.copy(openMode = ToolsOpenMode.Tap),
                    systemActions = it.systemActions.copy(
                        notificationsEnabled = false,
                        screenOffEnabled = false,
                    ),
                    // Persisted slots survive suite boundaries; a leftover
                    // rule renders its action as an extra DO row.
                    contextSlots = ContextRules.defaultSlots(),
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

    // ---- Bar -> panel -----------------------------------------------------

    @Test
    fun tapOnRightBarOpensDoPanel() {
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_do))
            .performTouchInput {
                down(center)
                up()
            }
        rule.waitForIdle()
        assertEquals(HomeOverlay.Do(toolsExpanded = false), viewModel.overlay.value)
        rule.onNodeWithText(res(R.string.panel_do_title)).assertIsDisplayed()
    }

    @Test
    fun tapOnLeftBarOpensTodayPanel() {
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_today))
            .performTouchInput {
                down(center)
                up()
            }
        rule.waitForIdle()
        assertEquals(HomeOverlay.Today, viewModel.overlay.value)
        rule.onNodeWithText(res(R.string.panel_today_title)).assertIsDisplayed()
    }

    @Test
    fun diagonalDragOnBarDoesNotOpenPanel() {
        // Reviewer repro: down on the right bar, then a large diagonal move
        // that never resolves to a direction; the release must close the
        // gesture, not fire a tap (design 4.2).
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_do))
            .performTouchInput {
                down(center)
                moveTo(center + Offset(-160f, 160f))
                up()
            }
        rule.waitForIdle()
        assertNull(viewModel.overlay.value)
        rule.onAllNodesWithText(res(R.string.panel_do_title)).assertCountEquals(0)
    }

    @Test
    fun osCancelOnBarDragDoesNotOpenPanel() {
        // Pulling past the open threshold and then losing the stream to the
        // OS (ACTION_CANCEL) must close, never open (design 4.2).
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_do))
            .performTouchInput {
                down(center)
                moveTo(center + Offset(-300f, 0f))
                cancel()
            }
        rule.waitForIdle()
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun movingSecondPointerCancelsBarDrag() {
        // Deliberate two-finger input still cancels (design 4.2): the
        // second contact operates — it moves — so the drag dies.
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_do))
            .performTouchInput {
                down(0, center)
                moveTo(0, center + Offset(-200f, 0f))
                down(1, center + Offset(-40f, 60f))
                moveTo(1, center + Offset(-120f, 60f))
                up(1)
                up(0)
            }
        rule.waitForIdle()
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun restingSecondPointerOnBarKeepsDrag() {
        // A palm resting on the bar itself is not a second input (r3):
        // the drag still opens the panel.
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_do))
            .performTouchInput {
                down(0, center)
                down(1, center + Offset(0f, 60f))
                moveTo(0, center + Offset(-400f, 0f))
                up(0)
                up(1)
            }
        rule.waitForIdle()
        assertEquals(HomeOverlay.Do(toolsExpanded = false), viewModel.overlay.value)
        rule.onNodeWithText(res(R.string.panel_do_title)).assertIsDisplayed()
    }

    @Test
    fun shortInwardPullBelowThresholdCloses() {
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_do))
            .performTouchInput {
                down(center)
                moveTo(center + Offset(-120f, 0f))
                up()
            }
        rule.waitForIdle()
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun deepPullFromRightBarOpensToolsWhenEnabled() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        runBlocking {
            app.container.settingsStore.update {
                it.copy(tools = it.tools.copy(openMode = ToolsOpenMode.DeepPull))
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_do))
            .performTouchInput {
                down(center)
                moveTo(center + Offset(-900f, 0f))
                up()
            }
        rule.waitForIdle()
        assertEquals(HomeOverlay.Do(toolsExpanded = true), viewModel.overlay.value)
    }

    // ---- Free area --------------------------------------------------------

    @Test
    fun tapOnFreeAreaTogglesGlance() {
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.4f, height * 0.5f))
            up()
        }
        rule.waitForIdle()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)
    }

    @Test
    fun swipeUpOnFreeAreaOpensSearch() {
        rule.onRoot().performTouchInput {
            val start = Offset(width * 0.5f, height * 0.6f)
            down(start)
            moveTo(start + Offset(0f, -400f))
            up()
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Search, viewModel.screen.value)
    }

    @Test
    fun longPressOnFreeAreaOpensEditSheet() {
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.4f, height * 0.5f))
            advanceEventTime(700)
            up()
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Edit, viewModel.screen.value)
        rule.onNodeWithText(res(R.string.edit_title)).assertIsDisplayed()
    }

    @Test
    fun horizontalMoveThenHoldDoesNotFireLongPress() {
        // Reviewer repro: down -> 350px sideways -> hold past the long-press
        // deadline -> release must stay silent; no edit sheet, no tap, no
        // GLANCE (design 5: horizontal swipes are unassigned and moved-out
        // input is never a tap).
        rule.onRoot().performTouchInput {
            val start = Offset(width * 0.35f, height * 0.5f)
            down(start)
            moveTo(start + Offset(350f, 0f))
            advanceEventTime(700)
            up()
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertNull(viewModel.overlay.value)
        rule.onAllNodesWithText(res(R.string.edit_title)).assertCountEquals(0)
    }

    @Test
    fun horizontalThenVerticalBendIsNotASwipe() {
        // A big horizontal leg locks the gesture; bending upward late in the
        // same input must not open search (design 5).
        rule.onRoot().performTouchInput {
            val start = Offset(width * 0.3f, height * 0.6f)
            down(start)
            moveTo(start + Offset(350f, 0f))
            moveTo(Offset(width * 0.3f + 350f, height * 0.2f))
            up()
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
    }

    @Test
    fun swipeStartedInsideBottomSystemAreaDoesNothing() {
        // A down inside the bottom system-gesture strip may not arm the
        // launcher swipe even if the finger travels up into the free area
        // (design 4.1: the OS home gesture wins there).
        rule.onRoot().performTouchInput {
            val start = Offset(width * 0.5f, height * 0.97f)
            down(start)
            moveTo(Offset(width * 0.5f, height * 0.5f))
            up()
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun movingSecondPointerOnFreeAreaSuppressesActions() {
        // Deliberate two-finger input still suppresses (design 4.2): the
        // second pointer moves like an operation, so the swipe must not
        // fire.
        rule.onRoot().performTouchInput {
            val start = Offset(width * 0.4f, height * 0.6f)
            down(0, start)
            moveTo(0, start + Offset(0f, -60f))
            down(1, start + Offset(120f, 0f))
            moveTo(1, start + Offset(240f, 0f))
            moveTo(0, start + Offset(0f, -500f))
            up(1)
            up(0)
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun restingSecondFingerKeepsTap() {
        // The r3 fix: a finger resting on the glass is not a second
        // input — the tap fires as usual.
        rule.onRoot().performTouchInput {
            val free = Offset(width * 0.4f, height * 0.5f)
            val rest = Offset(width * 0.7f, height * 0.7f)
            down(0, free)
            down(1, rest)
            up(0)
            up(1)
        }
        rule.waitForIdle()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)
    }

    @Test
    fun restingSecondPointerOnFreeAreaKeepsSwipe() {
        // Same rule for the swipe: the resting contact cannot cancel it.
        rule.onRoot().performTouchInput {
            val start = Offset(width * 0.5f, height * 0.6f)
            down(0, start)
            down(1, start + Offset(120f, 120f))
            moveTo(0, start + Offset(0f, -400f))
            up(0)
            up(1)
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Search, viewModel.screen.value)
    }

    @Test
    fun restingFingerOnBarKeepsFreeAreaLongPress() {
        // The r3 fix: one finger rests on the bar — outside this surface's
        // event stream — and never moves. It is not a second input, so
        // the long-press deadline still fires (design 4.2 relaxation).
        rule.onRoot().performTouchInput {
            val free = Offset(width * 0.4f, height * 0.5f)
            val bar = Offset(width - 10f, height * 0.5f)
            down(0, free)
            down(1, bar)
            advanceEventTime(800)
            up(1)
            up(0)
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(HomeScreen.Edit, viewModel.screen.value)
        rule.onNodeWithText(res(R.string.edit_title)).assertIsDisplayed()
    }

    @Test
    fun movingFingerOnBarSuppressesFreeAreaLongPress() {
        // Deliberate two-finger input still suppresses: the finger on the
        // bar moves like a real operation, which the shared latch reports.
        rule.onRoot().performTouchInput {
            val free = Offset(width * 0.4f, height * 0.5f)
            val bar = Offset(width - 10f, height * 0.5f)
            down(0, free)
            down(1, bar)
            moveTo(1, bar + Offset(0f, 120f))
            advanceEventTime(800)
            up(1)
            up(0)
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun heldSecondTapStillCountsAsDoubleTap() {
        // Reviewer repro (doubleTapEnabled): tap -> short gap -> press and
        // hold past the remaining window. The pending tap must not fire
        // while the second finger is still down.
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        runBlocking {
            app.container.settingsStore.update {
                it.copy(
                    systemActions = it.systemActions.copy(
                        screenOffEnabled = true,
                    ),
                )
            }
        }
        val received = CopyOnWriteArrayList<HomeMessage>()
        val collectJob = CoroutineScope(Dispatchers.Main).launch {
            viewModel.messages.collect { received += it }
        }
        try {
            // The modifier keys on screenOffEnabled and requestScreenOff
            // re-reads settingsState, so the store write must have reached
            // the StateFlow before the gesture runs.
            rule.waitUntil(timeoutMillis = 5_000) {
                (
                    (viewModel.settingsState.value as? SettingsState.Ready)
                        ?.data?.systemActions?.screenOffEnabled
                ) == true
            }
            rule.waitForIdle()
            rule.onRoot().performTouchInput {
                val point = Offset(width * 0.4f, height * 0.5f)
                down(point)
                advanceEventTime(20)
                up()
                advanceEventTime(100)
                down(point)
                advanceEventTime(250)
                up()
            }
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
            // DoubleTap reached the screen-off path (service off here, so
            // it reports SystemServiceOff), and no stray single tap toggled
            // GLANCE along the way.
            assertTrue(received.contains(HomeMessage.SystemServiceOff))
            assertNull(viewModel.overlay.value)
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun osCancelOnFreeAreaDoesNotFireActions() {
        // A cancel before any deadline must kill the pending long-press and
        // tap candidates for good, even if the clock advances afterwards.
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.4f, height * 0.5f))
            cancel()
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertNull(viewModel.overlay.value)
    }
}
