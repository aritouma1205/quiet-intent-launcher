package io.github.aritouma1205.quietintentlauncher.settings

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.MainActivity
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.home.HomeScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I41-T08 (Issue #41): the contract's other side — a configuration change
 * that keeps the screen on the stack top must keep the unsaved draft.
 * Driven through the real MainActivity so scenario.recreate() exercises
 * the genuine save/restore path the ViewModel survives.
 */
@RunWith(AndroidJUnit4::class)
class StackDraftRecreateTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val activity get() = rule.activity
    private val app get() =
        instrumentation.targetContext.applicationContext as QuietLauncherApp

    private lateinit var originalSettings: SettingsData

    private fun res(id: Int): String = activity.getString(id)

    @Before
    fun setUp() {
        runBlocking {
            originalSettings = (
                app.container.settingsStore.state
                    .first { it is SettingsState.Ready } as SettingsState.Ready
                ).data
            app.container.settingsStore.update {
                it.copy(
                    introCompleted = true,
                    tools = it.tools.copy(openMode = ToolsOpenMode.Tap),
                )
            }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            app.container.settingsStore.update {
                originalSettings.copy(introCompleted = true)
            }
        }
    }

    @Test
    fun unsavedDraftSurvivesRecreation() {
        rule.runOnUiThread {
            activity.viewModel.nav.navigateTo(HomeScreen.EdgeSettings)
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(res(R.string.edge_settings_title))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Dirty the draft: switch the TOOLS open mode chip.
        rule.onNodeWithText(res(R.string.edge_tools_deep))
            .performScrollTo()
            .performClick()
        rule.onNodeWithText(res(R.string.edge_tools_deep)).assertIsSelected()

        // Configuration change: the ViewModel (epoch + session id) and the
        // stack top both survive, so the draft must restore.
        rule.activityRule.scenario.recreate()

        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(res(R.string.edge_settings_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(res(R.string.edge_tools_deep))
            .performScrollTo()
            .assertIsSelected()
    }
}
