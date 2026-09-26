package io.github.aritouma1205.quietintentlauncher.apps

import android.content.ComponentName
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.MainActivity
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.context.ContextRules
import io.github.aritouma1205.quietintentlauncher.home.HomeScreen
import io.github.aritouma1205.quietintentlauncher.home.HomeViewModel
import io.github.aritouma1205.quietintentlauncher.home.QuietLauncherRoot
import io.github.aritouma1205.quietintentlauncher.settings.DoActionDefaults
import io.github.aritouma1205.quietintentlauncher.settings.SettingsData
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
 * Categorized all-apps grid (design 9.3, Issue #17): 「最近」 section first
 * from local launch history, then ApplicationInfo.category sections in the
 * fixed order, empty categories hidden, tap to launch, long-press for app
 * info, and settings/back navigation reachable while the catalog loads.
 */
@RunWith(AndroidJUnit4::class)
class AllAppsIntegrationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: HomeViewModel

    private fun res(id: Int, vararg args: Any): String =
        rule.activity.getString(id, *args)

    private fun ownComponent(): ComponentName = ComponentName(
        InstrumentationRegistry.getInstrumentation().targetContext,
        MainActivity::class.java,
    )

    private fun ownAppTarget(): StoredTarget.App = StoredTarget.App(
        ownComponent().flattenToShortString(),
    )

    private fun entry(
        pkg: String,
        label: String,
        category: AppCategory,
        cls: String = "$pkg.Main",
    ): AppEntry = AppEntry(
        component = ComponentName(pkg, cls),
        user = Process.myUserHandle(),
        label = label,
        category = category,
    )

    private fun ownEntry(category: AppCategory = AppCategory.Productivity): AppEntry =
        AppEntry(
            component = ownComponent(),
            user = Process.myUserHandle(),
            label = "Quiet Intent Launcher",
            category = category,
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

    private fun openAllApps() {
        viewModel.nav.navigateTo(HomeScreen.AllApps)
        rule.waitForIdle()
        assertEquals(HomeScreen.AllApps, viewModel.screen.value)
        rule.onNodeWithText(res(R.string.all_apps_title)).assertIsDisplayed()
    }

    private fun seedCatalog(entries: List<AppEntry>) {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        app.container.appCatalog.replaceAppsForTest(entries)
        rule.waitForIdle()
    }

    private fun topOf(text: String): Float =
        rule.onAllNodesWithText(text).onFirst()
            .fetchSemanticsNode().boundsInRoot.top

    @Test
    fun gridShowsCategorySectionsInFixedOrder() {
        seedCatalog(
            listOf(
                entry("pkg.zeta", "Zeta", AppCategory.Other),
                entry("pkg.game", "Game App", AppCategory.Game),
                entry("pkg.sns1", "Chat", AppCategory.Social),
                entry("pkg.sns2", "Talk", AppCategory.Social),
            ),
        )
        openAllApps()

        rule.onNodeWithText(res(R.string.app_category_social)).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.app_category_game)).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.app_category_other)).assertIsDisplayed()

        // Fixed order: SNS → … → ゲーム → その他 (design 9.3).
        assertTrue(topOf(res(R.string.app_category_social)) < topOf(res(R.string.app_category_game)))
        assertTrue(topOf(res(R.string.app_category_game)) < topOf(res(R.string.app_category_other)))

        rule.onNodeWithText("Chat").assertIsDisplayed()
        rule.onNodeWithText("Talk").assertIsDisplayed()
        rule.onNodeWithText("Game App").assertIsDisplayed()
        rule.onNodeWithText("Zeta").assertIsDisplayed()
    }

    @Test
    fun emptyCategoriesDoNotRender() {
        seedCatalog(listOf(entry("pkg.sns", "Chat", AppCategory.Social)))
        openAllApps()

        rule.onAllNodesWithText(res(R.string.app_category_audio)).assertCountEquals(0)
        rule.onAllNodesWithText(res(R.string.app_category_video)).assertCountEquals(0)
        rule.onAllNodesWithText(res(R.string.app_category_news)).assertCountEquals(0)
        rule.onAllNodesWithText(res(R.string.app_category_game)).assertCountEquals(0)
    }

    @Test
    fun recentSectionIsFirstAndAppStaysInCategory() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        runBlocking { app.container.recentStore.record(ownAppTarget()) }
        seedCatalog(
            listOf(
                ownEntry(AppCategory.Productivity),
                entry("pkg.game", "Game App", AppCategory.Game),
            ),
        )
        rule.waitUntil(timeoutMillis = 5_000) { viewModel.recentRows.value.isNotEmpty() }
        openAllApps()

        val recent = res(R.string.search_recent_section)
        rule.onNodeWithText(recent).assertIsDisplayed()
        // 「最近」 precedes every category header (design 9.3).
        assertTrue(
            topOf(recent) < topOf(res(R.string.app_category_productivity)),
        )
        assertTrue(topOf(recent) < topOf(res(R.string.app_category_game)))
        // 重複許可: the app appears under 最近 AND under its category.
        rule.onAllNodesWithText("Quiet Intent Launcher")
            .assertCountEquals(2)
    }

    @Test
    fun emptyHistoryHidesRecentSection() {
        seedCatalog(listOf(entry("pkg.sns", "Chat", AppCategory.Social)))
        openAllApps()
        rule.onAllNodesWithText(res(R.string.search_recent_section))
            .assertCountEquals(0)
    }

    @Test
    fun tapCellLaunchesAndReturnsToQuiet() {
        seedCatalog(listOf(ownEntry()))
        openAllApps()

        rule.onNodeWithText("Quiet Intent Launcher").performClick()
        rule.waitForIdle()
        // Successful launch returns home to Quiet (design 3).
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun longPressShowsAppInfoDialog() {
        seedCatalog(listOf(entry("pkg.game", "Game App", AppCategory.Game)))
        openAllApps()

        rule.onNodeWithText("Game App").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("pkg.game").assertIsDisplayed()
        rule.onNodeWithText(res(R.string.app_info)).assertIsDisplayed()

        rule.onNodeWithText(res(R.string.app_info)).performClick()
        rule.waitForIdle()
        // App info is an external OS hand-off; the screen itself is unchanged.
        assertEquals(HomeScreen.AllApps, viewModel.screen.value)
    }

    @Test
    fun backNavigationWorksWhileCatalogLoads() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        app.container.appCatalog.replaceAppsForTest(null)
        openAllApps()

        rule.onNodeWithText(res(R.string.back)).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.back)).performClick()
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
    }

    @Test
    fun emptyCatalogShowsEmptyState() {
        seedCatalog(emptyList())
        openAllApps()
        rule.onNodeWithText(res(R.string.all_apps_empty)).assertIsDisplayed()
    }
}
