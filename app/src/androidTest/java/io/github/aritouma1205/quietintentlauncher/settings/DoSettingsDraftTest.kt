package io.github.aritouma1205.quietintentlauncher.settings

import android.content.ComponentName
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.apps.AppCategory
import io.github.aritouma1205.quietintentlauncher.apps.AppEntry
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 行動・道具 edit screen draft behaviour (design 11.2): validation happens
 * before save, a failed save keeps the draft and shows the failure, and a
 * successful save delivers the edited data. Leaving with unsaved changes
 * asks 保存 / 破棄 / 編集に戻る (design 3), and a pending invalid link
 * input blocks saving entirely.
 */
@RunWith(AndroidJUnit4::class)
class DoSettingsDraftTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun res(id: Int): String = rule.activity.getString(id)

    private fun fakeApp(packageName: String, label: String) = AppEntry(
        component = ComponentName(packageName, "$packageName.Main"),
        user = Process.myUserHandle(),
        label = label,
        category = AppCategory.Other,
    )

    /**
     * The confirm dialog lives in a Popup whose first layout can land
     * several seconds after the compose tree reports idle on a loaded
     * emulator; poll for non-zero bounds before asserting it is displayed.
     */
    private fun assertUnsavedDialogShown() {
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodesWithText(res(R.string.unsaved_title))
                .fetchSemanticsNodes()
                .any { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        }
        rule.onNodeWithText(res(R.string.unsaved_title)).assertIsDisplayed()
    }

    /**
     * Issue #35: editors start collapsed. The merged row node carries the
     * action name as merged text plus the click action — tapping it opens
     * the editor (accordion: at most one expanded).
     */
    private fun expandAction(name: String) {
        rule.onNode(hasClickAction() and hasText(name))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
    }

    private fun setContent(
        initial: SettingsData = SettingsData(),
        onSave: (SettingsData, (Boolean) -> Unit) -> Unit,
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            QuietLauncherTheme {
                DoSettingsScreen(
                    initial = initial,
                    focusActionId = null,
                    apps = emptyList(),
                    iconLoader = { null },
                    isHomeRoleHeld = false,
                    shortcutsFor = { emptyList() },
                    onSave = onSave,
                    onBack = onBack,
                )
            }
        }
    }

    @Test
    fun blankNameIsRejectedBeforeSave() {
        var saveAttempts = 0
        setContent(onSave = { _, done -> saveAttempts++; done(true) })

        expandAction("撮る")
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("   ")
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        rule.onNodeWithText(res(R.string.action_error_name)).assertIsDisplayed()
        assertEquals(0, saveAttempts)
    }

    @Test
    fun failedSaveKeepsDraftAndShowsError() {
        var saveAttempts = 0
        setContent(onSave = { _, done -> saveAttempts++; done(false) })

        expandAction("撮る")
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("写真")
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertEquals(1, saveAttempts)
        rule.onNodeWithText(res(R.string.settings_save_failed))
            .assertIsDisplayed()
        // The draft is untouched: the edited name is still in the field.
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("写真"),
                ),
            )
    }

    @Test
    fun failedSaveKeepsToolVisibilityDraft() {
        // Tools section: the last toggleable node is the last tool's 表示
        // switch; a failed save must keep the draft edit (design 7, 11.2).
        var saveAttempts = 0
        setContent(onSave = { _, done -> saveAttempts++; done(false) })

        rule.onAllNodes(isToggleable()).onLast()
            .performScrollTo()
            .performClick()
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertEquals(1, saveAttempts)
        rule.onNodeWithText(res(R.string.settings_save_failed))
            .assertIsDisplayed()
        rule.onAllNodes(isOff()).onLast().assertIsOff()
    }

    @Test
    fun saveDeliversEditedData() {
        var saved: SettingsData? = null
        var backed = false
        setContent(
            onSave = { data, done -> saved = data; done(true) },
            onBack = { backed = true },
        )

        expandAction("撮る")
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("写真")
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertEquals("写真", saved?.actions?.first()?.name)
        assertEquals(6, saved?.actions?.size)
        assertEquals(true, backed)
    }

    @Test
    fun invalidPendingLinkBlocksSave() {
        var saveAttempts = 0
        val initial = SettingsData().let { data ->
            data.copy(
                actions = data.actions.mapIndexed { index, action ->
                    if (index == 0) {
                        action.copy(
                            target = StoredTarget.HttpsLink(
                                "https://example.com/old",
                            ),
                        )
                    } else {
                        action
                    }
                },
            )
        }
        setContent(
            initial = initial,
            onSave = { _, done -> saveAttempts++; done(true) },
        )

        // Open the first action's target picker and switch to the link mode.
        expandAction("撮る")
        rule.onAllNodesWithText(res(R.string.action_target_change))
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.onNodeWithText(res(R.string.target_kind_link))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        // The link field shows the stored URL; replace it with an invalid
        // non-blank input that never reaches the draft.
        rule.onNode(
            hasSetTextAction() and hasText("https://example.com/old"),
        )
            .assertIsDisplayed()
            .performTextReplacement("http://example.com/new")
        rule.waitForIdle()

        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertEquals(0, saveAttempts)
        rule.onNodeWithText(res(R.string.action_error_link_pending))
            .assertIsDisplayed()
    }

    @Test
    fun clearingInvalidLinkAllowsSavingUnsetTarget() {
        var saveAttempts = 0
        var saved: SettingsData? = null
        val initial = SettingsData().let { data ->
            data.copy(
                actions = data.actions.mapIndexed { index, action ->
                    if (index == 0) {
                        action.copy(
                            target = StoredTarget.HttpsLink(
                                "https://example.com/old",
                            ),
                        )
                    } else {
                        action
                    }
                },
            )
        }
        setContent(
            initial = initial,
            onSave = { data, done -> saveAttempts++; saved = data; done(true) },
        )

        // Open the first action's target picker and switch to the link mode.
        expandAction("撮る")
        rule.onAllNodesWithText(res(R.string.action_target_change))
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.onNodeWithText(res(R.string.target_kind_link))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        // Replace the stored URL with an invalid non-blank input so the
        // editor reports a pending invalid link.
        rule.onNode(
            hasSetTextAction() and hasText("https://example.com/old"),
        )
            .assertIsDisplayed()
            .performTextReplacement("http://example.com/new")
        rule.waitForIdle()

        // 「解除する」 must clear the pending-invalid flag together with the
        // assignment, so saving afterwards persists an unset target.
        rule.onNodeWithText(res(R.string.target_clear))
            .performClick()
        rule.waitForIdle()

        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertEquals(1, saveAttempts)
        assertEquals(null, saved?.actions?.first()?.target)
    }

    @Test
    fun pickerSearchFiltersGridAndPickSetsTarget() {
        // v1.2 (Issue #26): the app picker is a categorized grid with a
        // search field; picking a cell writes the draft target.
        var saved: SettingsData? = null
        val entries = listOf(
            fakeApp("com.example.maps", "マップ"),
            fakeApp("com.example.mail", "メール"),
            fakeApp("com.example.memo", "メモ"),
        )
        rule.setContent {
            QuietLauncherTheme {
                DoSettingsScreen(
                    initial = SettingsData(),
                    focusActionId = null,
                    apps = entries,
                    iconLoader = { null },
                    isHomeRoleHeld = false,
                    shortcutsFor = { emptyList() },
                    onSave = { data, done -> saved = data; done(true) },
                    onBack = {},
                )
            }
        }

        expandAction("撮る")
        rule.onAllNodesWithText(res(R.string.action_target_change))
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        // The search field and the categorized grid are composed.
        rule.onNode(
            hasSetTextAction() and hasText(res(R.string.picker_search_hint)),
        ).assertIsDisplayed()
        rule.onNodeWithText("マップ").assertExists()
        rule.onNodeWithText("メール").assertExists()

        // A normalized substring query hides non-matching cells.
        rule.onNode(
            hasSetTextAction() and hasText(res(R.string.picker_search_hint)),
        ).performTextReplacement("メー")
        rule.waitForIdle()
        rule.onNodeWithText("マップ").assertDoesNotExist()
        rule.onNodeWithText("メモ").assertDoesNotExist()

        // Picking the surviving cell stores the app target in the draft.
        rule.onNodeWithText("メール").performClick()
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertEquals(
            StoredTarget.App("com.example.mail/.Main"),
            saved?.actions?.first()?.target,
        )
    }

    @Test
    fun derivedOpPickerPickSavesAndPersistsOnReopen() {
        // Issue #29 UX28-01: the derived-op editor's TargetPicker is a
        // different call path (slot "actionId|opId") than the action's own
        // picker — the pick must reach the draft, survive save, and still
        // be shown when the screen is displayed again.
        var saved: SettingsData? = null
        // Re-displaying the screen re-runs setContent, which is allowed
        // once per test — flip [visit] inside a key() so the subtree is
        // recreated fresh with the saved data as its new initial draft.
        var visit by mutableIntStateOf(0)
        var initial by mutableStateOf(SettingsData())
        val entries = listOf(
            fakeApp("com.example.maps", "マップ"),
            fakeApp("com.example.mail", "メール"),
            fakeApp("com.example.memo", "メモ"),
        )
        rule.setContent {
            QuietLauncherTheme {
                key(visit) {
                    DoSettingsScreen(
                        initial = initial,
                        focusActionId = null,
                        apps = entries,
                        iconLoader = { null },
                        isHomeRoleHeld = false,
                        shortcutsFor = { emptyList() },
                        onSave = { data, done -> saved = data; done(true) },
                        onBack = {},
                    )
                }
            }
        }

        // Add a derived op to the first action, then open its picker —
        // inside action 1's card the op's 変更する follows the action's
        // own target row (index 1).
        expandAction("撮る")
        rule.onAllNodesWithText(res(R.string.action_op_add))
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(res(R.string.action_target_change))[1]
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        rule.onNode(
            hasSetTextAction() and hasText(res(R.string.picker_search_hint)),
        ).performScrollTo().assertIsDisplayed()
        // Filter to a single cell — the surviving メール is then the only
        // match and sits fully inside the grid viewport for the click.
        rule.onNode(
            hasSetTextAction() and hasText(res(R.string.picker_search_hint)),
        ).performTextReplacement("メー")
        // Dismiss the IME: with the keyboard up, the surviving cell can sit
        // underneath it and the injected touch is swallowed by the keyboard.
        rule.runOnUiThread {
            val imm = rule.activity.getSystemService(
                android.content.Context.INPUT_METHOD_SERVICE,
            ) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(
                rule.activity.window.decorView.windowToken, 0,
            )
        }
        rule.waitForIdle()
        // The grid's own scroll range is empty, so scrolling the cell is a
        // no-op; scroll the page instead by pulling the element BELOW the
        // picker into view — the cell then sits inside the window.
        rule.onAllNodesWithText(res(R.string.action_op_add))
            .onFirst()
            .performScrollTo()
        rule.onNodeWithText("メール")
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
        // The pick must reach the draft — a set `current` makes the open
        // picker surface 解除する (off-screen at first, so scroll).
        rule.onNodeWithText(res(R.string.target_clear))
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertEquals(
            StoredTarget.App("com.example.mail/.Main"),
            saved?.actions?.first()?.derivedOps?.first()?.target,
        )

        // Leave and display the screen again: the op retains the app.
        // The revisited screen starts collapsed (issue 35), so expand the
        // row first to reach the op summary inside the editor.
        initial = saved!!
        visit++
        rule.waitForIdle()
        expandAction("撮る")
        rule.onNodeWithText("メール")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun toolPickerPickSavesAndPersistsOnReopen() {
        // Issue #29 UX28-02: the tool editor's TargetPicker (slot
        // "tool:<id>", App-only modes) is yet another call path — the
        // pick must persist and re-display the same way.
        var saved: SettingsData? = null
        var visit by mutableIntStateOf(0)
        var initial by mutableStateOf(SettingsData())
        val entries = listOf(
            fakeApp("com.example.maps", "マップ"),
            fakeApp("com.example.mail", "メール"),
            fakeApp("com.example.memo", "メモ"),
        )
        rule.setContent {
            QuietLauncherTheme {
                key(visit) {
                    DoSettingsScreen(
                        initial = initial,
                        focusActionId = null,
                        focusToolId = if (visit == 0) "calculator" else null,
                        apps = entries,
                        iconLoader = { null },
                        isHomeRoleHeld = false,
                        shortcutsFor = { emptyList() },
                        onSave = { data, done -> saved = data; done(true) },
                        onBack = {},
                    )
                }
            }
        }
        // focusToolId is the production entry path for tool editing: it
        // opens that tool's picker directly ("tool:calculator").
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodes(
                hasSetTextAction() and
                    hasText(res(R.string.picker_search_hint)),
            ).fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNode(
            hasSetTextAction() and hasText(res(R.string.picker_search_hint)),
        ).assertIsDisplayed()
        // Filter to a single cell so the click lands unambiguously, and
        // dismiss the IME so it cannot swallow the injected touch.
        rule.onNode(
            hasSetTextAction() and hasText(res(R.string.picker_search_hint)),
        ).performTextReplacement("メー")
        rule.runOnUiThread {
            val imm = rule.activity.getSystemService(
                android.content.Context.INPUT_METHOD_SERVICE,
            ) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(
                rule.activity.window.decorView.windowToken, 0,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("メール")
            .performScrollTo()
            .performClick()
        // Diagnostic: a successful pick sets `current` in the still-open
        // picker, which surfaces the 「解除する」 row (target_clear).
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.target_clear))
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertEquals(true, saved != null)
        assertEquals(
            StoredTarget.App("com.example.mail/.Main"),
            saved?.tools?.items?.first { it.id == "calculator" }?.target,
        )

        // Leave and display the screen again: the tool retains the app.
        initial = saved!!
        visit++
        rule.waitForIdle()
        rule.onNodeWithText("メール")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun dirtyBackShowsConfirm() {
        var backed = false
        setContent(
            onSave = { _, done -> done(true) },
            onBack = { backed = true },
        )

        expandAction("撮る")
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("変えた")
        rule.onNodeWithText(res(R.string.back)).performClick()
        rule.waitForIdle()

        assertUnsavedDialogShown()
        assertEquals(false, backed)

        rule.onNodeWithText(res(R.string.unsaved_discard)).performClick()
        rule.waitForIdle()
        assertEquals(true, backed)
    }

    @Test
    fun dirtyBackKeepEditing() {
        var backed = false
        setContent(
            onSave = { _, done -> done(true) },
            onBack = { backed = true },
        )

        expandAction("撮る")
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("変えた")
        rule.onNodeWithText(res(R.string.back)).performClick()
        rule.waitForIdle()

        rule.onNodeWithText(res(R.string.unsaved_keep)).performClick()
        rule.waitForIdle()

        rule.onAllNodesWithText(res(R.string.unsaved_title)).assertCountEquals(0)
        assertEquals(false, backed)
        // The draft survives: the edited name is still in the field.
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("変えた"),
                ),
            )
    }

    @Test
    fun dirtyBackSaveExits() {
        var saved: SettingsData? = null
        var backed = false
        setContent(
            onSave = { data, done -> saved = data; done(true) },
            onBack = { backed = true },
        )

        expandAction("撮る")
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("写真")
        rule.onNodeWithText(res(R.string.back)).performClick()
        rule.waitForIdle()

        rule.onNodeWithText(res(R.string.unsaved_save)).performClick()
        rule.waitForIdle()

        assertEquals("写真", saved?.actions?.first()?.name)
        assertEquals(true, backed)
    }

    @Test
    fun cleanBackExitsImmediately() {
        var backed = false
        setContent(
            onSave = { _, done -> done(true) },
            onBack = { backed = true },
        )

        rule.onNodeWithText(res(R.string.back)).performClick()
        rule.waitForIdle()

        assertEquals(true, backed)
        rule.onAllNodesWithText(res(R.string.unsaved_title)).assertCountEquals(0)
    }

    @Test
    fun osBackWithDirtyShowsConfirm() {
        var backed = false
        setContent(
            onSave = { _, done -> done(true) },
            onBack = { backed = true },
        )

        expandAction("撮る")
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("変えた")
        rule.waitForIdle()
        // Injected KEYCODE_BACK events never reach this API-36 emulator's
        // window; invoke the dispatcher on the UI thread instead — that is
        // the same entry point a real OS back gesture ends up calling, and
        // it bypasses the IME entirely.
        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        rule.waitForIdle()

        assertUnsavedDialogShown()
        assertEquals(false, backed)
    }

    @Test
    fun cancelLeavesWithoutSaving() {
        var saveAttempts = 0
        var backed = false
        setContent(
            onSave = { _, done -> saveAttempts++; done(true) },
            onBack = { backed = true },
        )

        expandAction("撮る")
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextReplacement("変えた")
        rule.onNodeWithText(res(R.string.cancel))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        // A dirty draft needs confirmation before it is discarded.
        rule.onNodeWithText(res(R.string.unsaved_discard)).performClick()
        rule.waitForIdle()

        assertEquals(0, saveAttempts)
        assertEquals(true, backed)
    }

    @Test
    fun collapsedRowShowsNameAndExpandsToEditor() {
        // Issue #35: rows start collapsed — name + target summary visible,
        // no form fields. Tapping the row opens the full editor and the
        // edited name still saves through the unchanged path.
        var saved: SettingsData? = null
        setContent(onSave = { data, done -> saved = data; done(true) })

        rule.onNode(hasClickAction() and hasText("撮る")).assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        expandAction("撮る")
        rule.onNode(hasSetTextAction() and hasText("撮る"))
            .assertIsDisplayed()
            .performTextReplacement("写真")
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertEquals("写真", saved?.actions?.first()?.name)
    }

    @Test
    fun accordionCollapsesPreviousRow() {
        // Issue #35: a single expanded editor — opening the next row folds
        // the previous one.
        setContent(onSave = { _, done -> done(true) })

        expandAction("撮る")
        rule.onNode(hasSetTextAction() and hasText("撮る")).assertIsDisplayed()

        expandAction("話す")
        rule.onAllNodes(hasSetTextAction() and hasText("撮る"))
            .assertCountEquals(0)
        rule.onNode(hasSetTextAction() and hasText("話す")).assertIsDisplayed()
    }

    @Test
    fun collapsedReorderMovesActionWithoutExpanding() {
        // Issue #35: ↑/↓ reorder must stay available while folded
        // (design 11.2 switch-accessible reorder).
        var saved: SettingsData? = null
        var backed = false
        setContent(
            onSave = { data, done -> saved = data; done(true) },
            onBack = { backed = true },
        )

        // The first row's 下へ icon button — no editor opens.
        rule.onAllNodesWithContentDescription(res(R.string.action_move_down))
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertEquals("話す", saved?.actions?.get(0)?.name)
        assertEquals("撮る", saved?.actions?.get(1)?.name)
        assertEquals(true, backed)
    }

    @Test
    fun focusActionIdAutoExpandsAndOpensPicker() {
        // Issue #35: focusActionId is the production entry path (DO panel
        // unset-tap / この行動を編集) — it must expand the target row AND
        // open its picker, even though editors start collapsed.
        // AppPickList renders its search field only when apps exist.
        val entries = listOf(
            fakeApp("com.example.maps", "マップ"),
            fakeApp("com.example.mail", "メール"),
        )
        rule.setContent {
            QuietLauncherTheme {
                DoSettingsScreen(
                    initial = SettingsData(),
                    focusActionId = "00000000-0000-4000-8000-000000000004",
                    apps = entries,
                    iconLoader = { null },
                    isHomeRoleHeld = false,
                    shortcutsFor = { emptyList() },
                    onSave = { _, done -> done(true) },
                    onBack = {},
                )
            }
        }

        // The target's picker mounts (it lives inside the expanded editor).
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodes(
                hasSetTextAction() and hasText(res(R.string.picker_search_hint)),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // …and the focused row （見る, id …004) is the expanded one while
        // other editors stay folded.
        rule.onNode(hasSetTextAction() and hasText("見る")).assertExists()
        rule.onAllNodes(hasSetTextAction() and hasText("撮る"))
            .assertCountEquals(0)
    }

    @Test
    fun addedActionAutoExpands() {
        // Issue #35: 行動を追加 opens the new row expanded (and the deferred
        // scroll lands on it).
        setContent(onSave = { _, done -> done(true) })

        rule.onNodeWithText(res(R.string.action_add))
            .performScrollTo()
            .performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            rule.onAllNodes(
                hasSetTextAction() and hasText("行動"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasSetTextAction() and hasText("行動"))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
