package io.github.aritouma1205.quietintentlauncher.home

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.MainActivity
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.context.ContextRule
import io.github.aritouma1205.quietintentlauncher.context.ContextRules
import io.github.aritouma1205.quietintentlauncher.settings.DoActionDefaults
import io.github.aritouma1205.quietintentlauncher.settings.SettingsData
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import io.github.aritouma1205.quietintentlauncher.settings.ToolsOpenMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Universal Search, 「最近」 and Context Slots end to end (design 9, 10):
 * swipe-up opens the full-screen search, results never auto-launch, the
 * external hand-off rows stay separated, successful launches feed 「最近」,
 * and matching Context Slot rows sit below the action list in DO.
 */
@RunWith(AndroidJUnit4::class)
class SearchContextIntegrationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: HomeViewModel

    private fun res(id: Int, vararg args: Any): String =
        rule.activity.getString(id, *args)

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
                    contextSlots = ContextRules.defaultSlots(),
                    search = SettingsData().search,
                )
            }
            container.recentStore.clear()
        }
        viewModel = HomeViewModel(container)
        rule.setContent {
            QuietLauncherRoot(
                viewModel = viewModel,
                iconLoader = { null },
                todayInfo = { container.todayData.current() },
                onRequestHomeRole = {},
                onRestoreHome = {},
                onChangeWallpaper = {},
                onOpenAppInfo = {},
            )
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertNull(viewModel.overlay.value)
    }

    private fun openSearch() {
        viewModel.onFreeAreaEvent(FreeAreaEvent.SwipeUp)
        rule.waitForIdle()
        assertEquals(HomeScreen.Search, viewModel.screen.value)
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
    }

    private fun setData(transform: (SettingsData) -> SettingsData) {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        runBlocking { app.container.settingsStore.update(transform) }
        rule.waitForIdle()
    }

    @Test
    fun swipeUpOpensSearchWithFixedEntries() {
        openSearch()
        rule.onNodeWithText(res(R.string.all_apps_entry)).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.settings_entry)).assertIsDisplayed()
        // No external hand-off while the query is empty (design 9.2).
        rule.onAllNodesWithText(res(R.string.search_web)).assertCountEquals(0)
    }

    @Test
    fun typedQueryShowsActionMatchWithoutLaunching() {
        openSearch()
        rule.onNode(hasSetTextAction())
            .performTextReplacement("撮る")
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.searchRows.value.isNotEmpty()
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("撮る").onFirst().assertIsDisplayed()
        // Enter/results never auto-launch: still on the search screen.
        assertEquals(HomeScreen.Search, viewModel.screen.value)
        rule.onNodeWithText(res(R.string.search_web)).assertIsDisplayed()
    }

    @Test
    fun noMatchStillShowsExternalAndFixedEntries() {
        openSearch()
        rule.onNode(hasSetTextAction())
            .performTextReplacement("zzz-no-match")
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.searchQuery.value == "zzz-no-match"
        }
        rule.waitUntil(timeoutMillis = 5_000) { viewModel.searchRows.value.isEmpty() }
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.search_empty_results)).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.search_web)).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.all_apps_entry)).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.settings_entry)).assertIsDisplayed()
    }

    @Test
    fun leavingAndReopeningDropsTheQuery() {
        openSearch()
        rule.onNode(hasSetTextAction())
            .performTextReplacement("撮る")
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.searchQuery.value == "撮る"
        }
        viewModel.nav.back()
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertEquals("", viewModel.searchQuery.value)
        openSearch()
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun successfulLaunchFromSearchIsRecorded() {
        // NOTE: the launched MainActivity (singleTask) covers the test
        // activity, so post-launch assertions stay on VM state only; the
        // 最近 section itself is covered by emptyQueryShowsRecents.
        setData { data ->
            data.copy(
                actions = data.actions.map {
                    if (it.name == "撮る") it.copy(target = ownAppTarget()) else it
                },
            )
        }
        openSearch()
        rule.onNode(hasSetTextAction())
            .performTextReplacement("撮る")
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.searchRows.value.isNotEmpty()
        }
        rule.onAllNodesWithText("撮る").onFirst().performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.screen.value == HomeScreen.Quiet
        }
        // The shared launch path recorded the successful launch.
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.recentRows.value.isNotEmpty()
        }
    }

    @Test
    fun emptyQueryShowsRecents() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        runBlocking { app.container.recentStore.record(ownAppTarget()) }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.recentRows.value.isNotEmpty()
        }
        openSearch()
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.search_recent_section))
            .assertIsDisplayed()
        // The recorded row shows the resolved app label.
        rule.onNodeWithText(res(R.string.app_name)).assertIsDisplayed()
    }

    @Test
    fun tappingRecentRowLaunchesItsTarget() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        runBlocking { app.container.recentStore.record(ownAppTarget()) }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.recentRows.value.isNotEmpty()
        }
        openSearch()
        rule.onNodeWithText(res(R.string.app_name)).performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.screen.value == HomeScreen.Quiet
        }
    }

    @Test
    fun contextSlotRowAppearsBelowActionsAndLaunches() {
        val actionId = DoActionDefaults.defaults().first { it.name == "撮る" }.id
        setData { data ->
            data.copy(
                actions = data.actions.map {
                    if (it.name == "撮る") it.copy(target = ownAppTarget()) else it
                },
                contextSlots = data.contextSlots.mapIndexed { i, slot ->
                    if (i == 0) {
                        slot.copy(
                            label = "朝のスロット",
                            rules = listOf(
                                ContextRule(daysOfWeek = emptySet(), actionId = actionId),
                            ),
                        )
                    } else {
                        slot
                    }
                },
            )
        }
        openDoPanel()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.contextRows.value.isNotEmpty()
        }
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.context_now_heading)).assertIsDisplayed()
        rule.onNodeWithText("朝のスロット").assertIsDisplayed()
        // The action name appears twice: its own row plus the context row.
        rule.onAllNodesWithText("撮る").assertCountEquals(2)
        // The context row (second occurrence) launches the shared path.
        rule.onAllNodesWithText("撮る")[1].performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.screen.value == HomeScreen.Quiet
        }
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun hiddenActionStillResolvesThroughItsSlot() {
        // The visible flag declutters the normal action list only; design 10
        // skips 起動先未設定・削除済み・無効 targets, so a hidden action an
        // explicitly configured slot points at still resolves.
        val actionId = DoActionDefaults.defaults().first { it.name == "撮る" }.id
        setData { data ->
            data.copy(
                actions = data.actions.map {
                    if (it.name == "撮る") {
                        it.copy(target = ownAppTarget(), visible = false)
                    } else {
                        it
                    }
                },
                contextSlots = data.contextSlots.mapIndexed { i, slot ->
                    if (i == 0) {
                        slot.copy(
                            rules = listOf(
                                ContextRule(daysOfWeek = emptySet(), actionId = actionId),
                            ),
                        )
                    } else {
                        slot
                    }
                },
            )
        }
        // openDoPanel's 6-row wait does not apply with one hidden action.
        rule.onNodeWithContentDescription(res(R.string.edge_bar_open_do))
            .performTouchInput {
                down(center)
                up()
            }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.contextRows.value.isNotEmpty()
        }
        rule.waitForIdle()
        // The action row itself is hidden, so 撮る appears exactly once.
        rule.onAllNodesWithText("撮る").assertCountEquals(1)
    }

    @Test
    fun contextSlotStaysHiddenWhenItsActionIsUnset() {
        val actionId = DoActionDefaults.defaults().first { it.name == "撮る" }.id
        setData { data ->
            data.copy(
                contextSlots = data.contextSlots.mapIndexed { i, slot ->
                    if (i == 0) {
                        slot.copy(
                            rules = listOf(
                                ContextRule(daysOfWeek = emptySet(), actionId = actionId),
                            ),
                        )
                    } else {
                        slot
                    }
                },
            )
        }
        openDoPanel()
        rule.waitForIdle()
        // The referenced action has no usable target: no いま section.
        rule.onAllNodesWithText(res(R.string.context_now_heading))
            .assertCountEquals(0)
        assertTrue(viewModel.contextRows.value.isEmpty())
    }

    @Test
    fun deletingAReferencedActionStripsContextReferences() {
        val actionId = DoActionDefaults.defaults().first { it.name == "撮る" }.id
        setData { data ->
            data.copy(
                contextSlots = data.contextSlots.mapIndexed { i, slot ->
                    if (i == 0) {
                        slot.copy(
                            rules = listOf(
                                ContextRule(daysOfWeek = emptySet(), actionId = actionId),
                            ),
                            defaultActionId = actionId,
                        )
                    } else {
                        slot
                    }
                },
            )
        }
        viewModel.openActionEditor(actionId)
        rule.waitForIdle()
        assertEquals(HomeScreen.DoSettings, viewModel.screen.value)

        // 撮る is the first card: its 削除 button is the first one.
        rule.onAllNodesWithText(res(R.string.action_delete))
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithText(
            res(R.string.action_delete_body_refs, "撮る"),
        ).assertIsDisplayed()
        // The dialog's confirm is appended after the card buttons.
        rule.onAllNodesWithText(res(R.string.action_delete))
            .onLast()
            .performClick()
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            val data = (viewModel.settingsState.value as? SettingsState.Ready)?.data
            data != null && data.actions.none { it.id == actionId }
        }
        val data = (viewModel.settingsState.value as SettingsState.Ready).data
        assertFalse(ContextRules.referencesAction(data.contextSlots, actionId))
    }

    @Test
    fun stoppingRecordingClearsStoredHistory() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        runBlocking { app.container.recentStore.record(ownAppTarget()) }
        rule.waitUntil(timeoutMillis = 5_000) {
            app.container.recentStore.entries.value.isNotEmpty()
        }

        viewModel.nav.navigateTo(HomeScreen.SearchSettings)
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.search_settings_title))
            .assertIsDisplayed()

        // The only switch on the screen is the recording toggle.
        rule.onNode(isToggleable()).performClick()
        rule.waitForIdle()
        // The confirmation dialog's button shares the 履歴をすべて消去
        // label with the screen button; the dialog one is composed last.
        rule.onAllNodesWithText(res(R.string.search_settings_clear))
            .onLast()
            .performClick()
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.screen.value != HomeScreen.SearchSettings
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            app.container.recentStore.entries.value.isEmpty()
        }
        val data = (viewModel.settingsState.value as SettingsState.Ready).data
        assertFalse(data.search.recentRecording)
    }

    @Test
    fun contextSlotsScreenRendersBothSlots() {
        viewModel.nav.navigateTo(HomeScreen.ContextSettings)
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.context_settings_title))
            .assertIsDisplayed()
        rule.onNodeWithText(res(R.string.context_slot_title, 1))
            .assertIsDisplayed()
        rule.onNodeWithText(res(R.string.context_slot_title, 2))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
