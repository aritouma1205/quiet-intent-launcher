package io.github.aritouma1205.quietintentlauncher.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 行動・道具 edit screen draft behaviour (design 11.2): validation happens
 * before save, a failed save keeps the draft and shows the failure, and a
 * successful save delivers the edited data.
 */
@RunWith(AndroidJUnit4::class)
class DoSettingsDraftTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun res(id: Int): String = rule.activity.getString(id)

    private fun setContent(
        onSave: (SettingsData, (Boolean) -> Unit) -> Unit,
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            QuietLauncherTheme {
                DoSettingsScreen(
                    initial = SettingsData(),
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

        assertEquals(0, saveAttempts)
        assertEquals(true, backed)
    }
}
