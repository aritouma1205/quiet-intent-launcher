package io.github.aritouma1205.quietintentlauncher.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.calendar.EventSection
import io.github.aritouma1205.quietintentlauncher.calendar.TodayEvent
import io.github.aritouma1205.quietintentlauncher.system.VolumeUi
import io.github.aritouma1205.quietintentlauncher.today.TodayUi
import io.github.aritouma1205.quietintentlauncher.today.WeatherBlock
import io.github.aritouma1205.quietintentlauncher.ui.QuietLauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UX27-01 (Issue #29): TODAY renders its optional clusters — weather
 * inside Now, the 「これから」 events block, ambient battery — only when
 * the data exists. A missing cluster must leave no heading, no divider
 * region and no empty spacing debris; existence/absence of the cluster
 * nodes is the asserted contract (hairlines ride along inside the same
 * conditional blocks).
 */
@RunWith(AndroidJUnit4::class)
class TodayPanelStatesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun res(id: Int, vararg args: Any): String =
        rule.activity.getString(id, *args)

    private val nowMs = 1_800_000_000_000L

    private fun weather() = WeatherBlock(
        temperatureCelsius = 21.5,
        labelRes = R.string.weather_clear,
        fresh = true,
        fetchedAtWallMs = nowMs,
        regionName = "テスト地域",
    )

    private fun upcomingEvent() = TodayEvent(
        eventId = 1L,
        title = "定例ミーティング",
        beginMs = nowMs + 3_600_000L,
        endMs = nowMs + 7_200_000L,
        allDay = false,
        section = EventSection.Upcoming,
    )

    private fun setToday(state: TodayUi, volume: VolumeUi = VolumeUi(7, 15)) {
        rule.setContent {
            QuietLauncherTheme {
                TodayPanel(
                    state = state,
                    volume = volume,
                    panelWidthPx = 320f,
                    onClose = {},
                    onEventTap = {},
                    onVolumeFractionChange = {},
                    onOutputTap = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun baseState() = TodayUi(
        dateText = "9月27日",
        weekdayText = "土曜日",
        batteryPercent = 80,
        charging = false,
        nowMs = nowMs,
    )

    @Test
    fun weatherOnlyShowsNoUpcomingCluster() {
        // 天気あり・予定なし: the Upcoming cluster must not leave its
        // heading or a stray divider behind.
        setToday(baseState().copy(weather = weather()))

        rule.onNodeWithText(res(R.string.today_upcoming)).assertDoesNotExist()
        rule.onNode(hasText("Open-Meteo", substring = true)).assertExists()
        rule.onNodeWithText("9月27日 土曜日").assertIsDisplayed()
        rule.onNodeWithText(res(R.string.today_battery, 80)).assertExists()
    }

    @Test
    fun eventsOnlyHideWeatherAndKeepUpcoming() {
        // 天気なし・予定あり: no provider line, but the Upcoming heading
        // (with heading semantics) and the event row stay.
        setToday(baseState().copy(events = listOf(upcomingEvent())))

        rule.onAllNodes(hasText("Open-Meteo", substring = true))
            .assertCountEquals(0)
        rule.onNode(
            hasText(res(R.string.today_upcoming)),
        ).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit),
        ).assertIsDisplayed()
        rule.onNodeWithText("定例ミーティング").assertIsDisplayed()
        rule.onNodeWithText(res(R.string.today_battery, 80)).assertExists()
    }

    @Test
    fun missingOptionalClustersLeaveNoDebris() {
        // 両方なし（バッテリー読み取り不能も含む最小状態）: only the Now
        // cluster's date line remains — no empty heading or ambient row.
        setToday(
            baseState().copy(
                weather = null,
                events = emptyList(),
                batteryPercent = null,
            ),
        )

        rule.onNodeWithText(res(R.string.today_upcoming)).assertDoesNotExist()
        rule.onAllNodes(hasText("Open-Meteo", substring = true))
            .assertCountEquals(0)
        rule.onAllNodes(hasText("バッテリー", substring = true))
            .assertCountEquals(0)
        rule.onNodeWithText("9月27日 土曜日").assertIsDisplayed()
    }

    // ---- 音量 block (Issue #49) ----------------------------------------------

    @Test
    fun volumeBlockIsPresentEvenForMinimalToday() {
        // 最小 TodayUi（予定・天気・バッテリーなし）でも音量ブロックは
        // 常時表示 — メディア音量は常に存在するので空セクションではない
        // (Issue #49 受入: ブロック自体は常時表示).
        setToday(TodayUi())

        rule.onNodeWithText(res(R.string.today_volume)).assertIsDisplayed()
        rule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
        ).assertExists()
        rule.onNodeWithText(res(R.string.volume_output)).assertExists()
    }

    @Test
    fun outputRowExposesButtonRoleAndClickLabel() {
        // TalkBack は行を「出力先を変更、ボタン」系に読む (Issue #49):
        // clickable の onClickLabel と Role.Button がマージ行に乗ること。
        setToday(baseState())

        val node = rule.onNodeWithText(res(R.string.volume_output))
            .fetchSemanticsNode()
        assertEquals(
            Role.Button,
            node.config[SemanticsProperties.Role],
        )
        assertEquals(
            res(R.string.volume_output_change),
            node.config.getOrNull(SemanticsActions.OnClick)?.label,
        )
    }
}
