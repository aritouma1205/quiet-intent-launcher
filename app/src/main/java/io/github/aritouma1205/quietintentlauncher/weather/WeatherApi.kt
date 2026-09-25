package io.github.aritouma1205.quietintentlauncher.weather

import io.github.aritouma1205.quietintentlauncher.settings.WeatherLocation
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Raw reading returned by the provider; the caller stamps fetch times. */
data class WeatherReading(
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val isDay: Boolean,
)

/**
 * Weather call failures (design 14.2). Messages never carry request
 * parameters or a response body, so region names and coordinates cannot
 * leak through a crash log or an error UI.
 */
sealed class WeatherException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Network(cause: Throwable) : WeatherException("network failure", cause)
    class Timeout(cause: Throwable? = null) : WeatherException("request timed out", cause)
    class Http(val status: Int) : WeatherException("HTTP $status")
    class TooLarge : WeatherException("response exceeded the size cap")
    class Parse(cause: Throwable? = null) : WeatherException("unparseable response", cause)
}

/** Open-Meteo access (design 8.2): geocoding search + current weather. */
interface WeatherApi {
    suspend fun searchLocations(name: String): List<WeatherLocation>
    suspend fun fetchCurrent(location: WeatherLocation): WeatherReading
}

/**
 * Strict response validation for the Open-Meteo endpoints (design 14.2).
 * Geocoding rows are validated individually so one malformed row cannot
 * discard the usable results; the forecast payload is all-or-nothing so a
 * bad response never corrupts the cache with half a reading.
 */
object WeatherJson {

    /**
     * Geocoding `results` rows -> [WeatherLocation]. An absent `results`
     * field means no matches and yields an empty list; a non-array
     * `results` or a non-object body is a protocol violation and raises
     * [WeatherException.Parse].
     */
    fun parseGeocoding(body: String): List<WeatherLocation> {
        val results = rootObject(body)["results"] ?: return emptyList()
        val array = results as? JsonArray ?: throw WeatherException.Parse()
        return array.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val providerId = providerId(row["id"]) ?: return@mapNotNull null
            val name = stringValue(row["name"])
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val latitude = numberValue(row["latitude"])
                ?.takeIf { it in -90.0..90.0 }
                ?: return@mapNotNull null
            val longitude = numberValue(row["longitude"])
                ?.takeIf { it in -180.0..180.0 }
                ?: return@mapNotNull null
            WeatherLocation(providerId, name, latitude, longitude)
        }
    }

    /**
     * Forecast `current` object -> [WeatherReading]. Every required field
     * must exist with the right JSON type and a sane value; anything else
     * raises [WeatherException.Parse]. The weather code is not range
     * checked — unknown codes map to the generic label downstream.
     */
    fun parseCurrent(body: String): WeatherReading {
        val current = rootObject(body)["current"] as? JsonObject
            ?: throw WeatherException.Parse()
        val temperature = numberValue(current["temperature_2m"])
            ?.takeIf { it in TEMP_MIN..TEMP_MAX }
            ?: throw WeatherException.Parse()
        val code = intValue(current["weather_code"])
            ?: throw WeatherException.Parse()
        val isDay = when (intValue(current["is_day"])) {
            0 -> false
            1 -> true
            else -> throw WeatherException.Parse()
        }
        return WeatherReading(temperature, code, isDay)
    }

    private fun rootObject(body: String): JsonObject {
        val element = try {
            Json.parseToJsonElement(body)
        } catch (e: IllegalArgumentException) {
            throw WeatherException.Parse(e)
        }
        return element as? JsonObject ?: throw WeatherException.Parse()
    }

    /** Number literal only — a quoted number is not a number. */
    private fun numberValue(element: JsonElement?): Double? =
        (element as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.doubleOrNull
            ?.takeIf { it.isFinite() }

    /** Integral number inside the Int range. */
    private fun intValue(element: JsonElement?): Int? {
        val value = numberValue(element) ?: return null
        val int = value.toInt()
        return if (int.toDouble() == value) int else null
    }

    private fun stringValue(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Provider ids print as a long when integral, else as written. */
    private fun providerId(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val value = primitive.doubleOrNull?.takeIf { it.isFinite() } ?: return null
        val whole = value.toLong()
        return if (whole.toDouble() == value) whole.toString() else primitive.content
    }

    /** Sanity bounds for current temperature in Celsius (design 14.2). */
    private const val TEMP_MIN = -100.0
    private const val TEMP_MAX = 70.0
}

/**
 * [WeatherApi] over plain [HttpURLConnection] (design 8.2, 14.2): fixed
 * HTTPS endpoints, 10 s connect/read socket timeouts and a hard 1 MiB
 * response cap. The blocking IO runs interruptibly on [Dispatchers.IO] so
 * a closing screen can cancel an in-flight update (design 15).
 *
 * No overall timeout is applied here — the service owns the 10 s budget;
 * the socket timeouts are the inner safety net. Nothing is logged and
 * exception messages carry no request parameters or response bodies.
 */
class HttpWeatherApi(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxBodyBytes: Int = 1_048_576,
    private val openConnection: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
) : WeatherApi {

    override suspend fun searchLocations(name: String): List<WeatherLocation> {
        @Suppress("DEPRECATION")
        val encoded = URLEncoder.encode(name, Charsets.UTF_8.name())
        return get(
            "$GEOCODING_ENDPOINT?name=$encoded&count=8&language=ja&format=json",
            WeatherJson::parseGeocoding,
        )
    }

    override suspend fun fetchCurrent(location: WeatherLocation): WeatherReading =
        get(
            FORECAST_ENDPOINT +
                "?latitude=${location.latitude}&longitude=${location.longitude}" +
                "&current=temperature_2m,weather_code,is_day&timezone=auto",
            WeatherJson::parseCurrent,
        )

    /**
     * Request + validation as one interruptible IO block, so nothing runs
     * on the caller's dispatcher (design 15).
     */
    private suspend fun <T> get(url: String, parse: (String) -> T): T =
        withContext(Dispatchers.IO) {
            runInterruptible { parse(request(url)) }
        }

    private fun request(url: String): String {
        val connection = try {
            openConnection(url)
        } catch (e: IOException) {
            throw WeatherException.Network(e)
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val status = connection.responseCode
            if (status !in 200..299) throw WeatherException.Http(status)
            // A declared length over the cap fails before a byte is read.
            if (connection.contentLength > maxBodyBytes) {
                throw WeatherException.TooLarge()
            }
            return connection.inputStream.use { stream ->
                readCapped(stream).decodeToString()
            }
        } catch (e: WeatherException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw WeatherException.Timeout(e)
        } catch (e: IOException) {
            throw WeatherException.Network(e)
        } finally {
            connection.disconnect()
        }
    }

    /** Reads at most [maxBodyBytes]; one byte over the cap fails. */
    private fun readCapped(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val remaining = maxBodyBytes + 1 - total
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) return out.toByteArray()
            total += read
            if (total > maxBodyBytes) throw WeatherException.TooLarge()
            out.write(buffer, 0, read)
        }
    }

    private companion object {
        const val GEOCODING_ENDPOINT =
            "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        const val USER_AGENT = "QuietIntentLauncher"
    }
}
