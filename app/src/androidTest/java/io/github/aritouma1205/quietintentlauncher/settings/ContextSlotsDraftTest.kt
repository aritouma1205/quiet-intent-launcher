package io.github.aritouma1205.quietintentlauncher.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aritouma1205.quietintentlauncher.context.ContextRule
import io.github.aritouma1205.quietintentlauncher.context.ContextRules
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Draft persistence across configuration changes (design 3): raw time
 * input that has not settled into the draft yet — including unparseable
 * text the draft cannot represent — must survive Activity recreation.
 */
@RunWith(AndroidJUnit4::class)
class ContextSlotsDraftTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unsettledTimeInputSurvivesRecreation() {
        val actionId = DoActionDefaults.defaults().first().id
        val base = SettingsData().copy(
            actions = DoActionDefaults.defaults(),
            contextSlots = ContextRules.defaultSlots().mapIndexed { i, slot ->
                if (i == 0) {
                    slot.copy(
                        rules = listOf(
                            ContextRule(
                                daysOfWeek = emptySet(),
                                startMinuteOfDay = 540,
                                endMinuteOfDay = 1080,
                                actionId = actionId,
                            ),
                        ),
                    )
                } else {
                    slot
                }
            },
        )
        var saved: SettingsData? = null
        val restorationTester = StateRestorationTester(rule)
        restorationTester.setContent {
            QuietLauncherTheme {
                ContextSlotsScreen(
                    initial = base,
                    focusSlotIndex = null,
                    onSave = { data, done -> saved = data; done(true) },
                    onBack = {},
                )
            }
        }

        // Text fields in order: slot1 label, rule start, rule end, slot2
        // label. Corrupt the start field so the draft cannot hold it.
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("25:00")

        restorationTester.emulateSavedInstanceStateRestore()

        // The unparseable input is still there to fix — not silently
        // dropped or replaced by the last parsed value.
        rule.onAllNodes(hasSetTextAction())[1].assert(hasText("25:00"))
        assertNull(saved)
    }
}
