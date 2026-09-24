package io.github.aritouma1205.quietintentlauncher.intro

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.settings.DoAction
import io.github.aritouma1205.quietintentlauncher.settings.DoActionDefaults
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Intro assignment save behaviour (design 11.1): 「はじめる」 waits for the
 * settings write; a failed write keeps the actions step and the draft so it
 * can be retried, a successful one delivers the draft to the caller.
 */
@RunWith(AndroidJUnit4::class)
class IntroScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun res(id: Int): String = rule.activity.getString(id)

    @Test
    fun failedSaveKeepsStepAndDraft() {
        var invocations = 0
        var captured: List<DoAction>? = null
        var pendingDone: ((Boolean) -> Unit)? = null
        rule.setContent {
            QuietLauncherTheme {
                IntroScreen(
                    isDefaultHome = true,
                    actions = DoActionDefaults.defaults(),
                    apps = emptyList(),
                    iconLoader = { null },
                    onSetHome = {},
                    onDone = { actions, done ->
                        invocations++
                        captured = actions
                        pendingDone = done
                    },
                )
            }
        }

        rule.onNodeWithText(res(R.string.intro_next)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.intro_done)).performClick()
        rule.waitForIdle()

        rule.runOnUiThread { pendingDone!!(false) }
        rule.waitForIdle()

        // Still on the actions step; the failure is explained and the draft
        // is kept for a retry.
        rule.onNodeWithText(res(R.string.intro_actions_title))
            .assertIsDisplayed()
        rule.onNodeWithText(res(R.string.settings_save_failed))
            .assertIsDisplayed()

        rule.onNodeWithText(res(R.string.intro_done)).performClick()
        rule.waitForIdle()
        rule.runOnUiThread { pendingDone!!(true) }
        rule.waitForIdle()

        assertEquals(2, invocations)
    }

    @Test
    fun successfulDoneDeliversDraft() {
        val seeded = DoActionDefaults.defaults()
        var captured: List<DoAction>? = null
        rule.setContent {
            QuietLauncherTheme {
                IntroScreen(
                    isDefaultHome = true,
                    actions = seeded,
                    apps = emptyList(),
                    iconLoader = { null },
                    onSetHome = {},
                    onDone = { actions, done ->
                        captured = actions
                        done(true)
                    },
                )
            }
        }

        rule.onNodeWithText(res(R.string.intro_next)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.intro_done)).performClick()
        rule.waitForIdle()

        assertEquals(seeded, captured)
    }
}
