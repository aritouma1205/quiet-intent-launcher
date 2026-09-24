package io.github.aritouma1205.quietintentlauncher.launch

import java.net.URI
import java.net.URISyntaxException

/**
 * Save-time validation for HTTPS link targets (design 6): only well-formed
 * https:// URIs with a non-empty host are stored or launched. Pure JVM so
 * the boundary cases stay unit-testable.
 */
object LinkValidation {

    fun isValidHttpsUrl(raw: String): Boolean {
        if (raw.isBlank() || raw.any { it.isWhitespace() }) return false
        val uri = try {
            URI(raw)
        } catch (e: URISyntaxException) {
            return false
        }
        // Scheme comparison is case-insensitive per RFC 3986; http,
        // javascript:, missing schemes and malformed hosts are rejected.
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host ?: return false
        return host.isNotBlank()
    }

    /** Host shown in the editor so the destination is visible before saving. */
    fun httpsHost(raw: String): String? =
        if (isValidHttpsUrl(raw)) {
            try {
                URI(raw).host
            } catch (e: URISyntaxException) {
                null
            }
        } else {
            null
        }
}
