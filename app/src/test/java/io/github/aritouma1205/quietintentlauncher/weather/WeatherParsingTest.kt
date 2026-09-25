package io.github.aritouma1205.quietintentlauncher.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Strict Open-Meteo response validation (design 14.2): geocoding keeps
 * usable rows when a sibling row is malformed, while the forecast payload
 * is all-or-nothing.
 */
class WeatherParsingTest {

    private fun assertParseFails(body: String, parse: (String) -> Any?) {
        try {
            parse(body)
            fail("expected WeatherException.Parse for: $body")
        } catch (e: WeatherException.Parse) {
            // expected
        }
    }

    private fun assertGeocodingFails(body: String) =
        assertParseFails(body) { WeatherJson.parseGeocoding(it) }

    private fun assertCurrentFails(body: String) =
        assertParseFails(body) { WeatherJson.parseCurrent(it) }

    // --- Geocoding API -----------------------------------------------

    @Test
    fun `geocoding results map to locations`() {
        val locations = WeatherJson.parseGeocoding(
            """{"results":[{"id":1850147,"name":"Tokyo","latitude":35.6895,
              "longitude":139.69171,"country":"Japan","admin1":"Tokyo"}],
              "generationtime_ms":0.5}""",
        )
        val tokyo = locations.single()
        assertEquals("1850147", tokyo.providerId)
        assertEquals("1850147", tokyo.key)
        assertEquals("Tokyo", tokyo.name)
        assertEquals(35.6895, tokyo.latitude, 0.0001)
        assertEquals(139.69171, tokyo.longitude, 0.0001)
        assertTrue(tokyo.isValid)
    }

    @Test
    fun `absent results mean no matches`() {
        assertTrue(
            WeatherJson.parseGeocoding("""{"generationtime_ms":0.5}""").isEmpty(),
        )
        assertTrue(WeatherJson.parseGeocoding("""{"results":[]}""").isEmpty())
    }

    @Test
    fun `a malformed row is skipped while valid rows are kept`() {
        val locations = WeatherJson.parseGeocoding(
            """{"results":[
                 {"id":1,"name":"A","latitude":10.0,"longitude":20.0},
                 {"id":2,"latitude":10.0,"longitude":20.0},
                 "not an object",
                 {"id":3,"name":"C","latitude":-33.8,"longitude":151.2}]}""",
        )
        assertEquals(listOf("1", "3"), locations.map { it.providerId })
        assertEquals(listOf("A", "C"), locations.map { it.name })
    }

    @Test
    fun `rows with out-of-range coordinates are skipped`() {
        val locations = WeatherJson.parseGeocoding(
            """{"results":[
                 {"id":1,"name":"A","latitude":91.0,"longitude":0.0},
                 {"id":2,"name":"B","latitude":0.0,"longitude":-181.0},
                 {"id":3,"name":"C","latitude":-90.0,"longitude":180.0}]}""",
        )
        assertEquals(listOf("C"), locations.map { it.name })
    }

    @Test
    fun `rows with mistyped or blank fields are skipped`() {
        val locations = WeatherJson.parseGeocoding(
            """{"results":[
                 {"id":"1850147","name":"A","latitude":1.0,"longitude":1.0},
                 {"id":5,"name":"  ","latitude":1.0,"longitude":1.0},
                 {"id":6,"name":"B","latitude":"35","longitude":1.0},
                 {"id":7,"name":"C","latitude":1.0,"longitude":1.0}]}""",
        )
        assertEquals(listOf("7"), locations.map { it.providerId })
    }

    @Test
    fun `a non-integral id keeps its literal form`() {
        val locations = WeatherJson.parseGeocoding(
            """{"results":[{"id":1.5,"name":"A","latitude":0.0,"longitude":0.0}]}""",
        )
        assertEquals("1.5", locations.single().providerId)
    }

    @Test
    fun `geocoding rejects a non-object body`() {
        assertGeocodingFails("""[1,2,3]""")
        assertGeocodingFails("12345")
        assertGeocodingFails("not json {{{")
    }

    @Test
    fun `geocoding rejects a non-array results field`() {
        assertGeocodingFails("""{"results":{"id":1}}""")
        assertGeocodingFails("""{"results":null}""")
    }

    // --- Forecast API --------------------------------------------------

    @Test
    fun `current conditions map to a reading`() {
        val reading = WeatherJson.parseCurrent(
            """{"latitude":35.69,"longitude":139.69,
              "current":{"time":"2025-01-15T10:30","temperature_2m":12.4,
              "weather_code":3,"is_day":1}}""",
        )
        assertEquals(12.4, reading.temperatureCelsius, 0.0001)
        assertEquals(3, reading.weatherCode)
        assertTrue(reading.isDay)
    }

    @Test
    fun `is_day zero means night`() {
        val reading = WeatherJson.parseCurrent(
            """{"current":{"temperature_2m":-3.0,"weather_code":71,"is_day":0}}""",
        )
        assertFalse(reading.isDay)
    }

    @Test
    fun `an unknown weather code still parses`() {
        // Codes are not range-checked; the generic label covers them.
        val reading = WeatherJson.parseCurrent(
            """{"current":{"temperature_2m":10.0,"weather_code":47,"is_day":1}}""",
        )
        assertEquals(47, reading.weatherCode)
    }

    @Test
    fun `temperature sanity bounds are inclusive`() {
        listOf(-100.0, 70.0).forEach { temp ->
            val reading = WeatherJson.parseCurrent(
                """{"current":{"temperature_2m":$temp,"weather_code":0,"is_day":0}}""",
            )
            assertEquals(temp, reading.temperatureCelsius, 0.0001)
        }
    }

    @Test
    fun `forecast rejects missing or mistyped fields`() {
        assertCurrentFails("""{"current":{"weather_code":3,"is_day":1}}""")
        assertCurrentFails(
            """{"current":{"temperature_2m":"12.4","weather_code":3,"is_day":1}}""",
        )
        assertCurrentFails(
            """{"current":{"temperature_2m":12.4,"weather_code":"3","is_day":1}}""",
        )
        assertCurrentFails(
            """{"current":{"temperature_2m":12.4,"weather_code":3,"is_day":5}}""",
        )
        assertCurrentFails("""{"current":{"temperature_2m":12.4,"weather_code":3}}""")
    }

    @Test
    fun `forecast rejects temperatures outside the sanity range`() {
        assertCurrentFails(
            """{"current":{"temperature_2m":200.0,"weather_code":3,"is_day":1}}""",
        )
        assertCurrentFails(
            """{"current":{"temperature_2m":-101.0,"weather_code":3,"is_day":1}}""",
        )
    }

    @Test
    fun `forecast rejects a missing current object`() {
        assertCurrentFails("""{"latitude":35.69}""")
        assertCurrentFails("""{"current":[]}""")
    }

    @Test
    fun `forecast rejects a non-json or non-object body`() {
        assertCurrentFails("not json {{{")
        assertCurrentFails("""[1,2,3]""")
    }
}
