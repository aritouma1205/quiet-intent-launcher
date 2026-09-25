package io.github.aritouma1205.quietintentlauncher.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    fun dirtyBackShowsConfirm() {
        var backed = false
        setContent(
            onSave = { _, done -> done(true) },
            onBack = { backed = true },
        )

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
}
