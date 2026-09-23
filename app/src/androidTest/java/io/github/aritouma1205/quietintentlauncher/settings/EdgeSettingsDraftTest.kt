package io.github.aritouma1205.quietintentlauncher.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Draft persistence across configuration changes (design 3): an unsaved
 * edit must survive Activity recreation while the stored settings stay
 * untouched until save.
 */
@RunWith(AndroidJUnit4::class)
class EdgeSettingsDraftTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun res(id: Int): String = rule.activity.getString(id)

    @Test
    fun unsavedDraftSurvivesRecreation() {
        val base = SettingsData()
        var saved: SettingsData? = null
        val restorationTester = StateRestorationTester(rule)
        restorationTester.setContent {
            QuietLauncherTheme {
                EdgeSettingsScreen(
                    initial = base,
                    onSave = { data, done -> saved = data; done(true) },
                    onBack = {},
                )
            }
        }

        // Edit the draft: switch the TOOLS open mode chip (it sits below
        // the fold, so scroll it into view first).
        rule.onNodeWithText(res(R.string.edge_tools_deep))
            .performScrollTo()
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.edge_tools_deep)).assertIsSelected()

        // Save + restore the instance state like a real recreation would.
        restorationTester.emulateSavedInstanceStateRestore()

        // The edit is still there, and nothing was persisted meanwhile.
        rule.onNodeWithText(res(R.string.edge_tools_deep)).assertIsSelected()
        assertNull(saved)
    }
}
