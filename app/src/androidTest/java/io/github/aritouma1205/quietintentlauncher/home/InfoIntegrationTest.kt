package io.github.aritouma1205.quietintentlauncher.home

import android.Manifest
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aritouma1205.quietintentlauncher.QuietLauncherApp
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.settings.InfoSettings
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.settings.WeatherLocation
import io.github.aritouma1205.quietintentlauncher.settings.WeatherSettings
import io.github.aritouma1205.quietintentlauncher.weather.HttpWeatherApi
import io.github.aritouma1205.quietintentlauncher.weather.WeatherApi
import io.github.aritouma1205.quietintentlauncher.weather.WeatherReading
import io.github.aritouma1205.quietintentlauncher.weather.WeatherSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #6 end-to-end coverage on a real process: the GLANCE timer and
 * its suppression paths (finger hold / accessibility / 「消さない」), the
 * weather display path driven through a fake provider seam, and the
 * calendar permission lifecycle. The GLANCE countdown runs on real
 * time — the dismiss tests take a few seconds each by design.
 */
@RunWith(AndroidJUnit4::class)
class InfoIntegrationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: HomeViewModel
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() =
        instrumentation.targetContext.applicationContext as QuietLauncherApp

    private fun res(id: Int): String = rule.activity.getString(id)

    private var fakeApi: FakeWeatherApi? = null

    @Before
    fun setUp() {
        // Once any test touches UiAutomation its connection flips the
        // accessibility_enabled flag for the rest of the run, which would
        // suppress every later GLANCE timer — pin the seam instead.
        app.container.isAccessibilityActive = { false }
        runBlocking {
            app.container.settingsStore.state.first { it is SettingsState.Ready }
            app.container.settingsStore.update {
                it.copy(
                    introCompleted = true,
                    notificationHintShown = true,
                    info = InfoSettings(glanceDismissSeconds = 2),
                )
            }
        }
        viewModel = HomeViewModel(app.container)
        rule.setContent {
            QuietLauncherRoot(
                viewModel = viewModel,
                iconLoader = { null },
                onRequestHomeRole = {},
                onRestoreHome = {},
                onChangeWallpaper = {},
                onOpenAppInfo = {},
                onOpenEvent = {},
            )
        }
        rule.waitForIdle()
        assertEquals(HomeScreen.Quiet, viewModel.screen.value)
    }

    @After
    fun tearDown() {
        // The test-owned ViewModel has no ViewModelStore, so onCleared
        // never runs — close any open overlay so its collector
        // unregisters the ContentObserver instead of leaking it into
        // the next test.
        viewModel.closeOverlay()
        viewModel.setGlanceHold(false)
        rule.waitForIdle()
        // Restore the seams the tests swap out.
        app.container.isAccessibilityActive = defaultAccessibilityCheck()
        app.container.calendarAccess.permissionCheck = null
        app.container.calendarAccess.instanceSource = null
        app.container.weatherService.api = HttpWeatherApi()
        app.container.weatherService.clearCacheHook = null
        runBlocking {
            // Release a fetch still suspended on the fake's gate and wait
            // for it to settle — otherwise the tracked in-flight job makes
            // the next test's requestAutoRefresh return early.
            fakeApi?.gate?.complete(Unit)
            fakeApi = null
            withTimeout(5_000) {
                app.container.weatherService.ui.first { !it.refreshing }
            }
            // A leftover snapshot would satisfy the freshness check in the
            // next test and skip its fetch — always wipe it.
            app.container.weatherService.clearCache()
            app.container.settingsStore.update {
                it.copy(info = InfoSettings())
            }
        }
    }

    private fun defaultAccessibilityCheck(): () -> Boolean {
        val context = instrumentation.targetContext
        return {
            val am = context.getSystemService(
                android.view.accessibility.AccessibilityManager::class.java,
            )
            am != null && am.isEnabled
        }
    }

    private fun tapFreeArea() {
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.4f, height * 0.5f))
            up()
        }
        rule.waitForIdle()
    }

    // ---- GLANCE timer ------------------------------------------------------

    @Test
    fun tapTogglesGlanceOnAndOff() {
        tapFreeArea()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)

        tapFreeArea()
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun glanceAutoDismissesAfterTheConfiguredSeconds() {
        tapFreeArea()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)

        // 2 s configured — real time, generous margin.
        Thread.sleep(3_200)
        rule.waitForIdle()
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun fingerHoldPausesTheAutoDismiss() {
        tapFreeArea()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)

        // A real finger resting on the surface pauses the countdown via
        // the surface-level pointer tracking (design 8.3 触っている間).
        rule.onRoot().performTouchInput { down(center) }
        Thread.sleep(3_000)
        rule.waitForIdle()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)

        // Lift with a small move so the release is not a tap — a tap
        // would toggle GLANCE off on its own and mask whether the
        // countdown actually resumed.
        rule.onRoot().performTouchInput {
            moveTo(center + Offset(64f, 0f))
            up()
        }
        Thread.sleep(2_600)
        rule.waitForIdle()
        assertNull(viewModel.overlay.value)
    }

    @Test
    fun accessibilitySuppressesAutoDismiss() {
        // TalkBack / switch access on ⇒ GLANCE must not time out
        // (design 8.3). The container seam stubs the OS check.
        app.container.isAccessibilityActive = { true }
        tapFreeArea()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)

        Thread.sleep(3_000)
        rule.waitForIdle()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)
    }

    @Test
    fun glanceNeverDismissesWhenConfiguredSo() {
        runBlocking {
            app.container.settingsStore.update {
                it.copy(
                    info = it.info.copy(
                        glanceDismissSeconds = InfoSettings.GLANCE_DISMISS_NEVER,
                    ),
                )
            }
        }
        rule.waitForIdle()
        tapFreeArea()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)

        Thread.sleep(3_200)
        rule.waitForIdle()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)

        // Still dismissable by an explicit gesture.
        tapFreeArea()
        assertNull(viewModel.overlay.value)
    }

    // ---- Weather display ----------------------------------------------------

    @Test
    fun weatherRowAppearsInTodayAfterAFetch() {
        val fake = FakeWeatherApi(WeatherReading(21.0, 0, true))
        fakeApi = fake
        app.container.weatherService.api = fake
        enableWeather(tokyo())

        viewModel.openTodayPanel()
        rule.waitForIdle()

        // The fake answer lands through the cache into TODAY.
        runBlocking {
            withTimeout(5_000) {
                viewModel.today.first { it.weather != null }
            }
        }
        val expected = rule.activity.getString(
            R.string.today_weather_line,
            21,
            rule.activity.getString(R.string.weather_clear),
        )
        rule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun staleWeatherIsMarkedInTodayAndHiddenInGlance() {
        // The provider answer is held open so TODAY's open-triggered
        // refresh cannot overwrite the seeded stale snapshot mid-assert.
        val fake = FakeWeatherApi(WeatherReading(99.0, 95, false))
        fake.gate = kotlinx.coroutines.CompletableDeferred()
        fakeApi = fake
        app.container.weatherService.api = fake
        enableWeather(tokyo())
        runBlocking {
            // A reading fetched two hours ago — inside the 6 h window,
            // outside the 60 min freshness limit (design 8.2).
            val elapsed = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val stale = WeatherSnapshot(
                regionKey = tokyo().key,
                temperatureCelsius = 18.0,
                weatherCode = 3,
                isDay = true,
                fetchedAtElapsedMs = elapsed - 2 * 60 * 60_000L,
                fetchedAtWallMs = wall - 2 * 60 * 60_000L,
                bootMarkerMs = wall - elapsed,
            )
            app.container.weatherCacheStore.save(stale, tokyo())
            // Let the cache emission reach the weather UI state before
            // TODAY reads it.
            withTimeout(5_000) {
                app.container.weatherService.ui.first { it.snapshot != null }
            }
        }

        viewModel.openTodayPanel()
        rule.waitForIdle()
        val weatherText = rule.activity.getString(
            R.string.today_weather_line,
            18,
            rule.activity.getString(R.string.weather_overcast),
        )
        rule.onNodeWithText(weatherText).assertIsDisplayed()
        rule.onNodeWithText(
            rule.activity.getString(R.string.weather_updated_at, "")
                .substringBefore("%"),
            substring = true,
        ).assertIsDisplayed()

        // GLANCE shows fresh weather only (design 8.2).
        viewModel.closeOverlay()
        rule.waitForIdle()
        tapFreeArea()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)
        rule.onAllNodesWithText(weatherText).assertCountEquals(0)
    }

    @Test
    fun coreSurfaceKeepsWorkingWithoutWeather() {
        // Offline / disabled: the core surface keeps working (design 8.2 —
        // 取得失敗・オフラインでも中核機能を維持).
        tapFreeArea()
        assertEquals(HomeOverlay.Glance, viewModel.overlay.value)
        viewModel.openTodayPanel()
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.panel_today_title)).assertIsDisplayed()
    }

    // ---- Calendar permission path -------------------------------------------

    @Test
    fun deniedCalendarPermissionKeepsEventsOffToday() {
        // The permission seam stubs denial: a real revokeRuntimePermission
        // on our own package kills the test process, and touching
        // UiAutomation at all flips accessibility_enabled for the rest of
        // the run (which would break the GLANCE timer tests).
        app.container.calendarAccess.permissionCheck = { false }
        runBlocking {
            app.container.settingsStore.update {
                it.copy(info = it.info.copy(eventsEnabled = true))
            }
        }
        viewModel.openTodayPanel()
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.panel_today_title)).assertIsDisplayed()
        assertTrue(viewModel.today.value.events.isEmpty())
        assertTrue(!viewModel.calendarGranted.value)
    }

    @Test
    fun grantedPermissionWithNoSelectionShowsNoEvents() {
        // Granting is process-safe (only revocation kills); the grant is
        // left in place — the next suite run reinstalls the app anyway.
        instrumentation.uiAutomation.grantRuntimePermission(
            app.packageName,
            Manifest.permission.READ_CALENDAR,
        )
        runBlocking {
            app.container.settingsStore.update {
                it.copy(
                    info = it.info.copy(
                        eventsEnabled = true,
                        selectedCalendarIds = emptyList(),
                    ),
                )
            }
        }
        viewModel.openTodayPanel()
        rule.waitForIdle()
        rule.onNodeWithText(res(R.string.panel_today_title))
            .assertIsDisplayed()
        assertTrue(viewModel.today.value.events.isEmpty())
        runBlocking {
            withTimeout(5_000) {
                viewModel.calendarGranted.first { it }
            }
        }
    }

    @Test
    fun revokedMidSessionDropsTheGrantAndEvents() {
        // Mid-session revocation (design 8.1): the next refresh re-checks
        // the permission and drops every in-memory row. The seams flip
        // the OS answers without killing the process under test — and
        // the instance source first proves a non-empty selection is
        // actually displayed before the grant disappears.
        var granted = true
        app.container.calendarAccess.permissionCheck = { granted }
        app.container.calendarAccess.instanceSource = { nowMs, _, _ ->
            listOf(
                io.github.aritouma1205.quietintentlauncher.calendar
                    .RawInstance(
                        eventId = 42L,
                        calendarId = 7L,
                        title = "打ち合わせ",
                        beginMs = nowMs - 3_600_000L,
                        endMs = nowMs + 3_600_000L,
                        allDay = false,
                        deleted = false,
                        canceled = false,
                        declined = false,
                    ),
            )
        }
        runBlocking {
            app.container.settingsStore.update {
                it.copy(
                    info = it.info.copy(
                        eventsEnabled = true,
                        selectedCalendarIds = listOf(7L),
                    ),
                )
            }
        }
        viewModel.openTodayPanel()
        rule.waitForIdle()
        runBlocking {
            withTimeout(5_000) { viewModel.calendarGranted.first { it } }
            withTimeout(5_000) {
                viewModel.today.first { it.events.isNotEmpty() }
            }
        }

        granted = false
        viewModel.refreshEvents()
        rule.waitForIdle()
        runBlocking {
            withTimeout(5_000) { viewModel.calendarGranted.first { !it } }
        }
        assertTrue(viewModel.today.value.events.isEmpty())
    }

    // ---- Info save failure --------------------------------------------------

    @Test
    fun aFailedCacheEraseFailsTheInfoSave() {
        // Disabling weather erases the cache BEFORE the settings write
        // (design 8.2): a failed erase reports failure and the persisted
        // settings stay untouched — weather keeps its enabled state and
        // its still-matching cache.
        enableWeather(tokyo())
        app.container.weatherService.clearCacheHook = { false }
        val data = runBlocking {
            (app.container.settingsStore.state.first {
                it is SettingsState.Ready
            } as SettingsState.Ready).data
        }
        assertTrue(data.info.weather.enabled)

        var result: Boolean? = null
        viewModel.saveInfoSettings(
            data.copy(
                info = data.info.copy(
                    weather = WeatherSettings(enabled = false, location = null),
                ),
            ),
        ) { result = it }
        runBlocking {
            withTimeout(5_000) { while (result == null) delay(10) }
        }
        assertEquals(false, result)

        // The erase ran before the write, so the persisted settings
        // still hold the enabled weather and its region.
        val persisted = runBlocking {
            (app.container.settingsStore.state.first {
                it is SettingsState.Ready
            } as SettingsState.Ready).data
        }
        assertTrue(persisted.info.weather.enabled)
        assertEquals("1850147", persisted.info.weather.location?.providerId)

        // A retry with a working erase completes the save: disabled,
        // region cleared, cache gone.
        app.container.weatherService.clearCacheHook = null
        var retryResult: Boolean? = null
        viewModel.saveInfoSettings(
            data.copy(
                info = data.info.copy(
                    weather = WeatherSettings(enabled = false, location = null),
                ),
            ),
        ) { retryResult = it }
        runBlocking {
            withTimeout(5_000) { while (retryResult == null) delay(10) }
        }
        assertEquals(true, retryResult)
        val saved = runBlocking {
            (app.container.settingsStore.state.first {
                it is SettingsState.Ready
            } as SettingsState.Ready).data
        }
        assertTrue(!saved.info.weather.enabled)
        assertNull(saved.info.weather.location)
    }

    @Test
    fun aProviderFailureLeavesTodayEmptyButAlive() {
        // A stopped/failing Calendar Provider (SQLiteException,
        // DeadObjectException, …) degrades to an empty list instead of
        // crashing the refresh coroutine (design 8.1/A11).
        app.container.calendarAccess.permissionCheck = { true }
        app.container.calendarAccess.instanceSource = { _, _, _ ->
            throw android.database.sqlite.SQLiteException("provider gone")
        }
        runBlocking {
            app.container.settingsStore.update {
                it.copy(
                    info = it.info.copy(
                        eventsEnabled = true,
                        selectedCalendarIds = listOf(7L),
                    ),
                )
            }
        }
        viewModel.openTodayPanel()
        rule.waitForIdle()
        runBlocking {
            withTimeout(5_000) { viewModel.calendarGranted.first { it } }
        }
        // TODAY still renders; the failed query yields no rows and the
        // grant flag stays set (the failure was not a revocation).
        rule.onNodeWithText(res(R.string.panel_today_title)).assertIsDisplayed()
        assertTrue(viewModel.today.value.events.isEmpty())
        assertTrue(viewModel.calendarGranted.value)
    }

    // ---- Clock refresh ------------------------------------------------------

    @Test
    fun backgroundingStopsTheClockTicker() {
        // With the optional Quiet clock enabled the ticker runs — but a
        // backgrounded surface must not keep per-minute work alive
        // (design 15). The ticker condition drops on background and
        // re-engages on the foreground return.
        runBlocking {
            app.container.settingsStore.update {
                it.copy(clock = it.clock.copy(enabled = true))
            }
        }
        runBlocking {
            withTimeout(5_000) { viewModel.clockTickerActive.first { it } }
        }

        viewModel.onBackgrounded()
        rule.waitForIdle()
        runBlocking {
            withTimeout(5_000) { viewModel.clockTickerActive.first { !it } }
        }

        viewModel.onForegrounded()
        rule.waitForIdle()
        runBlocking {
            withTimeout(5_000) { viewModel.clockTickerActive.first { it } }
        }
    }

    @Test
    fun timeChangeRefreshesTheSnapshot() {
        viewModel.openTodayPanel()
        rule.waitForIdle()
        val before = viewModel.today.value.nowMs
        Thread.sleep(20)
        viewModel.onTimeChanged()
        rule.waitForIdle()
        assertTrue(viewModel.today.value.nowMs > before)
    }

    // ---- helpers -------------------------------------------------------------

    private fun tokyo() = WeatherLocation("1850147", "東京", 35.6895, 139.6917)

    private fun enableWeather(region: WeatherLocation) {
        runBlocking {
            app.container.settingsStore.update {
                it.copy(
                    info = it.info.copy(
                        weather = WeatherSettings(enabled = true, location = region),
                    ),
                )
            }
            withTimeout(5_000) {
                app.container.weatherService.ui.first { it.region != null }
            }
        }
    }

    private class FakeWeatherApi(
        private val reading: WeatherReading,
    ) : WeatherApi {
        val calls = CopyOnWriteArrayList<WeatherLocation>()
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun searchLocations(name: String): List<WeatherLocation> =
            emptyList()

        override suspend fun fetchCurrent(
            location: WeatherLocation,
        ): WeatherReading {
            calls += location
            gate?.await()
            return reading
        }
    }
}
