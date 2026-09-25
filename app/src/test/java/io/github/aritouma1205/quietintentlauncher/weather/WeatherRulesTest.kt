package io.github.aritouma1205.quietintentlauncher.weather

import io.github.aritouma1205.quietintentlauncher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Freshness boundaries of the weather cache (design 8.2): a reading is
 * fresh for 60 minutes, shown with its fetch time up to 6 hours and hidden
 * beyond. The age is only valid within the same boot — an undeterminable
 * age hides the value instead of showing a stale one.
 */
class WeatherRulesTest {

    // Fixed "now": 1_000_000 ms elapsed since boot; the boot marker is the
    // (wall - elapsed) offset a snapshot stamps at fetch time.
    private val bootMarker = 1_700_000_000_000L
    private val nowElapsed = 1_000_000L
    private val nowWall = bootMarker + nowElapsed

    private fun snapshot(ageMs: Long, marker: Long = bootMarker) = WeatherSnapshot(
        regionKey = "1850147",
        temperatureCelsius = 12.4,
        weatherCode = 3,
        isDay = true,
        fetchedAtElapsedMs = nowElapsed - ageMs,
        fetchedAtWallMs = nowWall - ageMs,
        bootMarkerMs = marker,
    )

    private fun freshness(ageMs: Long, marker: Long = bootMarker) =
        WeatherRules.freshness(nowElapsed, nowWall, snapshot(ageMs, marker))

    @Test
    fun `a reading exactly 60 minutes old is fresh`() {
        assertEquals(WeatherFreshness.Fresh, freshness(WeatherRules.FRESH_LIMIT_MS))
    }

    @Test
    fun `a reading just over 60 minutes old is stale`() {
        assertEquals(
            WeatherFreshness.Stale,
            freshness(WeatherRules.FRESH_LIMIT_MS + 1),
        )
    }

    @Test
    fun `a reading exactly 6 hours old is still stale`() {
        assertEquals(WeatherFreshness.Stale, freshness(WeatherRules.HIDE_LIMIT_MS))
    }

    @Test
    fun `a reading over 6 hours old is hidden`() {
        assertEquals(
            WeatherFreshness.Hidden,
            freshness(WeatherRules.HIDE_LIMIT_MS + 1),
        )
    }

    @Test
    fun `a missing reading is hidden`() {
        assertEquals(
            WeatherFreshness.Hidden,
            WeatherRules.freshness(nowElapsed, nowWall, null),
        )
    }

    @Test
    fun `a reading from a previous boot is hidden`() {
        // A reboot shifts (wall - elapsed) far beyond the tolerance.
        val marker = bootMarker + WeatherRules.BOOT_MARKER_TOLERANCE_MS + 1
        assertNull(WeatherRules.ageMs(nowElapsed, nowWall, snapshot(0, marker)))
        assertEquals(WeatherFreshness.Hidden, freshness(10 * 60_000L, marker))
    }

    @Test
    fun `a boot marker inside the tolerance still measures age`() {
        val marker = bootMarker + WeatherRules.BOOT_MARKER_TOLERANCE_MS
        assertEquals(WeatherFreshness.Fresh, freshness(10 * 60_000L, marker))
    }

    @Test
    fun `a wall clock change breaks the boot marker and hides the reading`() {
        val moved = WeatherRules.freshness(
            nowElapsed,
            nowWall + 60_000L,
            snapshot(10 * 60_000L),
        )
        assertEquals(WeatherFreshness.Hidden, moved)
    }

    @Test
    fun `a future fetch time is never fresh`() {
        assertNull(WeatherRules.ageMs(nowElapsed, nowWall, snapshot(-1)))
        assertEquals(WeatherFreshness.Hidden, freshness(-1))
    }

    @Test
    fun `age is measured on the monotonic clock`() {
        assertEquals(
            42_000L,
            WeatherRules.ageMs(nowElapsed, nowWall, snapshot(42_000L)),
        )
    }

    @Test
    fun `wmo codes map to their short labels`() {
        assertEquals(R.string.weather_clear, WeatherRules.weatherLabelRes(0))
        assertEquals(R.string.weather_mostly_clear, WeatherRules.weatherLabelRes(1))
        assertEquals(R.string.weather_partly_cloudy, WeatherRules.weatherLabelRes(2))
        assertEquals(R.string.weather_overcast, WeatherRules.weatherLabelRes(3))
        assertEquals(R.string.weather_fog, WeatherRules.weatherLabelRes(45))
        assertEquals(R.string.weather_fog, WeatherRules.weatherLabelRes(48))
        assertEquals(R.string.weather_drizzle, WeatherRules.weatherLabelRes(51))
        assertEquals(R.string.weather_rain, WeatherRules.weatherLabelRes(61))
        assertEquals(R.string.weather_freezing_rain, WeatherRules.weatherLabelRes(66))
        assertEquals(R.string.weather_snow, WeatherRules.weatherLabelRes(71))
        assertEquals(R.string.weather_snow, WeatherRules.weatherLabelRes(77))
        assertEquals(R.string.weather_rain_shower, WeatherRules.weatherLabelRes(80))
        assertEquals(R.string.weather_snow_shower, WeatherRules.weatherLabelRes(85))
        assertEquals(R.string.weather_thunderstorm, WeatherRules.weatherLabelRes(95))
        assertEquals(R.string.weather_thunderstorm_hail, WeatherRules.weatherLabelRes(96))
        assertEquals(R.string.weather_thunderstorm_hail, WeatherRules.weatherLabelRes(99))
    }

    @Test
    fun `unknown codes map to the generic label`() {
        assertEquals(R.string.weather_unknown, WeatherRules.weatherLabelRes(999))
        assertEquals(R.string.weather_unknown, WeatherRules.weatherLabelRes(-1))
        assertEquals(R.string.weather_unknown, WeatherRules.weatherLabelRes(4))
    }
}
