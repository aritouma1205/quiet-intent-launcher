package io.github.aritouma1205.quietintentlauncher.weather

import io.github.aritouma1205.quietintentlauncher.settings.WeatherLocation
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * HttpURLConnection wiring of the weather provider (design 14.2): the
 * response cap fails both a declared Content-Length and a stream that
 * runs past the limit — unbounded data is never read. The connection
 * is injected so no real network is touched.
 */
class HttpWeatherApiTest {

    private fun fakeApi(
        body: ByteArray,
        declaredLength: Int = -1,
    ): HttpWeatherApi = HttpWeatherApi(
        maxBodyBytes = CAP_BYTES,
        openConnection = {
            object : HttpURLConnection(URL("https://example.invalid/")) {
                override fun getResponseCode() = 200

                override fun getContentLength() = declaredLength

                override fun getInputStream(): InputStream =
                    ByteArrayInputStream(body)

                override fun connect() {}

                override fun disconnect() {}

                override fun usingProxy() = false
            }
        },
    )

    @Test
    fun aDeclaredLengthOverTheCapFailsBeforeReading() = runBlocking {
        // A stream that would explode if read proves nothing is
        // consumed once the declared length trips the cap.
        val api = HttpWeatherApi(
            maxBodyBytes = CAP_BYTES,
            openConnection = {
                object : HttpURLConnection(URL("https://example.invalid/")) {
                    override fun getResponseCode() = 200

                    override fun getContentLength() = CAP_BYTES + 1

                    override fun getInputStream(): InputStream =
                        throw AssertionError("stream must not be read")

                    override fun connect() {}

                    override fun disconnect() {}

                    override fun usingProxy() = false
                }
            },
        )
        try {
            api.fetchCurrent(TOKYO)
            fail("expected WeatherException.TooLarge")
        } catch (e: WeatherException.TooLarge) {
            // expected
        }
    }

    @Test
    fun aBodyStreamingPastTheCapFails() = runBlocking {
        try {
            fakeApi(ByteArray(CAP_BYTES + 1)).fetchCurrent(TOKYO)
            fail("expected WeatherException.TooLarge")
        } catch (e: WeatherException.TooLarge) {
            // expected
        }
    }

    @Test
    fun aBodyWithinTheCapParses() = runBlocking {
        val body = (
            """{"current":{"temperature_2m":12.4,""" +
                """"weather_code":3,"is_day":1}}"""
            ).toByteArray()
        val reading = fakeApi(body).fetchCurrent(TOKYO)
        assertEquals(12.4, reading.temperatureCelsius, 0.0001)
        assertEquals(3, reading.weatherCode)
    }

    private companion object {
        const val CAP_BYTES = 1024
        val TOKYO = WeatherLocation("1850147", "Tokyo", 35.6895, 139.6917)
    }
}
