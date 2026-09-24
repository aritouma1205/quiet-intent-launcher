package io.github.aritouma1205.quietintentlauncher.settings

import kotlinx.serialization.Serializable

/** GLANCE strip position (design 8.3). */
enum class GlancePosition { Top, Center, Bottom }

/** Optional Quiet clock position (design 12). */
enum class QuietClockPosition { TopStart, TopCenter, TopEnd }

/**
 * Optional small clock on the Quiet surface (design 12). Disabled by
 * default; when off, no launcher-owned ticker runs on Quiet (design 15).
 */
@Serializable
data class ClockSettings(
    val enabled: Boolean = false,
    val position: QuietClockPosition = QuietClockPosition.TopStart,
)

/**
 * One region the user picked from the Open-Meteo geocoding results
 * (design 8.2, 14.1). Kept inside settings while weather is enabled and
 * deleted together with the weather cache when weather is disabled.
 */
@Serializable
data class WeatherLocation(
    /** Provider-side region id; ties a cached reading to its region. */
    val providerId: String,
    /** Display name shown in TODAY and settings. */
    val name: String,
    val latitude: Double,
    val longitude: Double,
) {
    val isValid: Boolean
        get() = providerId.isNotBlank() &&
            name.isNotBlank() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0

    /** Stable key used to match in-flight responses and cache entries. */
    val key: String
        get() = providerId
}

/** Opt-in weather display (design 8.2). Disabled by default. */
@Serializable
data class WeatherSettings(
    val enabled: Boolean = false,
    val location: WeatherLocation? = null,
) {
    /**
     * Design 14.1: keep stored values inside the designed domain. An
     * enabled flag without a valid region cannot fetch anything, so it
     * decodes to disabled.
     */
    fun sanitized(): WeatherSettings {
        val validLocation = location?.takeIf { it.isValid }
        return copy(enabled = enabled && validLocation != null, location = validLocation)
    }
}

/**
 * 「情報」 section of settings (design 11.2): GLANCE position and timeout,
 * opt-in weather and the opt-in calendar event feed.
 */
@Serializable
data class InfoSettings(
    val glancePosition: GlancePosition = GlancePosition.Top,
    /**
     * Auto-dismiss delay in seconds. [GLANCE_DISMISS_NEVER] keeps the
     * overlay until an explicit close; any other value is clamped to
     * [GLANCE_DISMISS_MIN]..[GLANCE_DISMISS_MAX].
     */
    val glanceDismissSeconds: Int = GLANCE_DISMISS_DEFAULT,
    val weather: WeatherSettings = WeatherSettings(),
    val eventsEnabled: Boolean = false,
    /** User-selected calendar ids; empty means none selected. */
    val selectedCalendarIds: List<Long> = emptyList(),
) {
    fun sanitized(): InfoSettings = copy(
        glanceDismissSeconds = when (glanceDismissSeconds) {
            GLANCE_DISMISS_NEVER -> GLANCE_DISMISS_NEVER
            else -> glanceDismissSeconds.coerceIn(GLANCE_DISMISS_MIN, GLANCE_DISMISS_MAX)
        },
        weather = weather.sanitized(),
        selectedCalendarIds = selectedCalendarIds.distinct(),
    )

    companion object {
        const val GLANCE_DISMISS_NEVER = 0
        const val GLANCE_DISMISS_MIN = 2
        const val GLANCE_DISMISS_MAX = 10
        const val GLANCE_DISMISS_DEFAULT = 3
    }
}
