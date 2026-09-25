package io.github.aritouma1205.quietintentlauncher.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * システム操作 settings draft behaviour (design 11.2, 13): the three
 * feature switches follow the same draft contract as the other screens —
 * edits stay in memory until save, a failed save keeps the draft and shows
 * the failure, and a successful save delivers the edited data.
 */
@RunWith(AndroidJUnit4::class)
class SystemActionsDraftTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun res(id: Int): String = rule.activity.getString(id)

    private fun setContent(
        initial: SettingsData = SettingsData(),
        onSave: (SettingsData, (Boolean) -> Unit) -> Unit,
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            QuietLauncherTheme {
                SystemActionsSettingsScreen(
                    initial = initial,
                    serviceEnabled = true,
                    serviceConnected = true,
                    onOpenServiceSettings = {},
                    onSave = onSave,
                    onBack = onBack,
                )
            }
        }
    }

    @Test
    fun failedSaveKeepsTheDraftSwitch() {
        var saveAttempts = 0
        setContent(onSave = { _, done -> saveAttempts++; done(false) })

        // The last switch is スクリーンショット.
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
        // The draft keeps the toggled switch on — nothing was persisted.
        rule.onAllNodes(isToggleable()).onLast().assertIsOn()
    }

    @Test
    fun saveDeliversTheEditedSwitches() {
        var saved: SettingsData? = null
        var backed = false
        setContent(
            onSave = { data, done -> saved = data; done(true) },
            onBack = { backed = true },
        )

        rule.onAllNodes(isToggleable()).onLast()
            .performScrollTo()
            .performClick()
        rule.onNodeWithText(res(R.string.save))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()

        assertTrue(saved?.systemActions?.screenshotEnabled == true)
        assertEquals(true, backed)
    }
}
