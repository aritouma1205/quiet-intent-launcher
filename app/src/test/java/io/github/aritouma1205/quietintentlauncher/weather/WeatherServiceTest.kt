package io.github.aritouma1205.quietintentlauncher.weather

import androidx.datastore.core.DataStore
import io.github.aritouma1205.quietintentlauncher.settings.InfoSettings
import io.github.aritouma1205.quietintentlauncher.settings.SettingsData
import io.github.aritouma1205.quietintentlauncher.settings.SettingsSerializer
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.settings.SettingsStore
import io.github.aritouma1205.quietintentlauncher.settings.WeatherLocation
import io.github.aritouma1205.quietintentlauncher.settings.WeatherSettings
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WeatherService orchestration (design 8.2): single in-flight fetch,
 * freshness-gated auto refresh, failure backoff, manual-retry minimum,
 * and the region-change race where a late response must never reach the
 * new region's cache. Clocks and the network seam are injected; stores
 * are the real classes over in-memory DataStores.
 */
class WeatherServiceTest {

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var settingsStore: SettingsStore
    private lateinit var cacheStore: WeatherCacheStore
    private lateinit var service: WeatherService
    private lateinit var api: FakeWeatherApi

    /** Injectable clocks — the service measures ages on these only. */
    private var elapsedMs = 0L
    private var wallMs = 1_000_000_000_000L

    private val regionA = WeatherLocation("100", "Tokyo", 35.0, 139.0)
    private val regionB = WeatherLocation("200", "Osaka", 34.7, 135.5)

