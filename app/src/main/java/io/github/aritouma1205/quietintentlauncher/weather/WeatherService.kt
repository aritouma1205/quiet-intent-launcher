package io.github.aritouma1205.quietintentlauncher.weather

import android.os.SystemClock
import io.github.aritouma1205.quietintentlauncher.settings.SettingsState
import io.github.aritouma1205.quietintentlauncher.settings.SettingsStore
import io.github.aritouma1205.quietintentlauncher.settings.WeatherLocation
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Opt-in weather from Open-Meteo (design 8.2).
 *
 * - Fetches happen only while weather is enabled and only on the designed
 *   triggers (TODAY shown / foreground return / manual retry); there is no
 *   background polling.
 * - At most one request runs at a time; an overall 10 s budget bounds it.
 * - Automatic retries are suppressed for 5 min after a failure; a manual
 *   retry is accepted at most every 30 s.
 * - A response is written to the cache only while its region is still the
 *   selected one — a region change drops in-flight results and erases the
 *   old display/cache entry.
 */
class WeatherService(
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val cacheStore: WeatherCacheStore,
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    /** Network seam; tests substitute a fake implementation. */
    internal var api: WeatherApi = HttpWeatherApi()

    private val _refreshing = MutableStateFlow(false)
    private val _lastFailureAtElapsedMs = MutableStateFlow<Long?>(null)

    /**
     * Display-facing weather state: the selected region plus the cached
     * reading that still belongs to it (key-filtered so a stale cache
     * entry or a late write never leaks into the new region).
     */
    data class WeatherUi(
        val enabled: Boolean = false,
        val region: WeatherLocation? = null,
        val snapshot: WeatherSnapshot? = null,
        val refreshing: Boolean = false,
        val lastFailureAtElapsedMs: Long? = null,
    )

    val ui: StateFlow<WeatherUi> = combine(
        settingsStore.state,
        cacheStore.snapshot,
        _refreshing,
        _lastFailureAtElapsedMs,
    ) { state, snapshot, refreshing, failureAt ->
        val weather = (state as? SettingsState.Ready)?.data?.info?.weather
        val region = weather?.location?.takeIf { weather.enabled && it.isValid }
        WeatherUi(
            enabled = weather?.enabled == true && region != null,
            region = region,
            snapshot = snapshot?.takeIf { it.regionKey == region?.key },
            refreshing = refreshing,
            lastFailureAtElapsedMs = failureAt,
        )
    }.stateIn(scope, SharingStarted.Eagerly, WeatherUi())

    /** The fetch currently in flight; tagged with its region key. */
    @Volatile
    private var fetchJob: Pair<String, Job>? = null

    @Volatile
    private var lastAttemptElapsedMs: Long? = null
    private var lastRegionKey: String? = null

    /** Region-search results for the settings picker. */
    sealed interface RegionSearchState {
        data object Idle : RegionSearchState
        data object Searching : RegionSearchState
        data class Results(val locations: List<WeatherLocation>) : RegionSearchState
        data object Failed : RegionSearchState
    }

    private val _regionResults = MutableStateFlow<RegionSearchState>(RegionSearchState.Idle)
    val regionResults: StateFlow<RegionSearchState> = _regionResults.asStateFlow()
    private var regionSearchJob: Job? = null

    fun start() {
        scope.launch {
            // Region changes drop the old display immediately (the ui
            // filter handles that) and erase the stale cache entry; a
            // response for the old region arriving later is discarded by
            // the expected-region check inside save().
            ui.collect { current ->
                val key = current.region?.key
                if (key != lastRegionKey) {
                    val changed = lastRegionKey != null
                    lastRegionKey = key
                    if (changed) {
                        _lastFailureAtElapsedMs.value = null
                        lastAttemptElapsedMs = null
                        // A request for the old region is still worthless:
                        // its response is discarded, so cancel it now
                        // instead of letting it run out the 10 s budget
                        // (design 15).
                        val inFlight = fetchJob
                        if (inFlight != null && inFlight.first != key) {
                            inFlight.second.cancel()
                        }
                        cacheStore.clear()
                    }
                }
            }
        }
    }

    /**
     * Automatic trigger (TODAY opened / foreground return): fetches only
     * when the last success is older than 30 min (or unreadable) and the
     * post-failure backoff has elapsed. Design 8.2.
     */
    fun requestAutoRefresh() {
        val region = ui.value.region ?: return
        val now = elapsedClock()
        ui.value.snapshot?.let { snapshot ->
            val age = WeatherRules.ageMs(now, wallClock(), snapshot)
            // 「30分以上経過」→ refresh at the boundary, not past it.
            if (age != null && age < WeatherRules.REFRESH_INTERVAL_MS) return
        }
        val failureAt = _lastFailureAtElapsedMs.value
        if (failureAt != null && now - failureAt < WeatherRules.AUTO_RETRY_BACKOFF_MS) return
        startFetch(region, now)
    }

    /**
     * Manual retry from settings: accepted at most every 30 s
     * (design 8.2). Returns false when suppressed.
     */
    fun requestManualRefresh(): Boolean {
        val region = ui.value.region ?: return false
        val now = elapsedClock()
        val last = lastAttemptElapsedMs
        if (last != null && now - last < WeatherRules.MANUAL_RETRY_MIN_MS) return false
        startFetch(region, now)
        return true
    }

    private fun startFetch(region: WeatherLocation, nowElapsedMs: Long) {
        val inFlight = fetchJob
        if (inFlight != null && inFlight.first == region.key && inFlight.second.isActive) {
            // Same region already in flight: the one-request rule (8.2).
            return
        }
        // A different region wins: the old response is discarded anyway,
        // but canceling releases the connection earlier.
        inFlight?.second?.cancel()
        lastAttemptElapsedMs = nowElapsedMs
        _refreshing.value = true
        fetchJob = region.key to scope.launch {
            try {
                val reading = withTimeout(OVERALL_TIMEOUT_MS) {
                    api.fetchCurrent(region)
                }
                val elapsed = elapsedClock()
                val wall = wallClock()
                // Region changed while the request ran: discard, never write.
                if (ui.value.region?.key != region.key) return@launch
                cacheStore.save(
                    WeatherSnapshot(
                        regionKey = region.key,
                        temperatureCelsius = reading.temperatureCelsius,
                        weatherCode = reading.weatherCode,
                        isDay = reading.isDay,
                        fetchedAtElapsedMs = elapsed,
                        fetchedAtWallMs = wall,
                        bootMarkerMs = wall - elapsed,
                    ),
                    expectedRegion = region,
                )
                _lastFailureAtElapsedMs.value = null
            } catch (e: TimeoutCancellationException) {
                if (ui.value.region?.key == region.key) {
                    _lastFailureAtElapsedMs.value = elapsedClock()
                }
            } catch (e: CancellationException) {
                // Job canceled (region change / lifecycle): not a failure.
                throw e
            } catch (e: WeatherException) {
                if (ui.value.region?.key == region.key) {
                    _lastFailureAtElapsedMs.value = elapsedClock()
                }
            } catch (e: Exception) {
                // An unexpected failure must still release the lock and
                // back off — a weather outage must never crash the
                // launcher (design 8.2, 14.2).
                if (ui.value.region?.key == region.key) {
                    _lastFailureAtElapsedMs.value = elapsedClock()
                }
            } finally {
                // Only clear the flag when this job is still the tracked
                // one — a different region's fetch may have taken over.
                if (fetchJob?.second === coroutineContext[Job]) {
                    _refreshing.value = false
                }
            }
        }
    }

    /**
     * Region-name search for the settings picker (design 8.2: the entered
     * place name is sent to the provider — the settings screen explains
     * this before the field is used). Debounced; the latest query wins.
     */
    fun searchRegions(query: String) {
        regionSearchJob?.cancel()
        if (query.isBlank()) {
            _regionResults.value = RegionSearchState.Idle
            return
        }
        regionSearchJob = scope.launch {
            delay(REGION_SEARCH_DEBOUNCE_MS)
            if (!isActive) return@launch
            _regionResults.value = RegionSearchState.Searching
            try {
                val results = withTimeout(OVERALL_TIMEOUT_MS) {
                    api.searchLocations(query)
                }
                _regionResults.value = RegionSearchState.Results(results)
            } catch (e: TimeoutCancellationException) {
                _regionResults.value = RegionSearchState.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: WeatherException) {
                _regionResults.value = RegionSearchState.Failed
            } catch (e: Exception) {
                _regionResults.value = RegionSearchState.Failed
            }
        }
    }

    fun clearRegionSearch() {
        regionSearchJob?.cancel()
        _regionResults.value = RegionSearchState.Idle
    }

    /**
     * Deletes the cached reading (weather disabled in settings).
     * [clearCacheHook] is a test seam for the erase-failure path.
     */
    internal var clearCacheHook: (suspend () -> Boolean)? = null

    suspend fun clearCache(): Boolean = clearCacheHook?.invoke() ?: cacheStore.clear()

    companion object {
        const val OVERALL_TIMEOUT_MS = 10_000L
        private const val REGION_SEARCH_DEBOUNCE_MS = 300L
    }
}
