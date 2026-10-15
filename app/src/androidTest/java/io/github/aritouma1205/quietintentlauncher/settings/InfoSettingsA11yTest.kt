package io.github.aritouma1205.quietintentlauncher.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aritouma1205.quietintentlauncher.calendar.CalendarAccess
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import io.github.aritouma1205.quietintentlauncher.weather.WeatherService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I43-T03 (design 12): the calendar-selection rows expose ONE merged
 * semantics node carrying Role.Checkbox + ToggleableState + the click
 * action, so TalkBack reads the calendar name, the checkbox role and the
 * on/off state together. The inner visual Checkbox stays non-interactive
 * (onCheckedChange = null) — the row owns the tap.
 */
@RunWith(AndroidJUnit4::class)
class InfoSettingsA11yTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val workCalendar = CalendarAccess.CalendarInfo(
        id = 1L,
        displayName = "仕事カレンダー",
        accountName = "work@example.com",
    )
    private val personalCalendar = CalendarAccess.CalendarInfo(
        id = 2L,
        displayName = "個人カレンダー",
        accountName = "personal@example.com",
    )

    private val checkboxRole =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    private fun setContent(selectedIds: List<Long> = listOf(workCalendar.id)) {
        rule.setContent {
            QuietLauncherTheme {
                InfoSettingsScreen(
                    initial = SettingsData().copy(
                        info = InfoSettings(
                            eventsEnabled = true,
                            selectedCalendarIds = selectedIds,
                        ),
                    ),
                    weatherUi = WeatherService.WeatherUi(),
                    regionResults = WeatherService.RegionSearchState.Idle,
                    calendarGranted = true,
                    calendars = listOf(workCalendar, personalCalendar),
                    onSave = { _, done -> done(true) },
                    onSearchRegions = {},
                    onClearRegionSearch = {},
                    onRetryWeather = {},
                    onRefreshCalendars = {},
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun calendarRowsCarryCheckboxRoleStateAndClickOnOneNode() {
        setContent()

        // Selected row: role=checkbox + on state + the click action live on
        // the same node as the calendar name — the dead inner Checkbox
        // could not produce this combination.
        val selectedRow = rule.onNode(
            hasText(workCalendar.displayName) and checkboxRole,
        )
        selectedRow.performScrollTo().assertIsDisplayed()
        selectedRow.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ToggleableState,
                ToggleableState.On,
            ),
        )
        selectedRow.assert(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
        )

        val unselectedRow = rule.onNode(
            hasText(personalCalendar.displayName) and checkboxRole,
        )
        unselectedRow.performScrollTo().assertIsDisplayed()
        unselectedRow.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ToggleableState,
                ToggleableState.Off,
            ),
        )
        unselectedRow.assert(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
        )
    }

    @Test
    fun calendarRowClickTogglesTheRowSemantics() {
        setContent()

        // The tap lands on the merged row node — the dead inner Checkbox
        // cannot consume it — and the row's own toggleable state flips.
        val row = rule.onNode(
            hasText(personalCalendar.displayName) and checkboxRole,
        )
        row.performScrollTo()
        row.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ToggleableState,
                ToggleableState.Off,
            ),
        )
        row.performClick()
        row.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ToggleableState,
                ToggleableState.On,
            ),
        )
    }
}
