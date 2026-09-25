package io.github.aritouma1205.quietintentlauncher.weather

import androidx.annotation.StringRes
import io.github.aritouma1205.quietintentlauncher.R
import kotlin.math.abs

/**
 * One cached weather reading (design 8.2). The cache file is a separate
 * disposable store; a reading is only ever written for the region still
 * selected when the request completed.
 */
data class WeatherSnapshot(
    /** [io.github.aritouma1205.quietintentlauncher.settings.WeatherLocation.key]. */
    val regionKey: String,
    val temperatureCelsius: Double,
    /** WMO weather interpretation code. */
    val weatherCode: Int,
    val isDay: Boolean,
    /** SystemClock.elapsedRealtime() at fetch — age measurement. */
    val fetchedAtElapsedMs: Long,
    /** System.currentTimeMillis() at fetch — display only. */
    val fetchedAtWallMs: Long,
    /** (wallMs - elapsedMs) at fetch; identifies the boot session. */
    val bootMarkerMs: Long,
)

/** Display state of a cached reading (design 8.2). */
enum class WeatherFreshness { Fresh, Stale, Hidden }

/**
 * Refresh and freshness rules for the opt-in weather (design 8.2, 14.2,
 * 15). Pure functions over injected clocks so every boundary is
 * unit-testable.
 */
object WeatherRules {
    /** Re-fetch when the last success is older than this (design 8.2). */
    const val REFRESH_INTERVAL_MS = 30L * 60_000L
    /** A reading younger than this is shown normally. */
    const val FRESH_LIMIT_MS = 60L * 60_000L
    /** A reading older than this is hidden from TODAY and GLANCE. */
    const val HIDE_LIMIT_MS = 6L * 60 * 60_000L
    /** Automatic retries are suppressed for this long after a failure. */
    const val AUTO_RETRY_BACKOFF_MS = 5L * 60_000L
    /** Minimum interval between manual retries. */
    const val MANUAL_RETRY_MIN_MS = 30_000L
    /** Tolerance for the boot-marker comparison (elapsed vs wall skew). */
    const val BOOT_MARKER_TOLERANCE_MS = 5_000L

    /**
     * Age in ms on the monotonic clock, or null when undeterminable: the
     * boot marker moved (reboot or wall-clock change) or the fetch time
     * lies in the future. Design 8.2 treats an undeterminable age as
     * expired — a future fetch timestamp is never fresh.
     */
    fun ageMs(nowElapsedMs: Long, nowWallMs: Long, snapshot: WeatherSnapshot): Long? {
        val currentBootMarker = nowWallMs - nowElapsedMs
        if (abs(currentBootMarker - snapshot.bootMarkerMs) > BOOT_MARKER_TOLERANCE_MS) {
            return null
        }
        val age = nowElapsedMs - snapshot.fetchedAtElapsedMs
        return if (age < 0) null else age
    }

    /** Fresh within 60 minutes, stale up to 6 hours, hidden beyond or when the age is unknown. */
    fun freshness(nowElapsedMs: Long, nowWallMs: Long, snapshot: WeatherSnapshot?): WeatherFreshness {
        if (snapshot == null) return WeatherFreshness.Hidden
        val age = ageMs(nowElapsedMs, nowWallMs, snapshot) ?: return WeatherFreshness.Hidden
        return when {
            age <= FRESH_LIMIT_MS -> WeatherFreshness.Fresh
            age <= HIDE_LIMIT_MS -> WeatherFreshness.Stale
            else -> WeatherFreshness.Hidden
        }
    }

    /**
     * Short weather label for a WMO weather interpretation code. Unknown
     * codes fall back to the generic label — they are never guessed
     * (design 8.2).
     */
    @StringRes
    fun weatherLabelRes(code: Int): Int = when (code) {
        0 -> R.string.weather_clear
        1 -> R.string.weather_mostly_clear
        2 -> R.string.weather_partly_cloudy
        3 -> R.string.weather_overcast
        45, 48 -> R.string.weather_fog
        51, 53, 55, 56, 57 -> R.string.weather_drizzle
        61, 63, 65 -> R.string.weather_rain
        66, 67 -> R.string.weather_freezing_rain
        71, 73, 75, 77 -> R.string.weather_snow
        80, 81, 82 -> R.string.weather_rain_shower
        85, 86 -> R.string.weather_snow_shower
        95 -> R.string.weather_thunderstorm
        96, 99 -> R.string.weather_thunderstorm_hail
        else -> R.string.weather_unknown
    }
}
