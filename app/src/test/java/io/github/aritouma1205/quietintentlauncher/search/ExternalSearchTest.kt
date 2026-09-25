package io.github.aritouma1205.quietintentlauncher.search

import io.github.aritouma1205.quietintentlauncher.settings.WebSearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL shape of the fixed search endpoints (design 9.2). */
class ExternalSearchTest {

    @Test
    fun `each engine builds its fixed HTTPS URL`() {
        assertEquals(
            "https://www.google.com/search?q=test",
            ExternalSearch.webSearchUrl(WebSearchEngine.Google, "test"),
        )
        assertEquals(
            "https://www.bing.com/search?q=test",
            ExternalSearch.webSearchUrl(WebSearchEngine.Bing, "test"),
        )
        assertEquals(
            "https://duckduckgo.com/?q=test",
            ExternalSearch.webSearchUrl(WebSearchEngine.DuckDuckGo, "test"),
        )
    }

    @Test
    fun `queries are encoded and trimmed`() {
        val url = ExternalSearch.webSearchUrl(
            WebSearchEngine.Google,
            "  カメラ アプリ  ",
        )
        assertTrue(url!!.startsWith("https://www.google.com/search?q="))
        // Space encodes as + (form encoding) and multibyte text is UTF-8.
        assertTrue(url.contains("%E3%82%AB%E3%83%A1%E3%83%A9+%E3%82%A2%E3%83%97%E3%83%AA"))
    }

    @Test
    fun `blank queries produce no URL`() {
        assertNull(ExternalSearch.webSearchUrl(WebSearchEngine.Google, ""))
        assertNull(ExternalSearch.webSearchUrl(WebSearchEngine.Google, "   "))
    }
}
