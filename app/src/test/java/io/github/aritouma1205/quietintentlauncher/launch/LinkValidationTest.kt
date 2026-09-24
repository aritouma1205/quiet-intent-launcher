package io.github.aritouma1205.quietintentlauncher.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Save-time HTTPS link validation (design 6): only well-formed https://
 * URIs with a non-empty host may be stored or launched.
 */
class LinkValidationTest {

    @Test
    fun `accepts https with a host`() {
        assertTrue(LinkValidation.isValidHttpsUrl("https://example.com"))
        assertTrue(LinkValidation.isValidHttpsUrl("https://example.com/path?q=1"))
        assertTrue(LinkValidation.isValidHttpsUrl("https://sub.example.co.jp:8443/x"))
    }

    @Test
    fun `rejects http`() {
        assertFalse(LinkValidation.isValidHttpsUrl("http://example.com"))
    }

    @Test
    fun `rejects a missing scheme`() {
        assertFalse(LinkValidation.isValidHttpsUrl("example.com"))
        assertFalse(LinkValidation.isValidHttpsUrl("//example.com"))
        assertFalse(LinkValidation.isValidHttpsUrl("www.example.com/path"))
    }

    @Test
    fun `rejects an empty or malformed host`() {
        assertFalse(LinkValidation.isValidHttpsUrl("https://"))
        assertFalse(LinkValidation.isValidHttpsUrl("https:///path"))
        assertFalse(LinkValidation.isValidHttpsUrl("https://exa mple.com"))
        assertFalse(LinkValidation.isValidHttpsUrl("https://host:bad/"))
    }

    @Test
    fun `rejects javascript and other schemes`() {
        assertFalse(LinkValidation.isValidHttpsUrl("javascript:alert(1)"))
        assertFalse(LinkValidation.isValidHttpsUrl("file:///etc/passwd"))
        assertFalse(LinkValidation.isValidHttpsUrl("intent://example.com"))
    }

    @Test
    fun `rejects whitespace and blanks`() {
        assertFalse(LinkValidation.isValidHttpsUrl(""))
        assertFalse(LinkValidation.isValidHttpsUrl("   "))
        assertFalse(LinkValidation.isValidHttpsUrl(" https://example.com"))
        assertFalse(LinkValidation.isValidHttpsUrl("https://example.com "))
        assertFalse(LinkValidation.isValidHttpsUrl("https://example .com/x"))
    }

    @Test
    fun `rejects malformed uris`() {
        assertFalse(LinkValidation.isValidHttpsUrl("https://["))
        assertFalse(LinkValidation.isValidHttpsUrl("ht tps://example.com"))
        assertFalse(LinkValidation.isValidHttpsUrl("::://example.com"))
    }

    @Test
    fun `httpsHost returns the host for preview`() {
        assertEquals("example.com", LinkValidation.httpsHost("https://example.com/a"))
        assertEquals(
            "sub.example.co.jp",
            LinkValidation.httpsHost("https://sub.example.co.jp:8443/"),
        )
        assertNull(LinkValidation.httpsHost("http://example.com"))
        assertNull(LinkValidation.httpsHost("not a url"))
    }
}
