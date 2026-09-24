package io.github.aritouma1205.quietintentlauncher.home

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.MainActivity
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.settings.DerivedOp
import io.github.aritouma1205.quietintentlauncher.settings.DoAction
import io.github.aritouma1205.quietintentlauncher.settings.DoActionDefaults
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import io.github.aritouma1205.quietintentlauncher.settings.ToolsOpenMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DO action flow end to end (design 6, A06): the panel renders the
 * configured actions, tapping a set target launches it and returns to
 * Quiet, an unset action opens the editor, a long press shows derived ops
 * plus the edit entry, and the edit screen validates/persists/cancels.
 */
@RunWith(AndroidJUnit4::class)
class DoActionIntegrationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: HomeViewModel

    private fun res(id: Int): String = rule.activity.getString(id)

    private fun ownAppTarget(): StoredTarget.App = StoredTarget.App(
        ComponentName(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java,
        ).flattenToShortString(),
    )

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
                    actions = DoActionDefaults.defaults(),
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
            )
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertNull(viewModel.overlay.value)
    }

    private fun openDoPanel() {
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_do))
            .performTouchInput {
                down(center)
                up()
            }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.actionRows.value.size == 6
        }
        rule.waitForIdle()
        assertEquals(HomeOverlay.Do(toolsExpanded = false), viewModel.overlay.value)
    }

    private fun setActions(
        transform: (
            List<DoAction>,
        ) -> List<DoAction>,
        waitFor: (ActionRow) -> Boolean,
    ) {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        runBlocking {
            app.container.settingsStore.update {
                it.copy(actions = transform(it.actions))
            }
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.actionRows.value.any(waitFor)
        }
        rule.waitForIdle()
    }

    @Test
    fun doPanelRendersConfiguredActions() {
        openDoPanel()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.actionRows.value.size == 6
        }
        listOf("撮る", "話す", "聴く", "見る", "移動する", "調べる").forEach { name ->
            rule.onNodeWithText(name).assertIsDisplayed()
        }
        rule.onAllNodesWithText(res(R.string.do_target_unset))
            .assertCountEquals(6)
    }

    @Test
    fun tappingActionWithTargetLaunchesAndReturnsQuiet() {
        setActions(
            transform = { actions ->
                actions.map {
                    if (it.name == "撮る") it.copy(target = ownAppTarget()) else it
                }
            },
            waitFor = { it.action.name == "撮る" && it.status is ActionStatus.Available },
        )
        openDoPanel()
        rule.onNodeWithText("撮る").performClick()
        rule.waitForIdle()
        // Launching our own MainActivity counts as a successful external
        // launch: the panel closes and home returns to Quiet (design 3, 6).
        assertNull(viewModel.overlay.value)
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
    }

    @Test
    fun tappingUnsetActionOpensEditor() {
        openDoPanel()
        rule.onNodeWithText("聴く").performClick()
        rule.waitForIdle()
        assertNull(viewModel.overlay.value)
        assertEquals(HomeScreen.DoSettings, viewModel.screen.value)
        rule.onNodeWithText(res(R.string.do_settings_title)).assertIsDisplayed()
    }

    @Test
    fun longPressShowsDerivedOpsAndEditEntry() {
        setActions(
            transform = { actions ->
                actions.map {
                    if (it.name == "撮る") {
                        it.copy(
                            derivedOps = listOf(
                                DerivedOp(label = "動画", target = ownAppTarget()),
                            ),
                        )
                    } else {
                        it
                    }
                }
            },
            waitFor = {
                it.action.name == "撮る" && it.action.derivedOps.isNotEmpty()
            },
        )
        openDoPanel()
        rule.onNodeWithText("撮る").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("動画").assertIsDisplayed()
        rule.onNodeWithText(res(R.string.do_edit_action)).assertIsDisplayed()

        // The derived op launches through the same shared path.
        rule.onNodeWithText("動画").performClick()
        rule.waitForIdle()
        assertNull(viewModel.overlay.value)
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
    }

    @Test
    fun longPressEditEntryOpensEditor() {
        openDoPanel()
        rule.onNodeWithText("見る").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.do_edit_action)).performClick()
        rule.waitForIdle()
        assertEquals(HomeScreen.DoSettings, viewModel.screen.value)
    }

    @Test
    fun editRejectsBlankNameWithExplanation() {
        viewModel.openActionEditor(null)
        rule.waitForIdle()
        assertEquals(HomeScreen.DoSettings, viewModel.screen.value)

        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("   ")
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.action_error_name)).assertIsDisplayed()
        // Nothing was persisted.
        val data = (viewModel.settingsState.value as SettingsState.Ready).data
        assertEquals("撮る", data.actions.first().name)
    }

    @Test
    fun editSavePersistsRename() {
        viewModel.openActionEditor(null)
        rule.waitForIdle()

        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("写真")
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            (
                (viewModel.settingsState.value as? SettingsState.Ready)
                    ?.data?.actions?.first()?.name
            ) == "写真"
        }
        // Save returned to the previous screen.
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.screen.value == HomeScreen.Quiet
        }
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
    }

    @Test
    fun editCancelDiscardsDraft() {
        viewModel.openActionEditor(null)
        rule.waitForIdle()

        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("変えた")
        rule.onNodeWithText(res(R.string.cancel))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
        // The dirty draft asks before discarding (design 3).
        rule.onNodeWithText(res(R.string.unsaved_discard)).performClick()
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        val data = (viewModel.settingsState.value as SettingsState.Ready).data
        assertEquals("撮る", data.actions.first().name)
    }
}