    @Before
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("weather-service-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        api = FakeWeatherApi()
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun fakeSettingsStore(initial: SettingsData): SettingsStore {
        val state = MutableStateFlow(initial)
        val fake = object : DataStore<SettingsData> {
            override val data: Flow<SettingsData> = state
            override suspend fun updateData(
                transform: suspend (SettingsData) -> SettingsData,
            ): SettingsData {
                val next = transform(state.value)
                state.value = next
                return next
            }
        }
        return SettingsStore(
            scope = scope,
            serializer = SettingsSerializer(),
            fileProvider = { File(dir, "quiet_settings.json") },
        ) { fake }
    }

    private fun fakeCacheStore(): WeatherCacheStore {
        val state = MutableStateFlow(WeatherCacheData())
        val fake = object : DataStore<WeatherCacheData> {
            override val data: Flow<WeatherCacheData> = state
            override suspend fun updateData(
                transform: suspend (WeatherCacheData) -> WeatherCacheData,
            ): WeatherCacheData {
                val next = transform(state.value)
                state.value = next
                return next
            }
        }
        return WeatherCacheStore(
            scope = scope,
            serializer = WeatherCacheSerializer(),
            fileProvider = { File(dir, "quiet_weather.json") },
        ) { fake }
    }

    private fun startService(settings: SettingsData) = runBlocking {
        settingsStore = fakeSettingsStore(settings)
        cacheStore = fakeCacheStore()
        service = WeatherService(
            scope = scope,
            settingsStore = settingsStore,
            cacheStore = cacheStore,
            elapsedClock = { elapsedMs },
            wallClock = { wallMs },
        )
        service.api = api
        settingsStore.start()
        cacheStore.start()
        service.start()
        // Wait until the settings and the cache are observed.
        settingsStore.state.first { it is SettingsState.Ready }
        withTimeout(5_000) { while (!cacheStore.isOpen) delay(10) }
    }

    private fun enabledIn(region: WeatherLocation): SettingsData = SettingsData(
        info = InfoSettings(
            weather = WeatherSettings(enabled = true, location = region),
        ),
    )

    private fun snapshot(region: WeatherLocation, ageAgoMs: Long): WeatherSnapshot =
        WeatherSnapshot(
            regionKey = region.key,
            temperatureCelsius = 20.0,
            weatherCode = 0,
            isDay = true,
            fetchedAtElapsedMs = elapsedMs - ageAgoMs,
            fetchedAtWallMs = wallMs - ageAgoMs,
            bootMarkerMs = wallMs - elapsedMs,
        )

    // ---- Enable gate ------------------------------------------------------

    @Test
    fun disabledWeatherNeverFetches() = runBlocking {
        startService(SettingsData())
        service.requestAutoRefresh()
        delay(200)
        assertTrue(api.calls.isEmpty())
    }

    // ---- Freshness-driven refresh -----------------------------------------

    @Test
    fun autoRefreshSkipsAReadingYoungerThanThirtyMinutes() = runBlocking {
        startService(enabledIn(regionA))
        service.ui.first { it.region != null }
        assertTrue(cacheStore.save(snapshot(regionA, ageAgoMs = 10 * 60_000L), regionA))
        cacheStore.snapshot.first { it != null }

        service.requestAutoRefresh()
        delay(200)
        assertTrue(api.calls.isEmpty())

        // 31 minutes old: the refresh interval has passed.
        elapsedMs += 31 * 60_000L
        wallMs += 31 * 60_000L
        service.requestAutoRefresh()
        withTimeout(5_000) { cacheStore.snapshot.first { it?.temperatureCelsius == 42.0 } }
        assertEquals(1, api.calls.size)
    }

    @Test
    fun autoRefreshFetchesAtTheThirtyMinuteBoundary() = runBlocking {
        startService(enabledIn(regionA))
        service.ui.first { it.region != null }
        cacheStore.save(snapshot(regionA, ageAgoMs = 0L), regionA)
        cacheStore.snapshot.first { it != null }

        // 「30分以上経過」: at exactly 30 minutes the reading is due.
        elapsedMs += WeatherRules.REFRESH_INTERVAL_MS
        wallMs += WeatherRules.REFRESH_INTERVAL_MS
        service.requestAutoRefresh()
        withTimeout(5_000) {
            cacheStore.snapshot.first { it?.temperatureCelsius == 42.0 }
        }
        assertEquals(1, api.calls.size)
    }

    // ---- Single in-flight --------------------------------------------------

    @Test
    fun aFetchAlreadyInFlightIsNotDuplicated() = runBlocking {
        startService(enabledIn(regionA))
        service.ui.first { it.region != null }
        api.gate = CompletableDeferred()

        service.requestAutoRefresh()
        service.requestAutoRefresh()
        delay(200)
        assertEquals(1, api.calls.size)

        api.gate?.complete(Unit)
        withTimeout(5_000) { cacheStore.snapshot.first { it != null } }
        assertEquals(1, api.calls.size)
    }

    // ---- Failure handling --------------------------------------------------

    @Test
    fun aFailureSuppressesAutoRetriesForFiveMinutes() = runBlocking {
        startService(enabledIn(regionA))
        service.ui.first { it.region != null }
        api.behavior = { throw WeatherException.Network(java.io.IOException("down")) }

        service.requestAutoRefresh()
        withTimeout(5_000) {
            service.ui.first { it.lastFailureAtElapsedMs != null }
        }
        assertEquals(1, api.calls.size)

        // Inside the backoff window: no retry.
        elapsedMs += 4 * 60_000L
        wallMs += 4 * 60_000L
        service.requestAutoRefresh()
        delay(200)
        assertEquals(1, api.calls.size)

        // After five minutes the next trigger retries.
        elapsedMs += 2 * 60_000L
        wallMs += 2 * 60_000L
        api.behavior = { WeatherReading(10.0, 1, true) }
        service.requestAutoRefresh()
        withTimeout(5_000) { cacheStore.snapshot.first { it != null } }
        assertEquals(2, api.calls.size)
        assertNull(service.ui.value.lastFailureAtElapsedMs)
    }

    @Test
    fun anUnexpectedExceptionMarksFailureWithoutCrashing() = runBlocking {
        startService(enabledIn(regionA))
        service.ui.first { it.region != null }
        api.behavior = { throw IllegalStateException("boom") }

        service.requestAutoRefresh()
        withTimeout(5_000) {
            service.ui.first { it.lastFailureAtElapsedMs != null }
        }
        assertEquals(1, api.calls.size)
    }

    @Test
    fun anOverallTimeoutMarksFailure() = runBlocking {
        startService(enabledIn(regionA))
        service.ui.first { it.region != null }
        api.gate = CompletableDeferred() // never completes

        service.requestAutoRefresh()
        withTimeout(15_000) {
            service.ui.first { it.lastFailureAtElapsedMs != null }
        }
        assertEquals(1, api.calls.size)
    }

    // ---- Manual retry ------------------------------------------------------

    @Test
    fun manualRetryIsLimitedToEveryThirtySeconds() = runBlocking {
        startService(enabledIn(regionA))
        service.ui.first { it.region != null }

        assertTrue(service.requestManualRefresh())
        withTimeout(5_000) { cacheStore.snapshot.first { it != null } }
        assertEquals(1, api.calls.size)

        // Too soon after the last attempt — refused.
        assertFalse(service.requestManualRefresh())
        assertEquals(1, api.calls.size)

        elapsedMs += WeatherRules.MANUAL_RETRY_MIN_MS
        wallMs += WeatherRules.MANUAL_RETRY_MIN_MS
        assertTrue(service.requestManualRefresh())
        withTimeout(5_000) { while (api.calls.size < 2) delay(10) }
        assertEquals(2, api.calls.size)
    }

    // ---- Region-change race -------------------------------------------------

    @Test
    fun aLateResponseForTheOldRegionIsDiscarded() = runBlocking {
        startService(enabledIn(regionA))
        service.ui.first { it.region?.key == regionA.key }
        api.gate = CompletableDeferred()

        service.requestAutoRefresh()
        delay(100)
        assertEquals(1, api.calls.size)

        // Region changes while the request is in flight.
        settingsStore.update { enabledIn(regionB) }
        service.ui.first { it.region?.key == regionB.key }

        api.gate?.complete(Unit)
        delay(300)

        // The A-response must never reach B's cache or display, and no
        // replacement fetch is started on its own — fetches only happen
        // on the designed triggers (TODAY open / foreground return).
        assertNull(cacheStore.snapshot.value)
        assertNull(service.ui.value.snapshot)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aRegionChangeCancelsTheInFlightFetch() = runBlocking {
        startService(enabledIn(regionA))
        service.ui.first { it.region?.key == regionA.key }
        api.gate = CompletableDeferred() // never completes

        service.requestAutoRefresh()
        withTimeout(5_000) { service.ui.first { it.refreshing } }

        // Region changes mid-request: the obsolete fetch is cancelled
        // right away instead of running out its timeout (design 15).
        settingsStore.update { enabledIn(regionB) }
        withTimeout(5_000) { service.ui.first { !it.refreshing } }
        assertEquals(1, api.calls.size)
    }

    @Test
    fun aCacheEntryOfAnotherRegionIsNotShown() = runBlocking {
        startService(enabledIn(regionA))
        service.ui.first { it.region?.key == regionA.key }

        // A stale cache entry written for a different region stays hidden.
        val foreign = snapshot(regionB, ageAgoMs = 60_000L)
        cacheStore.save(foreign, regionB)
        cacheStore.snapshot.first { it != null }
        delay(200)
        assertNull(service.ui.value.snapshot)
    }

    private class FakeWeatherApi : WeatherApi {
        val calls = CopyOnWriteArrayList<WeatherLocation>()
        var gate: CompletableDeferred<Unit>? = null
        var behavior: (WeatherLocation) -> WeatherReading = {
            WeatherReading(42.0, 1, true)
        }

        override suspend fun searchLocations(name: String): List<WeatherLocation> =
            emptyList()

        override suspend fun fetchCurrent(location: WeatherLocation): WeatherReading {
            calls += location
            gate?.await()
            return behavior(location)
        }
    }
}
