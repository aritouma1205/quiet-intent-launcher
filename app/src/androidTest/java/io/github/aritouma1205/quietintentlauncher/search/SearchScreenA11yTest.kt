package io.github.aritouma1205.quietintentlauncher.search

import android.content.ComponentName
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.settings.ActionIcon
import io.github.aritouma1205.quietintentlauncher.settings.DerivedOp
import io.github.aritouma1205.quietintentlauncher.settings.DoAction
import io.github.aritouma1205.quietintentlauncher.settings.StoredTarget
import io.github.aritouma1205.quietintentlauncher.settings.ToolItem
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I43-T04 (design 12): every tap target on the search surface — ranked
 * result rows, 「最近」 rows and the fixed entry rows — must carry a
 * non-blank [SemanticsActions.OnClick] label so TalkBack announces what
 * the tap does instead of a bare "double-tap to activate".
 */
@RunWith(AndroidJUnit4::class)
class SearchScreenA11yTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val action = DoAction(
        id = "a1",
        name = "撮る",
        icon = ActionIcon.Face,
        target = StoredTarget.App("com.example.camera/.Main"),
    )
    private val op = DerivedOp(
        id = "op1",
        label = "夜景を撮る",
    )
    private val appEntry = AppEntry(
        component = ComponentName("com.example.maps", ".MainActivity"),
        user = Process.myUserHandle(),
        label = "地図",
    )

    private fun setContent(
        query: String = "q",
        rows: List<SearchRow> = listOf(
            SearchRow.Action(action),
            SearchRow.Op(action, op),
            SearchRow.App(appEntry, showPackage = false),
            SearchRow.Tool(ToolItem.Timer),
            SearchRow.Setting(SettingsDestination.Edge),
        ),
        recents: List<RecentRow> = emptyList(),
        chatGptAvailable: Boolean = true,
        shareAvailable: Boolean = true,
    ) {
        rule.setContent {
            QuietLauncherTheme {
                SearchScreen(
                    query = query,
                    rows = rows,
                    recents = recents,
                    chatGptAvailable = chatGptAvailable,
                    shareAvailable = shareAvailable,
                    iconLoader = { null },
                    onQueryChanged = {},
                    onRowTapped = {},
                    onRecentTapped = {},
                    onWebSearch = {},
                    onShare = {},
                    onAllApps = {},
                    onSettings = {},
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun everyTapTargetOnTheQuerySurfaceHasAnOnClickLabel() {
        setContent()

        // 5 result rows + web + ChatGPT + all-apps + settings entries —
        // the content must actually be there or the sweep proves nothing.
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(
            "expected at least 9 clickable nodes, found ${clickables.size}",
            clickables.size >= 9,
        )
        // Text inputs own SetText, not OnClick labels — everything else
        // that answers a click must name the action.
        val unlabeled = clickables.filter { node ->
            !node.config.contains(SemanticsActions.SetText) &&
                node.config.getOrNull(SemanticsActions.OnClick)
                    ?.label.isNullOrBlank()
        }
        assertTrue(
            "clickable nodes missing an onClickLabel: $unlabeled",
            unlabeled.isEmpty(),
        )
    }

    @Test
    fun rowAndEntryLabelsNameTheRow() {
        setContent()

        // The action row's label is its own title — verified end-to-end
        // from the rendered text to the announced action.
        val actionLabel = rule.onNode(
            hasText(action.name) and hasClickAction(),
        ).fetchSemanticsNode().config[SemanticsActions.OnClick].label
        assertEquals(action.name, actionLabel)

        val opTitle = op.label.ifBlank { action.name }
        val opLabel = rule.onNode(
            hasText(opTitle) and hasClickAction(),
        ).fetchSemanticsNode().config[SemanticsActions.OnClick].label
        assertEquals(opTitle, opLabel)
    }

    @Test
    fun recentRowCarriesAnOnClickLabel() {
        val recent = RecentRow(
            target = StoredTarget.App("com.example.maps/.MainActivity"),
            label = "最近の地図",
            appEntry = null,
            showPackage = false,
        )
        setContent(query = "", recents = listOf(recent))

        val label = rule.onNode(
            hasText(recent.label) and hasClickAction(),
        ).fetchSemanticsNode().config[SemanticsActions.OnClick].label
        assertEquals(recent.label, label)
    }
}
