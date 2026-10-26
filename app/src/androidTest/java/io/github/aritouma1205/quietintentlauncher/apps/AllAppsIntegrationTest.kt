package io.github.aritouma1205.quietintentlauncher.apps

import android.content.ComponentName
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
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

    /** Package names handed to the injected onUninstallApp callback. */
    private val uninstallRequests = mutableListOf<String>()

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
        canUninstall: Boolean = false,
    ): AppEntry = AppEntry(
        component = ComponentName(pkg, cls),
        user = Process.myUserHandle(),
        label = label,
        category = category,
        canUninstall = canUninstall,
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
        uninstallRequests.clear()
        viewModel = HomeViewModel(container)
        rule.setContent {
            QuietLauncherRoot(
                viewModel = viewModel,
                iconLoader = { null },
                onRequestHomeRole = {},
                onRestoreHome = {},
                onChangeWallpaper = {},
                onOpenAppInfo = {},
                onUninstallApp = { uninstallRequests += it },
                onOpenEvent = {},
                onOpenAccessibilitySettings = {},
                onOpenMediaOutput = {},
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

    // I38-T10: the long-press dialog lists every action for an
    // uninstallable app, stacked package → アプリ情報 → アンインストール.
    @Test
    fun uninstallableAppDialogShowsAllActionsInOrder() {
        seedCatalog(
            listOf(
                entry(
                    "pkg.game", "Game App", AppCategory.Game,
                    canUninstall = true,
                ),
            ),
        )
        openAllApps()

        rule.onNodeWithText("Game App").performTouchInput { longClick() }
        rule.waitForIdle()

        // Label appears in the cell and as the dialog title.
        rule.onAllNodesWithText("Game App").assertCountEquals(2)
        rule.onNodeWithText("pkg.game").assertIsDisplayed()
        rule.onNodeWithText(res(R.string.app_info)).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.all_apps_uninstall)).assertIsDisplayed()
        // Header back + dialog dismiss share the 戻る label.
        rule.onAllNodesWithText(res(R.string.back)).assertCountEquals(2)

        assertTrue(topOf("pkg.game") < topOf(res(R.string.app_info)))
        assertTrue(
            topOf(res(R.string.app_info)) <
                topOf(res(R.string.all_apps_uninstall)),
        )
    }

    // I38-T11: the uninstall row only exists for canUninstall targets —
    // system-like entries and this app itself never show it.
    @Test
    fun uninstallRowHiddenForProtectedEntries() {
        seedCatalog(
            listOf(
                entry("pkg.sys", "System App", AppCategory.Productivity),
                ownEntry(),
            ),
        )
        openAllApps()

        rule.onNodeWithText("System App").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("pkg.sys").assertIsDisplayed()
        rule.onAllNodesWithText(res(R.string.all_apps_uninstall))
            .assertCountEquals(0)

        // Close via the dialog's 戻る (the header back is the other match).
        rule.onAllNodesWithText(res(R.string.back)).onLast().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Quiet Intent Launcher")
            .performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText(ownComponent().packageName).assertIsDisplayed()
        rule.onAllNodesWithText(res(R.string.all_apps_uninstall))
            .assertCountEquals(0)
    }

    // I38-T12: 「アンインストール」 hands the package to the host callback
    // without closing the dialog; the armed external flow survives a
    // backgrounding (design 3 — the OS confirmation owns the outcome).
    @Test
    fun uninstallTapInvokesCallbackAndKeepsScreen() {
        seedCatalog(
            listOf(
                entry(
                    "pkg.game", "Game App", AppCategory.Game,
                    canUninstall = true,
                ),
            ),
        )
        openAllApps()

        rule.onNodeWithText("Game App").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.all_apps_uninstall)).performClick()
        rule.waitForIdle()

        assertEquals(listOf("pkg.game"), uninstallRequests)
        rule.onNodeWithText("pkg.game").assertIsDisplayed()
        assertEquals(HomeScreen.AllApps, viewModel.screen.value)

        // External flow is armed: backgrounding keeps All Apps.
        viewModel.nav.onBackgrounded()
        assertEquals(HomeScreen.AllApps, viewModel.screen.value)
        viewModel.nav.onForegrounded()
    }

    // I38-T13: the open dialog tracks the displayed package — it survives
    // a component-level refresh of the same package and closes when the
    // package leaves the list (design 9.3).
    @Test
    fun dialogFollowsDisplayedPackage() {
        seedCatalog(
            listOf(
                entry(
                    "pkg.game", "Game App", AppCategory.Game,
                    canUninstall = true,
                ),
            ),
        )
        openAllApps()

        rule.onNodeWithText("Game App").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("pkg.game").assertIsDisplayed()

        // Same package under another component: still listed, stays open.
        seedCatalog(
            listOf(
                entry(
                    "pkg.game", "Game App", AppCategory.Game,
                    cls = "pkg.game.Alt", canUninstall = true,
                ),
            ),
        )
        rule.waitForIdle()
        rule.onNodeWithText("pkg.game").assertIsDisplayed()

        // Package gone from the catalog: the dialog closes itself.
        seedCatalog(listOf(entry("pkg.other", "Other App", AppCategory.Other)))
        rule.waitForIdle()
        rule.onAllNodesWithText("pkg.game").assertCountEquals(0)
        rule.onAllNodesWithText(res(R.string.app_info)).assertCountEquals(0)
    }

    // I38-T14: a 「最近」 row opens the same dialog; resolved through the
    // real catalog, this app's own entry reports canUninstall=false and
    // hides the uninstall row.
    @Test
    fun recentSectionLongPressShowsSameDialog() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuietLauncherApp
        runBlocking { app.container.recentStore.record(ownAppTarget()) }
        seedCatalog(listOf(ownEntry(AppCategory.Productivity)))
        rule.waitUntil(timeoutMillis = 5_000) {
            viewModel.recentRows.value.isNotEmpty()
        }
        openAllApps()

        // The first match is the 「最近」 row — it precedes every category.
        rule.onAllNodesWithText("Quiet Intent Launcher").onFirst()
            .performTouchInput { longClick() }
        rule.waitForIdle()

        rule.onNodeWithText(ownComponent().packageName).assertIsDisplayed()
        rule.onNodeWithText(res(R.string.app_info)).assertIsDisplayed()
        rule.onAllNodesWithText(res(R.string.all_apps_uninstall))
            .assertCountEquals(0)
    }

    // I38-T15: TalkBack hears the long-press affordance (design 12).
    @Test
    fun cellExposesLongClickLabel() {
        seedCatalog(listOf(entry("pkg.game", "Game App", AppCategory.Game)))
        openAllApps()

        val node = rule.onNodeWithText("Game App").fetchSemanticsNode()
        val longClick = if (SemanticsActions.OnLongClick in node.config) {
            node.config[SemanticsActions.OnLongClick]
        } else {
            null
        }
        assertEquals(res(R.string.do_action_menu), longClick?.label)
    }

    // I38-T31: 戻る dismisses the dialog and changes nothing else.
    @Test
    fun dialogBackDismissesAndKeepsScreen() {
        seedCatalog(
            listOf(
                entry(
                    "pkg.game", "Game App", AppCategory.Game,
                    canUninstall = true,
                ),
            ),
        )
        openAllApps()

        rule.onNodeWithText("Game App").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onAllNodesWithText(res(R.string.back)).onLast().performClick()
        rule.waitForIdle()

        rule.onAllNodesWithText("pkg.game").assertCountEquals(0)
        assertEquals(HomeScreen.AllApps, viewModel.screen.value)
        assertTrue(uninstallRequests.isEmpty())
    }
}
