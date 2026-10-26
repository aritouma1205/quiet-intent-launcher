package io.github.aritouma1205.quietintentlauncher.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.AppContainer
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.home.HomeScreen
import io.github.aritouma1205.quietintentlauncher.home.HomeViewModel
import io.github.aritouma1205.quietintentlauncher.home.QuietLauncherRoot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #41 end-to-end coverage (design 3): a screen that leaves the
 * navigation-stack top loses its unsaved draft for good — every revisit
 * starts from the stored settings. Drives the real QuietLauncherRoot with
 * the real HomeNavigation, so the discard lands through the same publish
 * path production uses (back via the unsaved-changes dialog, backgrounded
 * reset, and the saved-value path after a successful save).
 */
@RunWith(AndroidJUnit4::class)
class StackDraftDiscardTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: HomeViewModel
    private lateinit var container: AppContainer
    private lateinit var originalSettings: SettingsData

    private fun res(id: Int): String = rule.activity.getString(id)

    @Before
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        container = app.container
        runBlocking {
            originalSettings = (
                container.settingsStore.state
                    .first { it is SettingsState.Ready } as SettingsState.Ready
                ).data
            container.settingsStore.update {
                it.copy(
                    introCompleted = true,
                    tools = it.tools.copy(openMode = ToolsOpenMode.Tap),
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
                onUninstallApp = {},
                onOpenEvent = {},
                onOpenAccessibilitySettings = {},
                onOpenMediaOutput = {},
            )
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
    }

    @After
    fun tearDown() {
        // A test that saved through the real store must not leak edits
        // into other classes sharing this app process.
        runBlocking {
            container.settingsStore.update {
                originalSettings.copy(introCompleted = true)
            }
        }
        viewModel.onBackgrounded()
    }

    private fun openEdgeSettings() {
        rule.runOnUiThread { viewModel.nav.navigateTo(HomeScreen.EdgeSettings) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(res(R.string.edge_settings_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun openDoSettings() {
        rule.runOnUiThread { viewModel.nav.navigateTo(HomeScreen.DoSettings) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(res(R.string.do_settings_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    /**
     * The confirm dialog lives in a Popup whose first layout can land
     * after the compose tree reports idle; poll for non-zero bounds
     * (same defence as DoSettingsDraftTest).
     */
    private fun assertUnsavedDialogShown() {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText(res(R.string.unsaved_title))
                .fetchSemanticsNodes()
                .any { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        }
        rule.onNodeWithText(res(R.string.unsaved_title)).assertIsDisplayed()
    }

    /** Accordion rows start collapsed; the merged row node opens the editor. */
    private fun expandAction(name: String) {
        rule.onNode(hasClickAction() and hasText(name))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
    }

    // I41-T07a: EdgeSettings dirty edit → screen-exit → 破棄する → revisit
    // shows the stored initial state and no leftover confirm dialog.
    @Test
    fun edgeDraftIsGoneAfterDiscardExitAndRevisit() {
        openEdgeSettings()
        // Dirty the draft: switch the TOOLS open mode chip.
        rule.onNodeWithText(res(R.string.edge_tools_deep))
            .performScrollTo()
            .performClick()
        rule.onNodeWithText(res(R.string.edge_tools_deep)).assertIsSelected()

        // Leave via the screen's exit path and discard in the dialog.
        rule.onNodeWithText(res(R.string.back)).performClick()
        assertUnsavedDialogShown()
        rule.onNodeWithText(res(R.string.unsaved_discard)).performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.screen.value == HomeScreen.Quiet
        }

        openEdgeSettings()
        rule.onNodeWithText(res(R.string.edge_tools_tap))
            .performScrollTo()
            .assertIsSelected()
        rule.onAllNodesWithText(res(R.string.unsaved_title))
            .assertCountEquals(0)
    }

    // I41-T07b: EdgeSettings dirty edit → onBackgrounded() → Quiet →
    // revisit starts from the stored values — no confirmation involved.
    @Test
    fun edgeDraftIsGoneAfterBackgrounding() {
        openEdgeSettings()
        rule.onNodeWithText(res(R.string.edge_tools_deep))
            .performScrollTo()
            .performClick()
        rule.onNodeWithText(res(R.string.edge_tools_deep)).assertIsSelected()

        rule.runOnUiThread { viewModel.onBackgrounded() }
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.screen.value == HomeScreen.Quiet
        }
        rule.runOnUiThread { viewModel.onForegrounded() }

        openEdgeSettings()
        rule.onNodeWithText(res(R.string.edge_tools_tap))
            .performScrollTo()
            .assertIsSelected()
        rule.onAllNodesWithText(res(R.string.unsaved_title))
            .assertCountEquals(0)
    }

    // I41-T07c: DoSettings rename → screen-exit → 破棄する → revisit shows
    // the still-stored name, not the discarded edit.
    @Test
    fun doDraftIsGoneAfterDiscardExitAndRevisit() {
        val storedName = originalSettings.actions.first().name
        openDoSettings()
        expandAction(storedName)
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("破棄される名前")
        rule.waitForIdle()

        rule.onNodeWithText(res(R.string.back)).performClick()
        assertUnsavedDialogShown()
        rule.onNodeWithText(res(R.string.unsaved_discard)).performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.screen.value == HomeScreen.Quiet
        }

        openDoSettings()
        expandAction(storedName)
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(storedName),
                ),
            )
    }

    // I41-T10: a save that succeeded flows through the same publish —
    // the revisit must show the stored value, not a stale draft and not
    // the pre-save initial either.
    @Test
    fun savedNameSurvivesRevisit() {
        val storedName = originalSettings.actions.first().name
        val savedName = "保存済みの名前"
        openDoSettings()
        expandAction(storedName)
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement(savedName)
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            viewModel.screen.value == HomeScreen.Quiet
        }

        openDoSettings()
        expandAction(savedName)
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(savedName),
                ),
            )
        val stored = (container.settingsStore.state.value as? SettingsState.Ready)
            ?.data?.actions?.first()?.name
        assertEquals(savedName, stored)
    }
}
